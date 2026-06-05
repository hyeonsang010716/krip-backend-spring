package site.krip.domain.chat.worker;

import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 채팅 노드 레지스트리.
 *
 * <p>{@code node_channel} 모드에서 활성 노드(`chat:nodes` ZSET, score=만료시각ms)를 추적.
 * publisher 가 fan-out 시 조회해 모든 노드 채널로 broadcast. 크래시 노드는 만료시각 청소로 자연 제거.
 */
@Component
public class NodeRegistry {

    private final StringRedisTemplate redis;
    private final ChatProperties props;

    public NodeRegistry(StringRedisTemplate redis, ChatProperties props) {
        this.redis = redis;
        this.props = props;
    }

    private long nowMs() {
        return System.currentTimeMillis();
    }

    private long expiresMs() {
        return nowMs() + ChatRedisKeys.NODE_TTL * 1000;
    }

    /** 자기 노드 등록 (시작 시 1회). */
    public void registerSelf() {
        redis.opsForZSet().add(ChatRedisKeys.NODES_ZSET_KEY, props.nodeId(), expiresMs());
    }

    /**
     * 자기 노드 만료시각 갱신 (heartbeat).
     *
     * <p>{@code ZADD ... XX} — 멤버가 이미 존재할 때만 갱신. deregister 후 racy 하게 발화한 heartbeat 가
     * 제거된 노드를 다시 살리는 것을 원자적으로 방지한다.
     * {@code StringRedisTemplate} 의 UTF-8 직렬화와 동일한 바이트라 {@link #registerSelf()} 와 같은 멤버를 가리킨다.
     */
    public void heartbeatSelf() {
        byte[] key = ChatRedisKeys.NODES_ZSET_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] member = props.nodeId().getBytes(StandardCharsets.UTF_8);
        double score = expiresMs();
        redis.execute((RedisCallback<Boolean>) connection ->
                connection.zSetCommands().zAdd(key, score, member, ZAddArgs.ifExists()));
    }

    /** 자기 노드 제거 (종료 시 1회). */
    public void deregisterSelf() {
        redis.opsForZSet().remove(ChatRedisKeys.NODES_ZSET_KEY, props.nodeId());
    }

    /** 활성 노드 목록 — 만료 청소 후 조회. 빈 리스트면 publish skip. */
    public List<String> listActiveNodes() {
        redis.opsForZSet().removeRangeByScore(ChatRedisKeys.NODES_ZSET_KEY, Double.NEGATIVE_INFINITY, nowMs());
        Set<String> members = redis.opsForZSet().range(ChatRedisKeys.NODES_ZSET_KEY, 0, -1);
        return members != null ? new ArrayList<>(members) : Collections.emptyList();
    }
}
