package site.krip.domain.chat.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.scheduling.annotation.Scheduled;
import site.krip.domain.chat.service.FanoutService;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.util.Map;

/**
 * 다중 노드 fan-out 인프라.
 *
 * <p>{@code fanout-mode=node_channel} 일 때만 활성화: 자기 노드를 ZSET 에 등록/heartbeat 하고,
 * {@code node:{NODE_ID}} 채널을 SUBSCRIBE 해 envelope 을 {@link FanoutService#dispatchEnvelope} 로 라우팅한다.
 * 기본 {@code in_process} 에서는 이 설정 전체가 비활성(빈 미생성).
 */
@Configuration
@ConditionalOnProperty(name = "krip.chat.fanout-mode", havingValue = "node_channel")
public class ChatNodeChannelConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatNodeChannelConfig.class);

    private final NodeRegistry nodeRegistry;
    private final FanoutService fanout;
    private final ChatProperties props;
    private final ObjectMapper mapper;

    public ChatNodeChannelConfig(NodeRegistry nodeRegistry, FanoutService fanout,
                                 ChatProperties props, ObjectMapper mapper) {
        this.nodeRegistry = nodeRegistry;
        this.fanout = fanout;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * 자기 노드 ZSET 등록 — 반드시 listener container 의 채널 SUBSCRIBE 가 끝난 뒤(=컨텍스트 ready).
     *
     * <p>{@link RedisMessageListenerContainer} 는 SmartLifecycle 이라 컨텍스트 start 단계에 구독을 시작하고,
     * {@link ApplicationReadyEvent} 는 모든 SmartLifecycle 시작 이후 발행된다. 따라서 이 시점엔 구독이 활성화돼
     * 있어, 다른 노드 publisher 가 우리를 ZSET 에서 보고 publish 해도 메시지가 유실되지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerSelf() {
        nodeRegistry.registerSelf();
        log.info("node registry 등록(구독 완료 후): node_id={}", props.nodeId());
    }

    /**
     * 종료 시 자기 노드 제거 — ContextClosedEvent 는 빈 destroy/Lifecycle stop 이전에 발행되므로,
     * 다른 노드 publisher 가 우리를 먼저 제외한 뒤 in-flight envelope 까지 처리하고 구독이 닫힌다.
     * deregister 실패해도 NODE_TTL 후 자연 청소되므로 fail-open.
     */
    @EventListener(ContextClosedEvent.class)
    public void deregisterSelf() {
        try {
            nodeRegistry.deregisterSelf();
        } catch (Exception e) {
            log.warn("node deregister 실패 (TTL 만료로 fallback)", e);
        }
    }

    /** NODE_TTL/3 마다 자기 노드 만료시각 갱신 + 만료 노드 청소(fan-out 핫패스에서 분리). */
    @Scheduled(fixedDelayString = "${krip.chat.node-heartbeat-ms:30000}")
    public void heartbeat() {
        try {
            nodeRegistry.heartbeatSelf();
        } catch (Exception e) {
            log.warn("node heartbeat 실패 (계속 진행)", e);
        }
        try {
            nodeRegistry.cleanupExpired();
        } catch (Exception e) {
            log.warn("만료 노드 청소 실패 (다음 tick 재시도)", e);
        }
    }

    /** envelope 수신 핸들러 — JSON → Map → 로컬 디스패치. */
    @SuppressWarnings("unchecked")
    public void onEnvelope(String json) {
        try {
            Map<String, Object> envelope = mapper.readValue(json, Map.class);
            fanout.dispatchEnvelope(envelope);
        } catch (Exception e) {
            log.warn("envelope 파싱/처리 실패 (drop)", e);
        }
    }

    @Bean
    public RedisMessageListenerContainer chatNodeListenerContainer(RedisConnectionFactory cf) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        MessageListenerAdapter adapter = new MessageListenerAdapter(this, "onEnvelope");
        adapter.afterPropertiesSet();
        container.addMessageListener(adapter, new ChannelTopic(ChatRedisKeys.nodeChannel(props.nodeId())));
        log.info("fan-out 디스패처 시작: channel={}", ChatRedisKeys.nodeChannel(props.nodeId()));
        return container;
    }
}
