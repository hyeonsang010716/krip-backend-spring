package site.krip.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.worker.NodeRegistry;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.config.ChatProperties;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅 이벤트 fan-out.
 *
 * <p>{@code in_process}: 단일 프로세스 메모리 직배송. {@code node_channel}: 다중 노드 Redis Pub/Sub
 * (`node:{node_id}` 채널). 호출측은 모드 무관 — {@code fanOutTo*} / {@code (un)subscribeUserToRoom} 만 사용.
 * publisher 는 자기 자신에게도 publish 해 디스패처가 {@code localDeliver*} 로 들어가는 통일 경로를 유지한다.
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
    private final NodeRegistry nodeRegistry;
    private final ObjectMapper mapper;

    private final Map<String, Set<WebSocketSession>> roomSubs = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> userSubs = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> localWsBySession = new ConcurrentHashMap<>();

    public FanoutService(ChatProperties props, StringRedisTemplate redis,
                         NodeRegistry nodeRegistry, ObjectMapper mapper) {
        this.props = props;
        this.redis = redis;
        this.nodeRegistry = nodeRegistry;
        this.mapper = mapper;
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
        localWsBySession.put(sessionId(ws), ws);
        userSubs.computeIfAbsent(userId(ws), k -> ConcurrentHashMap.newKeySet()).add(ws);
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
        if (!props.isNodeChannel()) {
            localSubscribeUserToRoom(userId, roomId);
            return;
        }
        publishBroadcast(Map.of("op", "subscribe", "user_id", userId, "room_id", roomId));
    }

    public void unsubscribeUserFromRoom(String userId, String roomId) {
        if (!props.isNodeChannel()) {
            localUnsubscribeUserFromRoom(userId, roomId);
            return;
        }
        publishBroadcast(Map.of("op", "unsubscribe", "user_id", userId, "room_id", roomId));
    }

    // ──────────────────── Fan-out (모드 분기) ────────────────────

    public void fanOutToRoom(String roomId, Map<String, Object> payload) {
        if (!props.isNodeChannel()) {
            localDeliverToRoom(roomId, payload);
            return;
        }
        publishBroadcast(Map.of("op", "room", "room_id", roomId, "payload", payload));
    }

    public void fanOutToUser(String userId, Map<String, Object> payload) {
        if (!props.isNodeChannel()) {
            localDeliverToUser(userId, payload);
            return;
        }
        publishBroadcast(Map.of("op", "user", "user_id", userId, "payload", payload));
    }

    public void fanOutToSession(String sessionId, Map<String, Object> payload) {
        if (!props.isNodeChannel()) {
            localDeliverToSession(sessionId, payload);
            return;
        }
        publishToSessionNode(sessionId, payload);
    }

    // ──────────────────── 디스패처 진입점 (node_channel) ────────────────────

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

    // ──────────────────── publish 헬퍼 (node_channel) ────────────────────

    private void publishBroadcast(Map<String, Object> envelope) {
        List<String> nodes = nodeRegistry.listActiveNodes();
        if (nodes.isEmpty()) {
            return;
        }
        String json = toJson(envelope);
        if (json == null) {
            return;
        }
        for (String node : nodes) {
            redis.convertAndSend(ChatRedisKeys.nodeChannel(node), json);
        }
    }

    private void publishToSessionNode(String sessionId, Map<String, Object> payload) {
        String targetNode = redis.opsForValue().get(ChatRedisKeys.wsRoute(sessionId));
        if (targetNode == null) {
            return;
        }
        Map<String, Object> envelope = Map.of(
                "op", "session", "session_id", sessionId, "payload", payload);
        String json = toJson(envelope);
        if (json != null) {
            redis.convertAndSend(ChatRedisKeys.nodeChannel(targetNode), json);
        }
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

    private void send(WebSocketSession ws, Map<String, Object> payload) {
        if (!ws.isOpen()) {
            unregisterWs(ws);
            return;
        }
        try {
            String json = mapper.writeValueAsString(payload);
            // WebSocketSession 은 동시 send 에 안전하지 않으므로 세션 단위 직렬화.
            synchronized (ws) {
                ws.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
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

    /** 단일 세션에 직접 송신 (핸들러의 connected/unread_synced 초기 메시지용). */
    public void sendToSessionDirect(WebSocketSession ws, Map<String, Object> payload) {
        send(ws, payload);
    }
}
