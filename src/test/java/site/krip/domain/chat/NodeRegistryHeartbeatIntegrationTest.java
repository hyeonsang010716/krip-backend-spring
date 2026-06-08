package site.krip.domain.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.domain.chat.worker.NodeRegistry;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 노드 레지스트리 하트비트 — chat:nodes ZSET 유실 후 자기 노드 자가 재등록 검증(회귀).
 *
 * <p>기존 ZADD XX(있으면만 갱신)는 FLUSHDB/eviction 으로 명단이 비면 재등록을 못 해 노드가 영구히
 * fan-out 대상에서 빠졌다. plain ZADD 로 다음 하트비트에 복귀하는지 확인한다.
 */
class NodeRegistryHeartbeatIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private NodeRegistry nodeRegistry;

    @Autowired
    private ChatProperties chatProps;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("ZSET 유실 후 heartbeat 가 자기 노드를 재등록한다")
    void heartbeatReRegistersSelfAfterZsetLoss() {
        String nodeId = chatProps.nodeId();
        redis.delete(ChatRedisKeys.NODES_ZSET_KEY);

        nodeRegistry.registerSelf();
        assertThat(nodeRegistry.listActiveNodes()).contains(nodeId);

        // FLUSHDB/eviction 시뮬레이션 — 명단 통째 삭제
        redis.delete(ChatRedisKeys.NODES_ZSET_KEY);
        assertThat(nodeRegistry.listActiveNodes()).doesNotContain(nodeId);

        // 하트비트가 빠진 자기 노드를 다시 등록해야 한다
        nodeRegistry.heartbeatSelf();
        assertThat(nodeRegistry.listActiveNodes()).contains(nodeId);

        redis.delete(ChatRedisKeys.NODES_ZSET_KEY);
    }
}
