package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import site.krip.domain.chat.worker.NodeRegistry;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;
import site.krip.global.support.IdGenerator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WS 세션의 Redis 상태 관리.
 *
 * <p>키: {@code sess:{sid}}(HASH, TTL 90s) / {@code sessions:{uid}}(ZSET, score=만료시각ms) /
 * {@code ws_route:{sid}}(STRING node_id, TTL 90s). ZSET score 가 만료시각이라
 * ZREMRANGEBYSCORE 한 번으로 죽은 세션 청소 — 자가 치유. 유저당 {@code MAX_SESSIONS_PER_USER} 초과 시 oldest revoke.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final FanoutService fanout;
    private final StringRedisTemplate redis;
    private final ChatProperties props;
    private final RedisScript<Long> setSessionScript;
    private final RedisScript<Long> updateTokenJtiScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> enforceSessionLimitScript;

    public SessionService(FanoutService fanout, StringRedisTemplate redis, ChatProperties props,
                          @Qualifier("setSessionScript") RedisScript<Long> setSessionScript,
                          @Qualifier("updateTokenJtiScript") RedisScript<Long> updateTokenJtiScript,
                          @Qualifier("enforceSessionLimitScript") @SuppressWarnings("rawtypes")
                          RedisScript<List> enforceSessionLimitScript) {
        this.fanout = fanout;
        this.redis = redis;
        this.props = props;
        this.setSessionScript = setSessionScript;
        this.updateTokenJtiScript = updateTokenJtiScript;
        this.enforceSessionLimitScript = enforceSessionLimitScript;
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static long expiresMs() {
        return nowMs() + ChatRedisKeys.SESSION_TTL * 1000;
    }

    private static final Duration TTL = Duration.ofSeconds(ChatRedisKeys.SESSION_TTL);

    /** WS 연결 시 — 새 session_id 발급 후 Redis 3키 세팅 + 한도 체크. */
    public String createSession(String userId, String tokenJti) {
        String sessionId = IdGenerator.sessionId();
        long nowMs = nowMs();

        // 3키(HASH/ZSET/STRING)를 단일 Lua 로 원자 세팅 — HSET+EXPIRE 분리 시의 좀비 해시 누수 차단.
        redis.execute(setSessionScript,
                List.of(ChatRedisKeys.sess(sessionId), ChatRedisKeys.sessions(userId), ChatRedisKeys.wsRoute(sessionId)),
                sessionId, userId, props.nodeId(), tokenJti, String.valueOf(nowMs),
                String.valueOf(ChatRedisKeys.SESSION_TTL), String.valueOf(nowMs + ChatRedisKeys.SESSION_TTL * 1000));

        enforceSessionLimit(userId);
        return sessionId;
    }

    /** 단일 세션 TTL 연장(세 키). sessions ZSET 은 이미 있는 멤버만 갱신(죽은 세션 부활 방지). batch 형은 {@link #heartbeatBatch}. */
    public void heartbeat(String sessionId, String userId) {
        redis.expire(ChatRedisKeys.sess(sessionId), TTL);
        redis.expire(ChatRedisKeys.wsRoute(sessionId), TTL);
        // ZADD ... XX — 이미 있는 멤버의 score 만 원자적으로 갱신. ZSCORE→ZADD 의 TOCTOU 레이스(동시 ZREM 후
        // 부활)를 차단한다. 고수준 opsForZSet().add 에 XX 가 없어 저수준 호출(NodeRegistry.heartbeatSelf 와 동일).
        byte[] key = ChatRedisKeys.sessions(userId).getBytes(StandardCharsets.UTF_8);
        byte[] member = sessionId.getBytes(StandardCharsets.UTF_8);
        double score = expiresMs();
        redis.execute((RedisCallback<Boolean>) connection ->
                connection.zSetCommands().zAdd(key, score, member, ZAddArgs.ifExists()));
    }

    /**
     * sweep 주기마다 노드의 로컬 세션 전체 TTL 을 파이프라인 1회로 연장.
     * EXPIRE(없는 키 no-op)·ZADD XX(없는 멤버 no-op) 모두 종료된 세션을 부활시키지 않는다.
     */
    public void heartbeatBatch(Map<String, String> sessionToUser) {
        if (sessionToUser.isEmpty()) {
            return;
        }
        double score = expiresMs();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            sessionToUser.forEach((sessionId, userId) -> {
                connection.keyCommands().expire(
                        ChatRedisKeys.sess(sessionId).getBytes(StandardCharsets.UTF_8), ChatRedisKeys.SESSION_TTL);
                connection.keyCommands().expire(
                        ChatRedisKeys.wsRoute(sessionId).getBytes(StandardCharsets.UTF_8), ChatRedisKeys.SESSION_TTL);
                connection.zSetCommands().zAdd(
                        ChatRedisKeys.sessions(userId).getBytes(StandardCharsets.UTF_8), score,
                        sessionId.getBytes(StandardCharsets.UTF_8), ZAddArgs.ifExists());
            });
            return null;
        });
    }

    /** JWT refresh 시 token_jti 만 갱신. session_id 유지. 만료된 세션은 부활시키지 않는다(키 존재 시에만). */
    public void updateTokenJti(String sessionId, String newTokenJti) {
        redis.execute(updateTokenJtiScript, List.of(ChatRedisKeys.sess(sessionId)), newTokenJti);
    }

    /** 매 op 진입 시 호출 — false 면 revoke 된 상태. */
    public boolean sessionExists(String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(ChatRedisKeys.sess(sessionId)));
    }

    public String getUserId(String sessionId) {
        Object v = redis.opsForHash().get(ChatRedisKeys.sess(sessionId), "user_id");
        return v != null ? v.toString() : null;
    }

    /** WS 종료/명시 로그아웃 시 Redis 상태 정리. */
    public void terminateSession(String sessionId, String userId) {
        redis.delete(ChatRedisKeys.sess(sessionId));
        redis.delete(ChatRedisKeys.wsRoute(sessionId));
        redis.opsForZSet().remove(ChatRedisKeys.sessions(userId), sessionId);
    }

    /** 유저의 모든 활성 세션 강제 종료 (회원 탈퇴 등). 오프라인이면 0. */
    public int revokeAllSessions(String userId) {
        Set<String> sessionIds = redis.opsForZSet().range(ChatRedisKeys.sessions(userId), 0, -1);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        for (String sid : sessionIds) {
            fanout.fanOutToSession(sid, Map.of("type", "session_revoked", "session_id", sid));
        }
        for (String sid : sessionIds) {
            redis.delete(ChatRedisKeys.sess(sid));
            redis.delete(ChatRedisKeys.wsRoute(sid));
        }
        redis.delete(ChatRedisKeys.sessions(userId));
        log.info("전체 세션 revoke: user_id={}, revoked_count={}", userId, sessionIds.size());
        return sessionIds.size();
    }

    /**
     * 만료 청소 + 초과분 oldest evict 를 단일 Lua 로 원자 실행 — 동시 접속이 stale count 로 살아있는
     * 세션을 잘못 evict 하는 레이스를 막는다. ZSET·키 삭제는 스크립트가, revoke 알림은 evict 목록을 받아
     * 여기서(best-effort) 보낸다. 알림 시점엔 Redis 상에선 이미 끊긴 상태라 안전.
     */
    @SuppressWarnings("unchecked")
    private void enforceSessionLimit(String userId) {
        List<String> evicted = (List<String>) redis.execute(
                enforceSessionLimitScript,
                List.of(ChatRedisKeys.sessions(userId)),
                String.valueOf(nowMs()), String.valueOf(ChatRedisKeys.MAX_SESSIONS_PER_USER));
        if (evicted == null || evicted.isEmpty()) {
            return;
        }
        for (String sid : evicted) {
            fanout.fanOutToSession(sid, Map.of("type", "session_revoked", "session_id", sid));
            log.info("세션 한도 초과로 revoke: user_id={}, revoked_session_id={}", userId, sid);
        }
    }
}
