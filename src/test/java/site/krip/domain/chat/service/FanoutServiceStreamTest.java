package site.krip.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code redis_stream} 모드 fan-out 단위 테스트 — 공유 Stream XADD 와 디스패처 로컬 전달.
 *
 * <p>실 컨텍스트 없이 협력자를 mock 해 검증: fanOutToRoom 이 단일 Stream 에 XADD / 수신 envelope(op=room) 의
 * 로컬 구독자 전달.
 */
class FanoutServiceStreamTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
    private FanoutService fanout;

    @BeforeEach
    void setUp() {
        ChatProperties props = new ChatProperties("redis_stream", "test-node", 1, 60_000, 1 << 20, 1000);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        // 동기 실행기(Runnable::run) — 송신을 인라인 유지해 단위 테스트가 결정적이게 한다.
        fanout = new FanoutService(props, redis, new ObjectMapper(), Runnable::run);
    }

    @Test
    @DisplayName("redis_stream — fanOutToRoom 은 공유 Stream 에 XADD")
    void publishesToSharedStream() {
        fanout.fanOutToRoom("room-1", Map.of("type", "message.new"));

        verify(streamOps).add(eq(ChatRedisKeys.CHAT_STREAM_KEY), anyMap());
    }

    @Test
    @DisplayName("dispatchEnvelope(op=room) — 로컬 구독 세션에 전달")
    void dispatchRoomDeliversToLocalSubscriber() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(FanoutService.ATTR_SESSION_ID, "sess-1");
        attrs.put(FanoutService.ATTR_USER_ID, "user-1");
        attrs.put(FanoutService.ATTR_ROOMS, new HashSet<String>());
        when(ws.getAttributes()).thenReturn(attrs);
        when(ws.isOpen()).thenReturn(true);

        fanout.registerSession(ws);
        fanout.registerWsToRoom(ws, "room-9");

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("op", "room");
        envelope.put("room_id", "room-9");
        envelope.put("payload", Map.of("type", "message.new"));
        fanout.dispatchEnvelope(envelope);

        verify(ws).sendMessage(any(TextMessage.class));
    }
}
