package site.krip.domain.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.service.FanoutService;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.support.IntegrationTestSupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * node_channel fan-out — 활성 노드 명단이 비어도 로컬 세션엔 전달되는지 검증(회귀).
 *
 * <p>기존 publishBroadcast 는 listActiveNodes()가 비면 즉시 return 해 자기 로컬 세션에도 전달하지
 * 못했다. 자기 노드를 항상 broadcast 대상에 포함하도록 고친 뒤, 명단을 비운 상태에서 fanOutToRoom 이
 * 로컬 구독 세션에 도달하는지(앱의 node 채널 구독 → dispatch → localDeliver)를 확인한다.
 */
@TestPropertySource(properties = "krip.chat.fanout-mode=node_channel")
class FanoutSelfDeliveryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private FanoutService fanout;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("활성 노드 명단이 비어도 로컬 구독 세션은 fan-out 을 받는다")
    void deliversToLocalSessionWhenNodeListEmpty() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(FanoutService.ATTR_SESSION_ID, "sess-self");
        attrs.put(FanoutService.ATTR_USER_ID, "user-self");
        attrs.put(FanoutService.ATTR_ROOMS, ConcurrentHashMap.<String>newKeySet());
        when(ws.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            delivered.countDown();
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));

        String roomId = "room-self-delivery";
        fanout.registerSession(ws);
        fanout.registerWsToRoom(ws, roomId);
        try {
            // 활성 노드 명단을 비운다 — 그래도 자기 노드로는 publish 돼야 한다.
            redis.delete(ChatRedisKeys.NODES_ZSET_KEY);

            fanout.fanOutToRoom(roomId, Map.of("type", "test_event"));

            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            fanout.unregisterWs(ws);
        }
    }
}
