package site.krip.domain.chat.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 노드 레지스트리 통합 테스트 — 실 Redis(`chat:nodes` ZSET) 기준.
 *
 * <p>커버: 등록→활성 목록 노출 / 만료(과거 score) 노드 청소 / deregister 후 heartbeat 가 ZADD XX 로 부활 안 됨.
 */
class NodeRegistryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private NodeRegistry nodeRegistry;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ChatProperties chatProperties;

    @BeforeEach
    void clean() {
        redis.delete(ChatRedisKeys.NODES_ZSET_KEY);
    }

    @Test
    @DisplayName("registerSelf → 활성 노드 목록에 자기 노드 노출")
    void registerSelfAppearsActive() {
        nodeRegistry.registerSelf();

        assertThat(nodeRegistry.listActiveNodes()).contains(chatProperties.nodeId());
    }

    @Test
    @DisplayName("만료시각이 지난 노드는 listActiveNodes 에서 청소된다")
    void staleNodeExpired() {
        // 만료시각(score)을 과거로 둔 죽은 노드를 직접 주입.
        redis.opsForZSet().add(ChatRedisKeys.NODES_ZSET_KEY, "dead-node",
                System.currentTimeMillis() - 60_000);

        assertThat(nodeRegistry.listActiveNodes()).doesNotContain("dead-node");
    }

    @Test
    @DisplayName("deregister 후 heartbeat(ZADD XX)는 제거된 노드를 부활시키지 않는다")
    void heartbeatAfterDeregisterNoResurrect() {
        nodeRegistry.registerSelf();
        nodeRegistry.deregisterSelf();

        nodeRegistry.heartbeatSelf();

        assertThat(nodeRegistry.listActiveNodes()).doesNotContain(chatProperties.nodeId());
    }
}
