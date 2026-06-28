package site.krip.domain.chat.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 채팅 노드 레지스트리.
 *
 * <p>{@code redis_stream} 모드에서 활성 노드(`chat:nodes` ZSET, score=만료시각ms)를 추적.
 * 죽은 노드의 consumer group 청소 기준으로 쓰인다({@link ChatStreamConfig} 가 명단에 없는 group 을 제거).
 * 조회({@link #listActiveNodes})는 읽기 전용이고, 만료 노드 청소({@link #cleanupExpired})는 heartbeat
 * 주기 작업으로 분리한다.
 */
@Component
@RequiredArgsConstructor
public class NodeRegistry {

    private final StringRedisTemplate redis;
    private final ChatProperties props;

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
     * 자기 노드 만료시각 갱신 (heartbeat) — 명단에서 빠졌으면 재등록한다.
     *
     * <p>plain ZADD(없으면 추가, 있으면 갱신)라 ZSET 유실(FLUSHDB/eviction) 후에도 다음 tick 에 자가 복귀한다.
     * 종료 시 deregister 직후 racy 하게 부활할 수 있으나, 그 항목은 score 만료(NODE_TTL)로 청소되고
     * 죽은 노드엔 라이브 세션이 없어 무해하다.
     */
    public void heartbeatSelf() {
        redis.opsForZSet().add(ChatRedisKeys.NODES_ZSET_KEY, props.nodeId(), expiresMs());
    }

    /** 자기 노드 제거 (종료 시 1회). */
    public void deregisterSelf() {
        redis.opsForZSet().remove(ChatRedisKeys.NODES_ZSET_KEY, props.nodeId());
    }

    /**
     * 활성 노드 목록 — 핫패스(fan-out)용 읽기 전용. 만료(score &lt; now)는 score 범위로 걸러 제외만 하고
     * 삭제하지 않는다(쓰기 없음). 실제 삭제는 {@link #cleanupExpired} 가 주기적으로 수행. 빈 리스트면 publish skip.
     */
    public List<String> listActiveNodes() {
        Set<String> members = redis.opsForZSet()
                .rangeByScore(ChatRedisKeys.NODES_ZSET_KEY, nowMs(), Double.POSITIVE_INFINITY);
        return members != null ? new ArrayList<>(members) : Collections.emptyList();
    }

    /** 만료(score &le; now) 노드 청소 — heartbeat 주기로 호출(핫패스 분리). 멱등이라 전 노드가 호출해도 안전. */
    public void cleanupExpired() {
        redis.opsForZSet().removeRangeByScore(ChatRedisKeys.NODES_ZSET_KEY, Double.NEGATIVE_INFINITY, nowMs());
    }
}
