package site.krip.domain.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.service.FanoutService;
import site.krip.global.chat.ChatRedisKeys;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** redis_stream fan-out — 활성 노드 ZSET 이 비어도 Stream 소비 경로로 자기 노드 로컬 세션엔 전달된다. */
@TestPropertySource(properties = {
        "krip.chat.fanout-mode=redis_stream",
        "krip.chat.node-id=test-node-1" // redis_stream 부팅 가드 충족 — 기본 'node-local' 은 fail-fast 거부됨
})
class FanoutSelfDeliveryIntegrationTest extends ChatTestSupport {

    @Autowired
    private FanoutService fanout;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("활성 노드 명단이 비어도 로컬 구독 세션은 fan-out 을 받는다")
    void deliversToLocalSessionWhenNodeListEmpty() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        WebSocketSession ws = mockWsSession("sess-self", "user-self");
        latchOnSend(ws, delivered);

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
