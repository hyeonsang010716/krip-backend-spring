package site.krip.domain.chat.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.service.FanoutService;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.support.IntegrationTestSupport;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS liveness sweep — ping/pong 기반으로 도달 불가(half-open) 세션을 닫고 TTL 연장에서 제외하는지 검증(회귀).
 *
 * <p>sweep 가 도달성과 무관하게 TTL 을 무조건 연장하던 기존 동작에서는 좀비 세션이 TTL 자가청소를 회피해
 * 사용자별 세션 한도를 소진했다. pong 으로 생존이 확인된 세션만 연장하고, 타임아웃 세션은 닫는지 본다.
 */
class ChatWebSocketHandlerLivenessTest extends IntegrationTestSupport {

    @Autowired
    private ChatWebSocketHandler handler;

    @Autowired
    private StringRedisTemplate redis;

    private WebSocketSession mockSession(String sessionId, String userId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(FanoutService.ATTR_SESSION_ID, sessionId);
        attrs.put(FanoutService.ATTR_USER_ID, userId);
        when(ws.getAttributes()).thenReturn(attrs);
        return ws;
    }

    @Test
    @DisplayName("pong 최근 세션 — ping 전송 + Redis TTL 연장, 닫지 않음")
    void freshSessionPingedAndTtlExtended() throws Exception {
        String sid = "WS-live-fresh";
        String uid = "user-live-fresh";
        redis.opsForValue().set(ChatRedisKeys.sess(sid), "x", Duration.ofSeconds(10));
        WebSocketSession ws = mockSession(sid, uid);
        handler.liveSessions.put(sid, ws);
        handler.lastPongAt.put(sid, System.currentTimeMillis()); // 방금 pong

        try {
            handler.sweepLiveness();

            verify(ws).sendMessage(isA(PingMessage.class));
            verify(ws, never()).close(any(CloseStatus.class));
            Long ttl = redis.getExpire(ChatRedisKeys.sess(sid));
            assertThat(ttl).isGreaterThan(10L); // 90s 로 연장됨
        } finally {
            handler.liveSessions.remove(sid);
            handler.lastPongAt.remove(sid);
            redis.delete(ChatRedisKeys.sess(sid));
        }
    }

    @Test
    @DisplayName("pong 끊긴 세션 — 닫고 Redis TTL 연장하지 않음")
    void staleSessionClosedAndTtlNotExtended() throws Exception {
        String sid = "WS-live-stale";
        String uid = "user-live-stale";
        redis.opsForValue().set(ChatRedisKeys.sess(sid), "x", Duration.ofSeconds(10));
        WebSocketSession ws = mockSession(sid, uid);
        handler.liveSessions.put(sid, ws);
        handler.lastPongAt.put(sid, System.currentTimeMillis() - 200_000); // 200s 전 — 타임아웃 초과

        try {
            handler.sweepLiveness();

            verify(ws).close(any(CloseStatus.class));
            Long ttl = redis.getExpire(ChatRedisKeys.sess(sid));
            assertThat(ttl).isLessThanOrEqualTo(10L); // 연장 안 됨
        } finally {
            handler.liveSessions.remove(sid);
            handler.lastPongAt.remove(sid);
            redis.delete(ChatRedisKeys.sess(sid));
        }
    }
}
