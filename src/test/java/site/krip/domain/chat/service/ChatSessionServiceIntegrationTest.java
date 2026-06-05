package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.support.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 세션 서비스 통합 테스트 — 실 Redis 로 세션 생성/존재/종료, 세션 한도 eviction, 전체 revoke 검증.
 *
 * <p>SessionService 는 순수 Redis 연산이라 DB 유저 없이 임의 user_id 로 검증한다.
 */
class ChatSessionServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private SessionService sessionService;

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
        String u = randomUser();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            ids.add(sessionService.createSession(u, "jti-" + i));
        }

        long alive = ids.stream().filter(sessionService::sessionExists).count();
        assertThat(alive).isEqualTo(10);
        // 가장 최근 세션은 살아있다.
        assertThat(sessionService.sessionExists(ids.get(ids.size() - 1))).isTrue();
    }

    @Test
    @DisplayName("revokeAllSessions: 유저의 모든 세션 제거 후 개수 반환")
    void revokeAllSessions() {
        String u = randomUser();
        String s1 = sessionService.createSession(u, "jti-1");
        String s2 = sessionService.createSession(u, "jti-2");
        String s3 = sessionService.createSession(u, "jti-3");

        int revoked = sessionService.revokeAllSessions(u);

        assertThat(revoked).isEqualTo(3);
        assertThat(sessionService.sessionExists(s1)).isFalse();
        assertThat(sessionService.sessionExists(s2)).isFalse();
        assertThat(sessionService.sessionExists(s3)).isFalse();
    }

    @Test
    @DisplayName("heartbeat 후에도 세션이 유지된다")
    void heartbeatKeepsSessionAlive() {
        String u = randomUser();
        String sid = sessionService.createSession(u, "jti-1");

        sessionService.heartbeat(sid, u);

        assertThat(sessionService.sessionExists(sid)).isTrue();
    }
}
