package site.krip.domain.chat.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.ChatTestSupport;
import site.krip.global.chat.ChatRedisKeys;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WS liveness sweep 회귀 — pong 으로 생존 확인된 세션만 ping/TTL 연장, half-open(타임아웃) 세션은 닫는다.
 * 과거엔 무조건 TTL 을 연장해 좀비 세션이 자가청소를 회피하고 세션 한도를 소진했다.
 */
@DisplayName("WS liveness — pong 기반 ping·TTL 연장·끊긴 세션 종료")
class ChatWebSocketHandlerLivenessTest extends ChatTestSupport {

    @Autowired
    private ChatWebSocketHandler handler;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("pong 최근 세션 — ping 전송 + Redis TTL 연장, 닫지 않음")
    void freshSessionPingedAndTtlExtended() throws Exception {
        String sid = "WS-live-fresh";
        String uid = "user-live-fresh";
        redis.opsForValue().set(ChatRedisKeys.sess(sid), "x", Duration.ofSeconds(10));
        WebSocketSession ws = mockWsSession(sid, uid);
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
        WebSocketSession ws = mockWsSession(sid, uid);
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
