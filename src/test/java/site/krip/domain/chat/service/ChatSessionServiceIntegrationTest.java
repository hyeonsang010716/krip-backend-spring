package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.support.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 세션 서비스 통합 테스트 — 실 Redis 로 세션 생성/존재/종료, 한도 eviction, revoke 검증.
 * SessionService 는 순수 Redis 연산이라 DB 유저 없이 임의 user_id 로 검증.
 */
@DisplayName("세션 서비스 — 한도 evict·jti 갱신·세션 폐기")
class ChatSessionServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    @Qualifier("enforceSessionLimitScript")
    @SuppressWarnings("rawtypes")
    private RedisScript enforceSessionLimitScript;

    private static String randomUser() {
        return "user-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("세션 생성→존재→종료 라이프사이클")
    void createExistsTerminate() {
        String u = randomUser();
        String sid = sessionService.createSession(u, "jti-1");

        assertThat(sessionService.sessionExists(sid)).isTrue();

        sessionService.terminateSession(sid, u);
        assertThat(sessionService.sessionExists(sid)).isFalse();
    }

    @Test
    @DisplayName("세션 한도(10): 11번째 생성 시 가장 오래된 세션이 evict 되어 총 10개만 유지")
    void sessionLimitEvictsOldest() {
        // given
        String u = randomUser();
        List<String> ids = new ArrayList<>();

        // when
        for (int i = 0; i < ChatRedisKeys.MAX_SESSIONS_PER_USER + 1; i++) {
            ids.add(sessionService.createSession(u, "jti-" + i));
        }

        // then
        long alive = ids.stream().filter(sessionService::sessionExists).count();
        assertThat(alive).isEqualTo((long) ChatRedisKeys.MAX_SESSIONS_PER_USER);
        // 가장 최근 세션은 살아있다.
        assertThat(sessionService.sessionExists(ids.get(ids.size() - 1))).isTrue();
    }

    @Test
    @DisplayName("score tie: 갓 만든(사전순 최저) 세션은 보호되고 기존 세션이 evict 된다")
    @SuppressWarnings("unchecked")
    void protectsNewlyCreatedSessionOnScoreTie() {
        // given
        String u = randomUser();
        String zkey = ChatRedisKeys.sessions(u);
        long score = System.currentTimeMillis() + 90_000; // 모두 동일 score → 순수 member 사전순 tie

        // 한도(10)를 기존 세션으로 채운다 — 새 세션보다 사전순으로 '높게' 정렬되도록 'WS_z_' 접두.
        for (int i = 0; i < ChatRedisKeys.MAX_SESSIONS_PER_USER; i++) {
            redis.opsForZSet().add(zkey, "WS_z_" + i, score);
        }
        // 갓 만든 세션 — 같은 score, 사전순 최저(tie 시 victim 1순위 위치). 이게 evict 되면 버그.
        String fresh = "WS_a_new";
        redis.opsForZSet().add(zkey, fresh, score);

        // when
        List<String> evicted = (List<String>) redis.execute(
                enforceSessionLimitScript,
                List.of(zkey),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(ChatRedisKeys.MAX_SESSIONS_PER_USER),
                fresh);

        // then
        // 정확히 1개 evict, 그건 fresh 가 아니라 기존 세션. fresh 는 생존, 총 10개 유지.
        assertThat(evicted).hasSize(1);
        assertThat(evicted.get(0)).isNotEqualTo(fresh).startsWith("WS_z_");
        assertThat(redis.opsForZSet().score(zkey, fresh)).isNotNull();
        assertThat(redis.opsForZSet().zCard(zkey)).isEqualTo((long) ChatRedisKeys.MAX_SESSIONS_PER_USER);
    }

    @Test
    @DisplayName("동시 접속이 한도(10)를 정확히 지킨다 — 단일 Lua 원자화로 over/under-eviction 없음")
    void concurrentCreateEnforcesExactLimit() throws Exception {
        // given
        String u = randomUser();
        int n = 30;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();                       // 동시 출발 — 레이스 유발
                return sessionService.createSession(u, "jti-" + idx);
            }));
        }

        // when
        start.countDown();

        List<String> sids = new ArrayList<>();
        for (Future<String> f : futures) {
            sids.add(f.get());
        }
        pool.shutdown();

        // then
        long alive = sids.stream().filter(sessionService::sessionExists).count();
        Long zcard = redis.opsForZSet().zCard(ChatRedisKeys.sessions(u));

        // 정확히 10 — 초과(미적용)도, 10 미만(over-eviction)도 아니어야 한다. ZSET 과 sess: 키도 일관.
        assertThat(alive).isEqualTo(ChatRedisKeys.MAX_SESSIONS_PER_USER);
        assertThat(zcard).isEqualTo((long) ChatRedisKeys.MAX_SESSIONS_PER_USER);
    }

    @Test
    @DisplayName("updateTokenJti: 만료된 세션은 부활시키지 않는다 — HSET 좀비 해시 누수 차단")
    void updateTokenJtiDoesNotResurrectExpiredSession() {
        // given
        String u = randomUser();
        String sid = sessionService.createSession(u, "jti-1");
        // 세션 만료 시뮬레이션 — sess: 해시 삭제.
        redis.delete(ChatRedisKeys.sess(sid));
        assertThat(sessionService.sessionExists(sid)).isFalse();

        // when
        // 만료 후 token refresh 가 들어와도 키가 부활하면 안 된다(과거 HSET 은 TTL 없는 좀비로 부활시켰음).
        sessionService.updateTokenJti(sid, "jti-2");

        // then
        assertThat(redis.hasKey(ChatRedisKeys.sess(sid))).isFalse();
        assertThat(sessionService.sessionExists(sid)).isFalse();
    }

    @Test
    @DisplayName("updateTokenJti: 살아있는 세션은 token_jti 갱신 + TTL 유지(좀비 아님)")
    void updateTokenJtiUpdatesLiveSessionAndKeepsTtl() {
        // given
        String u = randomUser();
        String sid = sessionService.createSession(u, "jti-1");

        // when
        sessionService.updateTokenJti(sid, "jti-2");

        // then
        assertThat(redis.opsForHash().get(ChatRedisKeys.sess(sid), "token_jti")).isEqualTo("jti-2");
        // 갱신이 TTL 을 날리지 않았는지 — 생성 시 건 만료가 그대로 남아 있어야 한다(좀비 방지).
        Long ttl = redis.getExpire(ChatRedisKeys.sess(sid));
        assertThat(ttl).isNotNull().isGreaterThan(0L);
    }

    @Test
    @DisplayName("revokeSessionsByTokenJti: 해당 토큰(jti) 세션만 끊고 다른 토큰 세션은 유지")
    void revokeSessionsByTokenJtiTerminatesOnlyMatchingToken() {
        // given
        String u = randomUser();
        String a1 = sessionService.createSession(u, "jti-A"); // 토큰 A 기기
        String a2 = sessionService.createSession(u, "jti-A"); // 토큰 A 재연결(같은 토큰)
        String b1 = sessionService.createSession(u, "jti-B"); // 토큰 B 기기(다른 기기)

        // when
        int revoked = sessionService.revokeSessionsByTokenJti(u, "jti-A");

        // then
        assertThat(revoked).isEqualTo(2);
        assertThat(sessionService.sessionExists(a1)).isFalse();
        assertThat(sessionService.sessionExists(a2)).isFalse();
        // 다른 토큰(기기)은 그대로.
        assertThat(sessionService.sessionExists(b1)).isTrue();
        assertThat(redis.opsForZSet().zCard(ChatRedisKeys.sessions(u))).isEqualTo(1L);
    }

    @Test
    @DisplayName("revokeSessionsByTokenJti: 매칭 토큰 없으면 0, 다른 세션 무영향")
    void revokeSessionsByTokenJtiNoMatchIsNoop() {
        String u = randomUser();
        String b1 = sessionService.createSession(u, "jti-B");

        assertThat(sessionService.revokeSessionsByTokenJti(u, "jti-A")).isZero();
        assertThat(sessionService.sessionExists(b1)).isTrue();
    }

    @Test
    @DisplayName("revokeAllSessions: 유저의 모든 세션 제거 후 개수 반환")
    void revokeAllSessions() {
        // given
        String u = randomUser();
        String s1 = sessionService.createSession(u, "jti-1");
        String s2 = sessionService.createSession(u, "jti-2");
        String s3 = sessionService.createSession(u, "jti-3");

        // when
        int revoked = sessionService.revokeAllSessions(u);

        // then
        assertThat(revoked).isEqualTo(3);
        assertThat(sessionService.sessionExists(s1)).isFalse();
        assertThat(sessionService.sessionExists(s2)).isFalse();
        assertThat(sessionService.sessionExists(s3)).isFalse();
    }
}
