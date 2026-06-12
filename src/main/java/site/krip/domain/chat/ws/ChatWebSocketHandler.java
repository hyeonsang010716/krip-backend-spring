package site.krip.domain.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import site.krip.domain.chat.dto.response.MessageSentAck;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.service.FanoutService;
import site.krip.domain.chat.service.MessageService;
import site.krip.domain.chat.service.RoomService;
import site.krip.domain.chat.service.SessionSerialExecutor;
import site.krip.domain.chat.service.SessionService;
import site.krip.domain.chat.service.UnreadService;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.auth.jwt.TokenRevocationService;
import site.krip.global.common.exception.ApiException;
import site.krip.global.config.ExecutorProperties;
import site.krip.global.support.IsoTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 채팅 WebSocket 핸들러 — {@code /api/ws/chat}.
 *
 * <p>연결 시 세션 생성 → 방 구독 → connected/unread_synced 송신. 노드 단위 sweep(30s)이 로컬 세션 전체의 Redis TTL 을 일괄 연장.
 * op: send / refresh / read. 매 op 진입 시 세션 유효성(Redis)을 확인하고, 실패는 server_error/read_failed/auth_expired 로 응답.
 *
 * <p>op 의 블로킹 DB/Redis/Mongo 처리는 컨테이너 I/O 스레드를 막지 않도록 {@code chatOpExecutor} 로 넘긴다.
 * 세션당 {@link SessionSerialExecutor} 로 직렬화해 같은 세션의 처리 순서(server_seq 단조성)를 보존하고,
 * 대기 한도 초과/풀 포화 시 server_busy 로 백프레셔한다. 송신은 {@code FanoutService} 가 세션 단위로 직렬화한다.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String SUBPROTOCOL_VERSION = "krip.chat.v1";
    private static final int HEARTBEAT_INTERVAL_SEC = 30;
    private static final int CLOSE_AUTH_EXPIRED = 4001;
    private static final int CLOSE_LIVENESS_TIMEOUT = 4002;
    /** pong 미수신 허용 한도 — sweep 3주기(=SESSION_TTL 90s). 초과 시 도달 불가로 보고 세션을 닫는다. */
    private static final long PONG_TIMEOUT_MS = HEARTBEAT_INTERVAL_SEC * 3L * 1000L;

    private final SessionService sessionService;
    private final RoomService roomService;
    private final MessageService messageService;
    private final UnreadService unreadService;
    private final FanoutService fanout;
    private final JwtProvider jwtProvider;
    private final TokenRevocationService revocation;
    private final ObjectMapper mapper;

    // 채팅 WS 전용 스케줄러(스프링 관리) — 블로킹 @Scheduled 잡과 격리, 종료 시 자동 정리.
    private final ThreadPoolTaskScheduler heartbeatScheduler;
    private final Executor recoverExecutor;
    // op(send/read) 처리를 컨테이너 I/O 스레드에서 분리하는 공유 유계 풀 + 세션당 대기 한도.
    private final Executor chatOpExecutor;
    private final int sessionOpMaxQueued;
    // 이 노드의 로컬 생존 세션(sid→ws). sweep 이 ping/TTL 연장 대상으로 순회한다.
    final Map<String, WebSocketSession> liveSessions = new ConcurrentHashMap<>();
    // 세션별 마지막 pong 수신 시각(ms) — sweep 이 도달성 판단에 사용한다.
    final Map<String, Long> lastPongAt = new ConcurrentHashMap<>();
    // 세션당 직렬 실행기 — op 를 chatOpExecutor 위에서 순서 보존하며 실행. 연결 시 생성, 종료 시 제거.
    private final Map<String, SessionSerialExecutor> sessionExecutors = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(SessionService sessionService, RoomService roomService,
                                MessageService messageService, UnreadService unreadService,
                                FanoutService fanout,
                                JwtProvider jwtProvider, TokenRevocationService revocation,
                                ObjectMapper mapper,
                                @Qualifier("chatWsScheduler") ThreadPoolTaskScheduler heartbeatScheduler,
                                @Qualifier("recoverExecutor") Executor recoverExecutor,
                                @Qualifier("chatOpExecutor") Executor chatOpExecutor,
                                ExecutorProperties execProps) {
        this.sessionService = sessionService;
        this.roomService = roomService;
        this.messageService = messageService;
        this.unreadService = unreadService;
        this.fanout = fanout;
        this.jwtProvider = jwtProvider;
        this.revocation = revocation;
        this.mapper = mapper;
        this.heartbeatScheduler = heartbeatScheduler;
        this.recoverExecutor = recoverExecutor;
        this.chatOpExecutor = chatOpExecutor;
        this.sessionOpMaxQueued = execProps.chatOpSessionMaxQueued();
    }

    @PostConstruct
    void startHeartbeatSweep() {
        Duration interval = Duration.ofSeconds(HEARTBEAT_INTERVAL_SEC);
        heartbeatScheduler.scheduleWithFixedDelay(this::sweepLiveness,
                Instant.now().plus(interval), interval);
    }

    /**
     * 주기 sweep — 각 로컬 세션에 ping 을 보내고, {@link #PONG_TIMEOUT_MS} 넘게 pong 이 없는(=도달 불가) 세션은
     * 닫는다. 생존이 확인된 세션만 Redis TTL 을 연장해, half-open 좀비가 TTL 자가청소를 회피하지 못하게 한다.
     */
    void sweepLiveness() {
        if (liveSessions.isEmpty()) {
            return;
        }
        try {
            long deadline = System.currentTimeMillis() - PONG_TIMEOUT_MS;
            Map<String, String> alive = new HashMap<>();
            for (Map.Entry<String, WebSocketSession> entry : liveSessions.entrySet()) {
                String sessionId = entry.getKey();
                WebSocketSession ws = entry.getValue();
                if (lastPongAt.getOrDefault(sessionId, System.currentTimeMillis()) < deadline || !sendPing(ws)) {
                    log.info("WS liveness 종료 — pong 미수신/ping 실패: session_id={}", sessionId);
                    closeQuietly(ws, new CloseStatus(CLOSE_LIVENESS_TIMEOUT));
                    // wedged 소켓은 afterConnectionClosed 콜백이 지연될 수 있어 sweep 이 즉시 정리한다(콜백과 멱등).
                    cleanupLocalSession(ws);
                    continue;
                }
                String userId = (String) ws.getAttributes().get(FanoutService.ATTR_USER_ID);
                if (userId != null) {
                    alive.put(sessionId, userId);
                }
            }
            if (!alive.isEmpty()) {
                sessionService.heartbeatBatch(alive);
            }
        } catch (Exception e) {
            log.warn("liveness sweep 실패 (계속): err={}", e.toString());
        }
    }

    /** 세션에 ping 프레임 전송 — 송신 실패(소켓 파손) 시 false. */
    private boolean sendPing(WebSocketSession ws) {
        try {
            if (!ws.isOpen()) {
                return false;
            }
            synchronized (ws) {
                ws.sendMessage(new PingMessage());
            }
            return true;
        } catch (Exception e) {
            log.debug("WS ping 송신 실패: {}", e.toString());
            return false;
        }
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
            log.warn("세션 생성 실패: user_id={}", userId, e);
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
            log.warn("방 목록 로드 실패: user_id={}", userId, e);
        }

        fanout.sendToSessionDirect(session, Map.of("type", "connected", "session_id", sessionId));

        // 커서 파생 계산은 Mongo 를 칠 수 있어 연결 스레드를 막지 않도록 항상 백그라운드로.
        spawnUnreadSync(session, userId);

        sessionExecutors.put(sessionId, new SessionSerialExecutor(chatOpExecutor, sessionOpMaxQueued));
        // pong 기준선을 먼저 심어 첫 sweep 이 갓 연결된 세션을 미수신으로 오판하지 않게 한다.
        lastPongAt.put(sessionId, System.currentTimeMillis());
        liveSessions.put(sessionId, session);
    }

    /**
     * 컨테이너 I/O 스레드는 op 를 세션 직렬 실행기에 제출만 하고 즉시 반환한다(블로킹 DB/Redis/Mongo 는
     * chatOpExecutor 로 이동). 세션 대기 한도 초과/풀 포화 시 server_busy 로 백프레셔하고 소켓은 유지한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = (String) session.getAttributes().get(FanoutService.ATTR_SESSION_ID);
        String userId = (String) session.getAttributes().get(FanoutService.ATTR_USER_ID);
        SessionSerialExecutor exec = sessionId != null ? sessionExecutors.get(sessionId) : null;
        if (exec == null) {
            return; // 세션 등록 전/종료 후 도착 — 무시(곧 close 처리됨).
        }
        String payload = message.getPayload();
        try {
            exec.submit(() -> processOp(session, sessionId, userId, payload));
        } catch (RejectedExecutionException e) {
            log.warn("chat op 백프레셔 — server_busy: session_id={} ({})", sessionId, e.getMessage());
            safeSend(session, Map.of("type", "server_error", "reason", "server_busy"));
        }
    }

    /** 클라가 sweep 의 ping 에 자동 응답한 pong — 마지막 생존 시각을 갱신한다. */
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        String sessionId = (String) session.getAttributes().get(FanoutService.ATTR_SESSION_ID);
        if (sessionId != null) {
            lastPongAt.put(sessionId, System.currentTimeMillis());
        }
    }

    /** op 1건 처리 — chatOpExecutor(세션 직렬) 위에서 실행. 세션 유효성 확인·파싱·핸들러 호출 일체. */
    @SuppressWarnings("unchecked")
    private void processOp(WebSocketSession session, String sessionId, String userId, String payload) {
        if (!sessionService.sessionExists(sessionId)) {
            safeSend(session, Map.of("type", "auth_expired"));
            closeQuietly(session, new CloseStatus(CLOSE_AUTH_EXPIRED));
            return;
        }

        Map<String, Object> req;
        try {
            req = mapper.readValue(payload, Map.class);
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
            log.warn("WS op 처리 실패: op={}, session_id={}", op, sessionId, e);
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
                               Map<String, Object> req) {
        String token = str(req.get("token"));
        JwtProvider.ParsedToken parsed;
        try {
            parsed = token != null ? jwtProvider.parse(token) : null;
        } catch (Exception e) {
            parsed = null;
        }
        // refresh 는 세션의 토큰을 교체하는 재인증 — 핸드셰이크와 동일하게 폐기 토큰을 거절한다(폐기된 jti 로 무장 차단).
        if (parsed == null || !userId.equals(parsed.userId()) || revocation.isRevoked(parsed.jti())) {
            safeSend(session, Map.of("type", "auth_expired"));
            closeQuietly(session, new CloseStatus(CLOSE_AUTH_EXPIRED));
            return;
        }
        // 세션의 token_jti 를 새 토큰의 진짜 jti 로 갱신 — 로그아웃의 토큰별 종료가 refresh 이후에도 맞도록.
        sessionService.updateTokenJti(sessionId, parsed.jti());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanupLocalSession(session);
    }

    /**
     * 로컬 세션 정리 — fan-out 등록 해제 + 로컬 맵 제거 + Redis 세션 상태 정리.
     * afterConnectionClosed(콜백)와 sweepLiveness(즉시) 양쪽에서 호출되며, 모든 연산이 멱등이라 이중 실행 안전.
     */
    private void cleanupLocalSession(WebSocketSession session) {
        String sessionId = (String) session.getAttributes().get(FanoutService.ATTR_SESSION_ID);
        String userId = (String) session.getAttributes().get(FanoutService.ATTR_USER_ID);

        try {
            fanout.unregisterWs(session);
        } catch (Exception e) {
            log.warn("fanout unregister 실패 (무시): session_id={}, err={}", sessionId, e.toString());
        }
        if (sessionId != null) {
            liveSessions.remove(sessionId);
            lastPongAt.remove(sessionId);
            // 신규 op 수락 중단(드레인 중인 작업은 완료될 때까지 큐를 비운다 — safeSend 가 isOpen 으로 가드).
            sessionExecutors.remove(sessionId);
            try {
                sessionService.terminateSession(sessionId, userId);
            } catch (Exception e) {
                log.warn("세션 종료 실패 (무시): session_id={}, err={}", sessionId, e.toString());
            }
        }
    }

    private void spawnUnreadSync(WebSocketSession session, String userId) {
        recoverExecutor.execute(() -> {
            try {
                Map<String, Integer> counts = unreadService.countsForUser(userId);
                if (!counts.isEmpty() && session.isOpen()) {
                    fanout.sendToSessionDirect(session, Map.of("type", "unread_synced", "counts", counts));
                }
            } catch (Exception e) {
                log.warn("unread 동기화 실패: user_id={}", userId, e);
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

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("WS close 실패 (이미 종료 가능): {}", e.toString());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
