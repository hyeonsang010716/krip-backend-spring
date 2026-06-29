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
 * 채팅 노드 레지스트리 통합 테스트 — 실 Redis(`chat:nodes` ZSET) 기준. 등록/활성 노출, 만료 노드 제외,
 * listActiveNodes 읽기 전용, cleanupExpired 삭제, ZSET 유실 후 heartbeat 자가복구를 커버.
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
    @DisplayName("만료시각이 지난 노드는 listActiveNodes 결과에서 제외된다")
    void staleNodeExcludedFromList() {
        // 만료시각(score)을 과거로 둔 죽은 노드를 직접 주입.
        redis.opsForZSet().add(ChatRedisKeys.NODES_ZSET_KEY, "dead-node",
                System.currentTimeMillis() - 60_000);

        assertThat(nodeRegistry.listActiveNodes()).doesNotContain("dead-node");
    }

    @Test
    @DisplayName("listActiveNodes 는 읽기 전용 — 만료 노드를 결과에서 제외만 하고 ZSET 에서 삭제하지 않는다")
    void listActiveNodesDoesNotDelete() {
        redis.opsForZSet().add(ChatRedisKeys.NODES_ZSET_KEY, "dead-node",
                System.currentTimeMillis() - 60_000);

        nodeRegistry.listActiveNodes();

        // 핫패스 조회는 쓰기를 하지 않으므로 ZSET 에는 여전히 남아 있어야 한다(청소는 주기 작업 몫).
        assertThat(redis.opsForZSet().score(ChatRedisKeys.NODES_ZSET_KEY, "dead-node")).isNotNull();
    }

    @Test
    @DisplayName("cleanupExpired 는 만료 노드만 ZSET 에서 삭제하고 활성 노드는 보존한다")
    void cleanupExpiredRemovesOnlyDead() {
        redis.opsForZSet().add(ChatRedisKeys.NODES_ZSET_KEY, "dead-node",
                System.currentTimeMillis() - 60_000);
        nodeRegistry.registerSelf(); // 미래 score 활성 노드

        nodeRegistry.cleanupExpired();

        assertThat(redis.opsForZSet().score(ChatRedisKeys.NODES_ZSET_KEY, "dead-node")).isNull();
        assertThat(redis.opsForZSet().score(ChatRedisKeys.NODES_ZSET_KEY, chatProperties.nodeId())).isNotNull();
    }

    @Test
    @DisplayName("ZSET 유실 후 heartbeat 가 명단에서 빠진 자기 노드를 재등록한다(자가복구)")
    void heartbeatReRegistersSelfAfterZsetLoss() {
        nodeRegistry.registerSelf();
        assertThat(nodeRegistry.listActiveNodes()).contains(chatProperties.nodeId());

        // FLUSHDB/eviction 시뮬레이션 — 명단 통째 삭제
        redis.delete(ChatRedisKeys.NODES_ZSET_KEY);
        assertThat(nodeRegistry.listActiveNodes()).doesNotContain(chatProperties.nodeId());

        nodeRegistry.heartbeatSelf();

        assertThat(nodeRegistry.listActiveNodes()).contains(chatProperties.nodeId());
    }
}
