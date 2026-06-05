package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 채팅 도메인 설정.
 *
 * <p>{@code fanoutMode}: {@code in_process}(단일 프로세스 직배송) 또는
 * {@code node_channel}(다중 노드 Redis Pub/Sub). {@code dedupeRedisDatabase}: dedupe 키 격리용 Redis DB(hot 과 분리).
 */
@ConfigurationProperties(prefix = "krip.chat")
public record ChatProperties(
        String fanoutMode,
        String nodeId,
        int dedupeRedisDatabase
) {
    public boolean isNodeChannel() {
        return "node_channel".equals(fanoutMode);
    }
}
