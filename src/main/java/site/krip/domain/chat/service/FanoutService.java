package site.krip.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 채팅 이벤트 fan-out.
 *
 * <p>{@code in_process}: 단일 프로세스 메모리 직배송. {@code redis_stream}: 다중 노드 공유 Stream
 * ({@code chat:stream}). 호출측은 모드 무관 — {@code fanOutTo*} / {@code (un)subscribeUserToRoom} 만 사용.
 * publish 는 단일 XADD 이고, 각 노드가 자기 consumer group 으로 전부 읽어 {@link #dispatchEnvelope} 로
 * 들어가는 통일 경로를 유지한다(자기 노드도 동일 경로로 수신).
 *
 * <p>WS 세션은 attribute 로 session_id / user_id / subscribed_rooms 를 보관한다(핸들러가 심음).
 */
@Service
public class FanoutService {

    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);

    public static final String ATTR_SESSION_ID = "session_id";
    public static final String ATTR_USER_ID = "user_id";
    public static final String ATTR_ROOMS = "subscribed_rooms";

    private final ChatProperties props;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Executor deliveryPool;
    private final int sendTimeLimitMs;
    private final int sendBufferBytes;
    private final int deliverySessionMaxQueued;

    private final Map<String, Set<WebSocketSession>> roomSubs = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> userSubs = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> localWsBySession = new ConcurrentHashMap<>();
    // 세션별 전송 자원 — 폴/송신 스레드에서 소켓 I/O 를 떼어내는 직렬 실행기 + 전송 상한 데코레이터.
    private final Map<String, SessionSerialExecutor> deliveryExecutors = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> deliveryDecorated = new ConcurrentHashMap<>();

    public FanoutService(ChatProperties props, StringRedisTemplate redis, ObjectMapper mapper,
                         @Qualifier("chatDeliveryExecutor") Executor deliveryPool) {
        this.props = props;
        this.redis = redis;
        this.mapper = mapper;
        this.deliveryPool = deliveryPool;
        this.sendTimeLimitMs = props.wsSendTimeLimitMs();
        this.sendBufferBytes = props.wsSendBufferBytes();
        this.deliverySessionMaxQueued = props.deliverySessionMaxQueued();
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Set<String> rooms(WebSocketSession ws) {
        return (Set<String>) ws.getAttributes().get(ATTR_ROOMS);
    }

    private static @Nullable String sessionId(WebSocketSession ws) {
        return (String) ws.getAttributes().get(ATTR_SESSION_ID);
    }

    private static @Nullable String userId(WebSocketSession ws) {
        return (String) ws.getAttributes().get(ATTR_USER_ID);
    }

    // ──────────────────── 등록/해제 (로컬 전용) ────────────────────

    /**
     * key→Set 에 ws 를 원자적으로 추가/제거. add 와 "비면 key 제거"를 둘 다 {@code compute}(키별 락) 안에서 하여
     * 상호배제 — 동시 register/unregister 시 빈-set 제거가 갓 추가된 live 세션의 key 를 날려버리는 race 를 막는다.
     * (Set 은 ConcurrentHashMap.newKeySet — 읽기측 {@code List.copyOf} 가 compute 밖에서 약-일관 순회.)
     */
    private static void addToSet(Map<String, Set<WebSocketSession>> map, String key, WebSocketSession ws) {
        map.compute(key, (k, set) -> {
            Set<WebSocketSession> s = (set != null) ? set : ConcurrentHashMap.newKeySet();
            s.add(ws);
            return s;
        });
    }

    private static void removeFromSet(Map<String, Set<WebSocketSession>> map, String key, WebSocketSession ws) {
        map.compute(key, (k, set) -> {
            if (set == null) {
                return null;
            }
            set.remove(ws);
            return set.isEmpty() ? null : set;
        });
    }

    public void registerSession(WebSocketSession ws) {
        // 등록 시점엔 핸들러가 핸드셰이크에서 속성을 심어둔 상태 — 없으면 등록 불가(fail-fast).
        String sid = Objects.requireNonNull(sessionId(ws), "WS 세션에 session_id 속성이 없습니다.");
        String uid = Objects.requireNonNull(userId(ws), "WS 세션에 user_id 속성이 없습니다.");
        localWsBySession.put(sid, ws);
        addToSet(userSubs, uid, ws);
        // 전송 상한 데코레이터 + 세션 직렬 실행기 — fan-out 송신을 폴 스레드에서 분리하고 느린 소켓을 차단.
        deliveryDecorated.put(sid, new ConcurrentWebSocketSessionDecorator(ws, sendTimeLimitMs, sendBufferBytes));
        deliveryExecutors.put(sid, new SessionSerialExecutor(deliveryPool, deliverySessionMaxQueued));
    }

    public void registerWsToRoom(WebSocketSession ws, String roomId) {
        addToSet(roomSubs, roomId, ws);
        Objects.requireNonNull(rooms(ws), "WS 세션에 subscribed_rooms 속성이 없습니다.").add(roomId);
    }

    public void unregisterWs(WebSocketSession ws) {
        String sid = sessionId(ws);
        String uid = userId(ws);
        if (sid != null) {
            localWsBySession.remove(sid);
            deliveryDecorated.remove(sid);
            deliveryExecutors.remove(sid);
        }
        if (uid != null) {
            removeFromSet(userSubs, uid, ws);
        }
        Set<String> subscribed = rooms(ws);
        if (subscribed != null) {
            for (String roomId : List.copyOf(subscribed)) {
                removeFromSet(roomSubs, roomId, ws);
            }
        }
    }

    // ──────────────────── 동적 방 구독 (cross-node) ────────────────────

    public void subscribeUserToRoom(String userId, String roomId) {
        if (!props.isMultiNode()) {
            localSubscribeUserToRoom(userId, roomId);
            return;
        }
        publishToStream(Map.of("op", "subscribe", "user_id", userId, "room_id", roomId));
    }

    public void unsubscribeUserFromRoom(String userId, String roomId) {
        if (!props.isMultiNode()) {
            localUnsubscribeUserFromRoom(userId, roomId);
            return;
        }
        publishToStream(Map.of("op", "unsubscribe", "user_id", userId, "room_id", roomId));
    }

    // ──────────────────── Fan-out (모드 분기) ────────────────────

    public void fanOutToRoom(String roomId, Map<String, Object> payload) {
        if (!props.isMultiNode()) {
            localDeliverToRoom(roomId, payload);
            return;
        }
        publishDeliveryBestEffort(Map.of("op", "room", "room_id", roomId, "payload", payload));
    }

    public void fanOutToUser(String userId, Map<String, Object> payload) {
        if (!props.isMultiNode()) {
            localDeliverToUser(userId, payload);
            return;
        }
        publishDeliveryBestEffort(Map.of("op", "user", "user_id", userId, "payload", payload));
    }

    public void fanOutToSession(String sessionId, Map<String, Object> payload) {
        if (!props.isMultiNode()) {
            localDeliverToSession(sessionId, payload);
            return;
        }
        publishDeliveryBestEffort(Map.of("op", "session", "session_id", sessionId, "payload", payload));
    }

    // ──────────────────── 디스패처 진입점 (redis_stream) ────────────────────

    @SuppressWarnings("unchecked")
    public void dispatchEnvelope(Map<String, Object> envelope) {
        String op = (String) envelope.getOrDefault("op", "unknown");
        try {
            // 필수 필드는 명시적으로 꺼내 검증 — 누락(malformed)이면 조용히 drop.
            String roomId = (String) envelope.get("room_id");
            String userId = (String) envelope.get("user_id");
            String sessionId = (String) envelope.get("session_id");
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
            switch (op) {
                case "room" -> {
                    if (roomId != null && payload != null) {
                        localDeliverToRoom(roomId, payload);
                    }
                }
                case "user" -> {
                    if (userId != null && payload != null) {
                        localDeliverToUser(userId, payload);
                    }
                }
                case "session" -> {
                    if (sessionId != null && payload != null) {
                        localDeliverToSession(sessionId, payload);
                    }
                }
                case "subscribe" -> {
                    if (userId != null && roomId != null) {
                        localSubscribeUserToRoom(userId, roomId);
                    }
                }
                case "unsubscribe" -> {
                    if (userId != null && roomId != null) {
                        localUnsubscribeUserFromRoom(userId, roomId);
                    }
                }
                default -> log.warn("알 수 없는 envelope op (drop): {}", op);
            }
        } catch (Exception e) {
            log.warn("envelope 처리 실패 (drop): op={}", op, e);
        }
    }

    // ──────────────────── 로컬 전달 ────────────────────

    private void localSubscribeUserToRoom(String userId, String roomId) {
        for (WebSocketSession ws : List.copyOf(userSubs.getOrDefault(userId, Set.of()))) {
            String sid = sessionId(ws);
            if (sid == null || !localWsBySession.containsKey(sid)) {
                continue;
            }
            registerWsToRoom(ws, roomId);
        }
    }

    private void localUnsubscribeUserFromRoom(String userId, String roomId) {
        Set<WebSocketSession> affected = userSubs.get(userId);
        if (affected == null || affected.isEmpty()) {
            return;
        }
        for (WebSocketSession ws : List.copyOf(affected)) {
            Set<String> r = rooms(ws);
            if (r != null) {
                r.remove(roomId);
            }
            removeFromSet(roomSubs, roomId, ws);
        }
    }

    private void localDeliverToRoom(String roomId, Map<String, Object> payload) {
        Object senderSid = payload.get("sender_session_id");
        for (WebSocketSession ws : List.copyOf(roomSubs.getOrDefault(roomId, Set.of()))) {
            if (senderSid != null && senderSid.equals(sessionId(ws))) {
                continue;
            }
            send(ws, payload);
        }
    }

    private void localDeliverToUser(String userId, Map<String, Object> payload) {
        for (WebSocketSession ws : List.copyOf(userSubs.getOrDefault(userId, Set.of()))) {
            send(ws, payload);
        }
    }

    private void localDeliverToSession(String sessionId, Map<String, Object> payload) {
        WebSocketSession ws = localWsBySession.get(sessionId);
        if (ws != null) {
            send(ws, payload);
        }
    }

    // ──────────────────── publish 헬퍼 (redis_stream) ────────────────────

    /** 전달 op publish — best-effort. 실시간 전용이라 publish 실패로 호출측을 막지 않는다(히스토리로 복구). */
    private void publishDeliveryBestEffort(Map<String, Object> envelope) {
        try {
            publishToStream(envelope);
        } catch (Exception e) {
            log.warn("fan-out publish 실패 (best-effort): op={}, err={}", envelope.get("op"), e.toString());
        }
    }

    /** 공유 Stream 에 envelope 한 건 XADD — 모든 노드가 자기 group 으로 읽어 dispatch 한다. */
    private void publishToStream(Map<String, Object> envelope) {
        String json = toJson(envelope);
        if (json == null) {
            return;
        }
        redis.opsForStream().add(ChatRedisKeys.CHAT_STREAM_KEY, Map.of("data", json));
    }

    private @Nullable String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("envelope 직렬화 실패 (drop)", e);
            return null;
        }
    }

    // ──────────────────── WS 송신 ────────────────────

    /**
     * 세션 직렬 실행기로 실제 송신을 offload — 폴/송신 스레드는 enqueue 만 하고 즉시 반환(블로킹 소켓이
     * 다른 방·노드 전체의 fan-out 을 막는 head-of-line 차단 제거). 세션 큐/풀 포화 시 그 전달만 드롭한다.
     */
    private void send(WebSocketSession ws, Map<String, Object> payload) {
        if (!ws.isOpen()) {
            unregisterWs(ws);
            return;
        }
        String sid = sessionId(ws);
        SessionSerialExecutor exec = sid != null ? deliveryExecutors.get(sid) : null;
        if (exec == null) {
            writeNow(ws, payload); // 미등록(직접 경로/레이스) — 인라인 폴백
            return;
        }
        try {
            exec.submit(() -> writeNow(ws, payload));
        } catch (RejectedExecutionException e) {
            // 세션 큐(느린 소켓) 또는 delivery 풀 포화 — 이 전달만 드롭(best-effort). 폴/송신 스레드는 안 막힌다.
            log.debug("fan-out 전달 드롭 (세션 큐/풀 포화): session_id={}", sid);
        }
    }

    /** 실제 WS 송신 — delivery 풀 스레드에서 실행. 전송 상한 데코레이터를 통해 보내 느린 소켓을 차단한다. */
    private void writeNow(WebSocketSession ws, Map<String, Object> payload) {
        if (!ws.isOpen()) {
            unregisterWs(ws);
            return;
        }
        WebSocketSession target = deliveryTarget(ws);
        try {
            String json = mapper.writeValueAsString(payload);
            // raw 세션 모니터로 직렬화 — 핸들러의 sendPing/safeSend(둘 다 synchronized(rawSession))와 상호배제해야
            // 같은 소켓에 동시 write(IllegalStateException)가 안 난다. write 는 상한 데코레이터를 통해 보낸다.
            synchronized (ws) {
                target.sendMessage(new TextMessage(json));
            }
        } catch (IOException | SessionLimitExceededException e) {
            // 전송 실패 또는 전송 상한 초과(느린 소켓 → 데코레이터가 세션 종료) — 세션 정리.
            log.warn("fan-out send 실패 (세션 정리): session_id={}, err={}", sessionId(ws), e.toString());
            unregisterWs(ws);
            try {
                ws.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        } catch (Exception e) {
            log.warn("fan-out send 실패: session_id={}, err={}", sessionId(ws), e.toString());
        }
    }

    /** 송신 대상 — 전송 상한 데코레이터(등록된 세션) 또는 raw 세션(폴백). */
    private WebSocketSession deliveryTarget(WebSocketSession ws) {
        String sid = sessionId(ws);
        WebSocketSession decorated = sid != null ? deliveryDecorated.get(sid) : null;
        return decorated != null ? decorated : ws;
    }

    /** 단일 세션에 직접 송신 (핸들러의 connected/unread_synced 초기 메시지용). */
    public void sendToSessionDirect(WebSocketSession ws, Map<String, Object> payload) {
        send(ws, payload);
    }
}
