package site.krip.domain.chat.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.scheduling.annotation.Scheduled;
import site.krip.domain.chat.service.FanoutService;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 다중 노드 fan-out 인프라 (Redis Stream).
 *
 * <p>{@code fanout-mode=redis_stream} 일 때만 활성화. 공유 Stream {@code chat:stream} 을 노드 ID 이름의
 * consumer group 으로 읽어 {@link FanoutService#dispatchEnvelope} 로 라우팅한다. NOACK(best-effort) 전송 —
 * 노드 다운 중 도착분은 group 커서가 Redis 에 남아 재시작 시 이어 읽지만, 읽은 뒤 dispatch 전 크래시한
 * in-flight 배치는 유실된다(at-most-once). 실시간 전용이며 영속/복구는 Mongo + REST 히스토리가 담당.
 *
 * <p>node-id 가 매 기동마다 바뀌면 죽은 group 이 쌓이므로, heartbeat 주기에 활성 노드(ZSET) 명단에 없는
 * group 을 청소하고 Stream 을 근사 트림한다.
 */
@Configuration
@ConditionalOnProperty(name = "krip.chat.fanout-mode", havingValue = "redis_stream")
@Slf4j
public class ChatStreamConfig {

    private static final int BATCH_SIZE = 50;
    // 연속 N틱(≈ N×heartbeat) 부재일 때만 group 제거 — 합류 직후 스냅샷 경합/짧은 heartbeat 누락 방어.
    private static final int REAP_ABSENCE_TICKS = 2;
    // node-id 미설정 시 application.yml 폴백 기본값 — 다중 노드에서 그대로 쓰면 그룹 공유 → fan-out 붕괴.
    private static final String DEFAULT_NODE_ID = "node-local";

    private final StringRedisTemplate redis;
    private final NodeRegistry nodeRegistry;
    private final FanoutService fanout;
    private final ChatProperties props;
    private final ObjectMapper mapper;

    // @Bean chatStreamContainer 에서 할당, start()/stop()(ApplicationReady/ContextClosed)에서 사용 — 프레임워크 초기화 순서 보장.
    @SuppressWarnings("NullAway.Init")
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    // group 별 연속 부재 카운트 — heartbeat 스케줄러 단일 스레드만 접근.
    private final Map<String, Integer> absenceStreak = new HashMap<>();

    public ChatStreamConfig(StringRedisTemplate redis, NodeRegistry nodeRegistry, FanoutService fanout,
                            ChatProperties props, ObjectMapper mapper) {
        requireStableNodeId(props);
        this.redis = redis;
        this.nodeRegistry = nodeRegistry;
        this.fanout = fanout;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * 다중 노드(redis_stream)에서 node-id 가 미설정 기본값('node-local')/공란이면 모든 노드가 같은 consumer
     * group 을 공유해 fan-out 이 조용히 붕괴한다(메시지가 노드 간 분배돼 한 노드에만 도달). 노드별 고유·안정
     * NODE_ID 를 fail-fast 로 강제한다 — yml 주석대로 재시작에도 안정적이어야 커서 연속성도 유지된다.
     */
    private static void requireStableNodeId(ChatProperties props) {
        if (!props.isMultiNode()) {
            return; // 이 빈은 redis_stream 에서만 생성되지만 의도를 명시.
        }
        String nodeId = props.nodeId();
        if (nodeId == null || nodeId.isBlank() || DEFAULT_NODE_ID.equals(nodeId)) {
            throw new IllegalStateException(
                    "fanout-mode=redis_stream 에서는 노드별 고유·안정 NODE_ID 가 필요합니다 (현재 '"
                            + nodeId + "'). NODE_ID 환경변수를 설정하세요.");
        }
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> chatStreamContainer(
            RedisConnectionFactory cf) {
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .batchSize(BATCH_SIZE)
                        .build();
        this.container = StreamMessageListenerContainer.create(cf, options);
        return container;
    }

    /** 컨텍스트 ready 후: group 보장 → 구독 → 컨테이너 시작 → 자기 노드 등록. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ensureGroup();
        // cancelOnError(false): 일시 오류(Redis 글리치/순간 NOGROUP)로 구독을 영구 취소하지 않고 다음 poll 재시도.
        // heartbeat 의 ensureGroup 이 사라진 group 을 재생성하므로, 둘이 합쳐 자가복구된다.
        StreamReadRequest<String> request = StreamReadRequest
                .builder(StreamOffset.create(ChatRedisKeys.CHAT_STREAM_KEY, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(props.nodeId(), props.nodeId()))
                .autoAcknowledge(true)
                .cancelOnError(t -> false)
                // 셧다운 중(container.stop() 이후) 닫힌 커넥션 경고는 정상 종료이므로 무시.
                .errorHandler(t -> { if (container.isRunning()) log.warn("stream 구독 오류 (계속 폴링): {}", t.toString()); })
                .build();
        container.register(request, this::onRecord);
        container.start();
        nodeRegistry.registerSelf();
        log.info("chat stream fan-out 시작: stream={}, group={}", ChatRedisKeys.CHAT_STREAM_KEY, props.nodeId());
    }

    /** 종료 시: 컨테이너 정지 + 자기 노드 제거. group 은 유지해 재시작 시 커서를 이어받는다. */
    @EventListener(ContextClosedEvent.class)
    public void stop() {
        try {
            container.stop();
        } catch (Exception e) {
            log.warn("stream container 정지 실패", e);
        }
        try {
            nodeRegistry.deregisterSelf();
        } catch (Exception e) {
            log.warn("node deregister 실패 (TTL fallback)", e);
        }
    }

    /** NODE_TTL/3 주기: 자기 liveness 갱신 + 만료 노드/죽은 group 청소 + Stream 트림. */
    @Scheduled(fixedDelayString = "${krip.chat.node-heartbeat-ms:30000}")
    public void heartbeat() {
        try {
            nodeRegistry.heartbeatSelf();
            nodeRegistry.cleanupExpired();
        } catch (Exception e) {
            log.warn("node liveness 갱신 실패 (다음 tick 재시도)", e);
        }
        try {
            ensureGroup(); // 장애/오청소로 사라졌으면 자기 group 재생성(멱등) — cancelOnError(false) 와 함께 자가복구.
        } catch (Exception e) {
            log.warn("group 재확인 실패 (다음 tick 재시도)", e);
        }
        try {
            reapDeadGroups();
        } catch (Exception e) {
            log.warn("죽은 consumer group 청소 실패 (다음 tick 재시도)", e);
        }
        try {
            redis.opsForStream().trim(ChatRedisKeys.CHAT_STREAM_KEY, ChatRedisKeys.CHAT_STREAM_MAXLEN, true);
        } catch (Exception e) {
            log.warn("stream 트림 실패 (다음 tick 재시도)", e);
        }
    }

    /** XGROUP CREATE MKSTREAM — 이미 있으면(BUSYGROUP) 저장된 커서에서 이어 읽는다. */
    void ensureGroup() {
        try {
            redis.opsForStream().createGroup(ChatRedisKeys.CHAT_STREAM_KEY, ReadOffset.from("$"), props.nodeId());
        } catch (Exception e) {
            // BUSYGROUP: 이미 존재 — 정상.
        }
    }

    /** 활성 노드(ZSET) 명단에 없는 group 제거 — node-id 가 바뀌어 남은 죽은 group 정리. */
    private void reapDeadGroups() {
        Set<String> active = new HashSet<>(nodeRegistry.listActiveNodes());
        active.add(props.nodeId());
        Set<String> existing = new HashSet<>();
        redis.opsForStream().groups(ChatRedisKeys.CHAT_STREAM_KEY).forEach(g -> existing.add(g.groupName()));
        for (String name : selectGroupsToReap(active, existing)) {
            redis.opsForStream().destroyGroup(ChatRedisKeys.CHAT_STREAM_KEY, name);
        }
    }

    /**
     * 제거 대상 group 선정 — 활성 명단에 없으면 부재 카운트를 올리고, {@link #REAP_ABSENCE_TICKS} 연속
     * 부재일 때만 대상에 넣는다. 살아있으면 카운트 리셋. 합류 직후 스냅샷 경합으로 한 틱 빠져도 다음 틱에
     * 명단에 들어오면 제거되지 않는다. ({@link #absenceStreak} 갱신하는 부수효과 — 단일 스레드 전제.)
     */
    Set<String> selectGroupsToReap(Set<String> active, Set<String> existingGroups) {
        Set<String> toReap = new HashSet<>();
        for (String name : existingGroups) {
            if (active.contains(name)) {
                absenceStreak.remove(name);
            } else if (absenceStreak.merge(name, 1, Integer::sum) >= REAP_ABSENCE_TICKS) {
                toReap.add(name);
                absenceStreak.remove(name);
            }
        }
        absenceStreak.keySet().retainAll(existingGroups); // 사라진 group 의 잔여 카운트 정리
        return toReap;
    }

    /** envelope 수신: data 필드 JSON → Map → 로컬 dispatch. */
    @SuppressWarnings("unchecked")
    private void onRecord(MapRecord<String, String, String> record) {
        try {
            String json = record.getValue().get("data");
            if (json == null) {
                return;
            }
            Map<String, Object> envelope = mapper.readValue(json, Map.class);
            fanout.dispatchEnvelope(envelope);
        } catch (Exception e) {
            log.warn("stream envelope 처리 실패 (drop): id={}", record.getId(), e);
        }
    }
}
