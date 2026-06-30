package site.krip.domain.chat.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.ChatTestSupport;
import site.krip.domain.chat.service.FanoutService;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Stream 컨슈머 자가복구 검증 — NOGROUP(런타임 group 파괴)에도 구독이 죽지 않고 ensureGroup() 재생성 후 소비 재개.
 */
@TestPropertySource(properties = {
        "krip.chat.fanout-mode=redis_stream",
        "krip.chat.node-id=test-node-1" // redis_stream 부팅 가드 충족 — 기본 'node-local' 은 fail-fast 거부됨
})
@DisplayName("스트림 컨슈머 복원력 — group 파괴 후 재생성·전달 재개")
class ChatStreamConsumerResilienceIntegrationTest extends ChatTestSupport {

    @Autowired
    private FanoutService fanout;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ChatProperties props;

    @Autowired
    private ChatStreamConfig streamConfig;

    @Test
    @DisplayName("group 이 런타임에 파괴돼도 구독은 살아남고 재생성 후 전달이 재개된다")
    void recoversAfterConsumerGroupDestroyed() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        WebSocketSession ws = mockWsSession("sess-resilience", "user-resilience");
        latchOnSend(ws, delivered);

        String roomId = "room-resilience";
        fanout.registerSession(ws);
        fanout.registerWsToRoom(ws, roomId);
        try {
            // 런타임에 자기 group 을 파괴 → 컨슈머 폴링이 NOGROUP 을 맞게 한다.
            redis.opsForStream().destroyGroup(ChatRedisKeys.CHAT_STREAM_KEY, props.nodeId());
            // group 이 실제로 사라졌는지 확인(폴링이 NOGROUP 을 겪을 조건) — cancelOnError(false)면 구독은 죽지 않아야 한다.
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(redis.opsForStream().groups(ChatRedisKeys.CHAT_STREAM_KEY).stream()
                            .noneMatch(g -> g.groupName().equals(props.nodeId()))).isTrue());

            // 자가복구: group 재생성($) → 이후 발행분부터 다시 읽힌다.
            streamConfig.ensureGroup();
            fanout.fanOutToRoom(roomId, Map.of("type", "read", "marker", "after-recovery"));

            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            fanout.unregisterWs(ws);
        }
    }
}
