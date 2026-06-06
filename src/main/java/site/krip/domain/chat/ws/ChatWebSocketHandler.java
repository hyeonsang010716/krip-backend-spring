package site.krip.domain.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import site.krip.domain.chat.dto.response.MessageSentAck;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.service.FanoutService;
import site.krip.domain.chat.service.MessageHistoryService;
import site.krip.domain.chat.service.MessageService;
import site.krip.domain.chat.service.RoomService;
import site.krip.domain.chat.service.SessionService;
import site.krip.domain.chat.service.UnreadRecoveryService;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.IsoTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 채팅 WebSocket 핸들러 — {@code /api/ws/chat}.
 *
 * <p>연결 시 세션 생성 → 방 구독 → connected/unread_synced 송신 → 서버 측 heartbeat(30s)로 Redis TTL 연장.
 * op: send / refresh / read. 매 op 진입 시 세션 유효성(Redis)을 확인하고, 실패는 server_error/read_failed/auth_expired 로 응답.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String SUBPROTOCOL_VERSION = "krip.chat.v1";
    private static final int HEARTBEAT_INTERVAL_SEC = 30;
    private static final int CLOSE_AUTH_EXPIRED = 4001;

    private final SessionService sessionService;
    private final RoomService roomService;
    private final MessageService messageService;
    private final MessageHistoryService historyService;
    private final UnreadRecoveryService unreadRecovery;
    private final FanoutService fanout;
    private final JwtProvider jwtProvider;
    private final ObjectMapper mapper;

    // 채팅 WS 전용 스케줄러(스프링 관리) — 블로킹 @Scheduled 잡과 격리, 종료 시 자동 정리.
    private final ThreadPoolTaskScheduler heartbeatScheduler;
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(SessionService sessionService, RoomService roomService,
                                MessageService messageService, MessageHistoryService historyService,
                                UnreadRecoveryService unreadRecovery, FanoutService fanout,
                                JwtProvider jwtProvider, ObjectMapper mapper,
                                @Qualifier("chatWsScheduler") ThreadPoolTaskScheduler heartbeatScheduler) {
        this.sessionService = sessionService;
        this.roomService = roomService;
        this.messageService = messageService;
        this.historyService = historyService;
        this.unreadRecovery = unreadRecovery;
        this.fanout = fanout;
        this.jwtProvider = jwtProvider;
        this.mapper = mapper;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(SUBPROTOCOL_VERSION);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_WS_USER);
        String jti = (String) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_WS_JTI);

        String sessionId;
        try {
            sessionId = sessionService.createSession(userId, jti);
        } catch (Exception e) {
            log.warn("세션 생성 실패: user_id={}, err={}", userId, e.toString());
            safeSend(session, Map.of("type", "server_error", "reason", "session_create_failed"));
            session.close(CloseStatus.SERVICE_RESTARTED);
            return;
        }

        session.getAttributes().put(FanoutService.ATTR_SESSION_ID, sessionId);
        session.getAttributes().put(FanoutService.ATTR_USER_ID, userId);
        session.getAttributes().put(FanoutService.ATTR_ROOMS, ConcurrentHashMap.newKeySet());

        fanout.registerSession(session);
        try {
            for (String rid : roomService.listUserRoomIds(userId)) {
                fanout.registerWsToRoom(session, rid);
            }
        } catch (Exception e) {
            log.warn("방 목록 로드 실패: user_id={}, err={}", userId, e.toString());
        }

        fanout.sendToSessionDirect(session, Map.of("type", "connected", "session_id", sessionId));

        try {
            Map<String, Integer> counts = historyService.unreadCounts(userId);
            if (!counts.isEmpty()) {
                fanout.sendToSessionDirect(session, Map.of("type", "unread_synced", "counts", counts));
            } else {
                spawnRecoverUnread(session, userId);
            }
        } catch (Exception e) {
            log.warn("unread 동기화 실패 (무시): user_id={}, err={}", userId, e.toString());
        }

        Duration interval = Duration.ofSeconds(HEARTBEAT_INTERVAL_SEC);
        ScheduledFuture<?> hb = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                sessionService.heartbeat(sessionId, userId);
            } catch (Exception e) {
                log.warn("heartbeat 실패 (계속): session_id={}, err={}", sessionId, e.toString());
            }
        }, Instant.now().plus(interval), interval);
        heartbeatTasks.put(sessionId, hb);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = (String) session.getAttributes().get(FanoutService.ATTR_SESSION_ID);
        String userId = (String) session.getAttributes().get(FanoutService.ATTR_USER_ID);

        if (!sessionService.sessionExists(sessionId)) {
            safeSend(session, Map.of("type", "auth_expired"));
            session.close(new CloseStatus(CLOSE_AUTH_EXPIRED));
            return;
        }

        Map<String, Object> req;
        try {
            req = mapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            safeSend(session, Map.of("type", "server_error", "reason", "invalid op: malformed json"));
            return;
        }
        String op = req.get("op") instanceof String s ? s : "unknown";

        try {
            switch (op) {
                case "send" -> handleSend(session, sessionId, userId, req);
                case "read" -> handleRead(sessionId, userId, req);
                case "refresh" -> handleRefresh(session, sessionId, userId, req);
                default -> safeSend(session, Map.of("type", "server_error", "reason", "invalid op: " + op));
            }
        } catch (ApiException e) {
            // 검증/권한 등 의도된 오류는 사용자 메시지를 그대로 전달.
            sendOpError(session, op, req, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            // ChatUpstreamException(저장 재시도 소진)·DataAccessException·런타임 오류 등 비-ApiException 도
            // 소켓을 조용히 끊지 않고 server_error 로 통지한다. 내부 상세는 노출하지 않는다.
            log.warn("WS op 처리 실패: op={}, session_id={}, err={}", op, sessionId, e.toString());
            sendOpError(session, op, req, "server_error");
        }
    }

    /** op 실패 응답: read 는 read_failed(room_id 포함), 그 외는 server_error. */
    private void sendOpError(WebSocketSession session, String op, Map<String, Object> req, String reason) {
        if ("read".equals(op)) {
            Map<String, Object> p = new HashMap<>();
            p.put("type", "read_failed");
            p.put("room_id", req.get("room_id"));
            p.put("reason", reason);
            safeSend(session, p);
        } else {
            safeSend(session, Map.of("type", "server_error", "reason", reason));
        }
    }

    private void handleSend(WebSocketSession session, String sessionId, String userId,
                            Map<String, Object> req) {
        String roomId = str(req.get("room_id"));
        String clientMsgId = str(req.get("client_msg_id"));
        String content = str(req.get("content"));
        if (roomId == null || clientMsgId == null || content == null) {
            throw ApiException.badRequest("invalid op: room_id/client_msg_id/content 필수");
        }
        if (content.length() > 2000) {
            throw ApiException.badRequest("invalid op: content 는 2000자 이하");
        }
        MessageType type = req.get("type") != null ? MessageType.from(str(req.get("type"))) : MessageType.TEXT;

        MessageSentAck ack = messageService.sendMessage(userId, sessionId, roomId, clientMsgId, type, content);
        Map<String, Object> p = new HashMap<>();
        p.put("type", "message.sent");
        p.put("client_msg_id", ack.clientMsgId());
        p.put("message_id", ack.messageId());
        p.put("server_seq", ack.serverSeq());
        p.put("created_at", IsoTimestamp.format(ack.createdAt()));
        fanout.sendToSessionDirect(session, p);
    }

    private void handleRead(String sessionId, String userId, Map<String, Object> req) {
        String roomId = str(req.get("room_id"));
        Object seqRaw = req.get("up_to_server_seq");
        if (roomId == null || !(seqRaw instanceof Number)) {
            throw ApiException.badRequest("invalid op: room_id/up_to_server_seq 필수");
        }
        long upTo = ((Number) seqRaw).longValue();
        roomService.markRead(userId, sessionId, roomId, upTo); // read_ack/read 는 내부에서 fan-out
    }

    private void handleRefresh(WebSocketSession session, String sessionId, String userId,
                               Map<String, Object> req) throws Exception {
        String token = str(req.get("token"));
        String tokenUser;
        try {
            tokenUser = token != null ? jwtProvider.parseUserId(token) : null;
        } catch (Exception e) {
            tokenUser = null;
        }
        if (tokenUser == null || !tokenUser.equals(userId)) {
            safeSend(session, Map.of("type", "auth_expired"));
            session.close(new CloseStatus(CLOSE_AUTH_EXPIRED));
            return;
        }
        String newJti = token.length() > 32 ? token.substring(0, 32) : token;
        sessionService.updateTokenJti(sessionId, newJti);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get(FanoutService.ATTR_SESSION_ID);
        String userId = (String) session.getAttributes().get(FanoutService.ATTR_USER_ID);

        try {
            fanout.unregisterWs(session);
        } catch (Exception e) {
            log.warn("fanout unregister 실패 (무시): session_id={}, err={}", sessionId, e.toString());
        }
        if (sessionId != null) {
            ScheduledFuture<?> hb = heartbeatTasks.remove(sessionId);
            if (hb != null) {
                hb.cancel(true);
            }
            try {
                sessionService.terminateSession(sessionId, userId);
            } catch (Exception e) {
                log.warn("세션 종료 실패 (무시): session_id={}, err={}", sessionId, e.toString());
            }
        }
    }

    private void spawnRecoverUnread(WebSocketSession session, String userId) {
        heartbeatScheduler.execute(() -> {
            try {
                Map<String, Integer> counts = unreadRecovery.recoverUnreadForUser(userId);
                if (!counts.isEmpty() && session.isOpen()) {
                    fanout.sendToSessionDirect(session, Map.of("type", "unread_synced", "counts", counts));
                }
            } catch (Exception e) {
                log.warn("unread 백그라운드 복구 실패: user_id={}, err={}", userId, e.toString());
            }
        });
    }

    private void safeSend(WebSocketSession session, Map<String, Object> payload) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            log.debug("safeSend 실패 (WS 종료 가능): {}", e.toString());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
