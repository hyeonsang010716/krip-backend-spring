package site.krip.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static Set<String> rooms(WebSocketSession ws) {
        return (Set<String>) ws.getAttributes().get(ATTR_ROOMS);
    }

    private static String sessionId(WebSocketSession ws) {
        return (String) ws.getAttributes().get(ATTR_SESSION_ID);
    }

    private static String userId(WebSocketSession ws) {
        return (String) ws.getAttributes().get(ATTR_USER_ID);
    }

    // ──────────────────── 등록/해제 (로컬 전용) ────────────────────

    public void registerSession(WebSocketSession ws) {
        String sid = sessionId(ws);
        localWsBySession.put(sid, ws);
        userSubs.computeIfAbsent(userId(ws), k -> ConcurrentHashMap.newKeySet()).add(ws);
        // 전송 상한 데코레이터 + 세션 직렬 실행기 — fan-out 송신을 폴 스레드에서 분리하고 느린 소켓을 차단.
        deliveryDecorated.put(sid, new ConcurrentWebSocketSessionDecorator(ws, sendTimeLimitMs, sendBufferBytes));
        deliveryExecutors.put(sid, new SessionSerialExecutor(deliveryPool, deliverySessionMaxQueued));
    }

    public void registerWsToRoom(WebSocketSession ws, String roomId) {
        roomSubs.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(ws);
        rooms(ws).add(roomId);
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
            Set<WebSocketSession> set = userSubs.get(uid);
            if (set != null) {
                set.remove(ws);
                if (set.isEmpty()) {
                    userSubs.remove(uid);
                }
            }
        }
        Set<String> subscribed = rooms(ws);
        if (subscribed != null) {
            for (String roomId : List.copyOf(subscribed)) {
                Set<WebSocketSession> set = roomSubs.get(roomId);
                if (set != null) {
                    set.remove(ws);
                    if (set.isEmpty()) {
                        roomSubs.remove(roomId);
                    }
                }
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
        publishToStream(Map.of("op", "room", "room_id", roomId, "payload", payload));
    }

    public void fanOutToUser(String userId, Map<String, Object> payload) {
        if (!props.isMultiNode()) {
            localDeliverToUser(userId, payload);
            return;
        }
        publishToStream(Map.of("op", "user", "user_id", userId, "payload", payload));
    }

    public void fanOutToSession(String sessionId, Map<String, Object> payload) {
        if (!props.isMultiNode()) {
            localDeliverToSession(sessionId, payload);
            return;
        }
        publishToStream(Map.of("op", "session", "session_id", sessionId, "payload", payload));
    }

    // ──────────────────── 디스패처 진입점 (redis_stream) ────────────────────

    @SuppressWarnings("unchecked")
    public void dispatchEnvelope(Map<String, Object> envelope) {
        String op = (String) envelope.getOrDefault("op", "unknown");
        try {
            switch (op) {
                case "room" -> localDeliverToRoom((String) envelope.get("room_id"),
                        (Map<String, Object>) envelope.get("payload"));
                case "user" -> localDeliverToUser((String) envelope.get("user_id"),
                        (Map<String, Object>) envelope.get("payload"));
                case "session" -> localDeliverToSession((String) envelope.get("session_id"),
                        (Map<String, Object>) envelope.get("payload"));
                case "subscribe" -> localSubscribeUserToRoom((String) envelope.get("user_id"),
                        (String) envelope.get("room_id"));
                case "unsubscribe" -> localUnsubscribeUserFromRoom((String) envelope.get("user_id"),
                        (String) envelope.get("room_id"));
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
        Set<WebSocketSession> roomSet = roomSubs.get(roomId);
        for (WebSocketSession ws : List.copyOf(affected)) {
            Set<String> r = rooms(ws);
            if (r != null) {
                r.remove(roomId);
            }
            if (roomSet != null) {
                roomSet.remove(ws);
            }
        }
        if (roomSet != null && roomSet.isEmpty()) {
            roomSubs.remove(roomId);
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

    /** 공유 Stream 에 envelope 한 건 XADD — 모든 노드가 자기 group 으로 읽어 dispatch 한다. */
    private void publishToStream(Map<String, Object> envelope) {
        String json = toJson(envelope);
        if (json == null) {
            return;
        }
        redis.opsForStream().add(ChatRedisKeys.CHAT_STREAM_KEY, Map.of("data", json));
    }

    private String toJson(Map<String, Object> value) {
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
            // 데코레이터가 동시 send 직렬화·상한을 보장하지만, 인라인 폴백을 대비해 세션 단위 락도 유지.
            synchronized (target) {
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
