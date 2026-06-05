package site.krip.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.worker.NodeRegistry;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code node_channel} 모드 fan-out 단위 테스트 — Redis Pub/Sub publish 라우팅과 디스패처 로컬 전달.
 *
 * <p>실 컨텍스트/타이밍 의존 없이 협력자를 mock 해 검증: 활성 노드별 publish / 활성 노드 없을 때 skip /
 * 수신 envelope(op=room) 의 로컬 구독자 전달.
 */
class FanoutServiceNodeChannelTest {

    private StringRedisTemplate redis;
    private NodeRegistry nodeRegistry;
    private FanoutService fanout;

    @BeforeEach
    void setUp() {
        ChatProperties props = new ChatProperties("node_channel", "test-node", 1);
        redis = mock(StringRedisTemplate.class);
        nodeRegistry = mock(NodeRegistry.class);
        fanout = new FanoutService(props, redis, nodeRegistry, new ObjectMapper());
    }

    @Test
    @DisplayName("node_channel — 활성 노드마다 채널로 publish")
    void publishesToEachActiveNode() {
        when(nodeRegistry.listActiveNodes()).thenReturn(List.of("nodeA", "nodeB"));

        fanout.fanOutToRoom("room-1", Map.of("type", "message.new"));

        verify(redis).convertAndSend(eq(ChatRedisKeys.nodeChannel("nodeA")), anyString());
        verify(redis).convertAndSend(eq(ChatRedisKeys.nodeChannel("nodeB")), anyString());
    }

    @Test
    @DisplayName("node_channel — 활성 노드가 없으면 publish skip")
    void skipsWhenNoActiveNodes() {
        when(nodeRegistry.listActiveNodes()).thenReturn(List.of());

        fanout.fanOutToRoom("room-1", Map.of("type", "message.new"));

        verify(redis, never()).convertAndSend(anyString(), any());
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
