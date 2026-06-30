package site.krip.domain.chat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import site.krip.domain.chat.service.RoomService;
import site.krip.global.auth.jwt.TokenRevocationService;
import site.krip.support.IntegrationTestSupport;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실 WebSocket 핸드셰이크 + op E2E — {@code @SpringBootTest(RANDOM_PORT)} 임베디드 톰캣에 JSR-356 클라이언트로
 * {@code /api/ws/chat} 실제 연결. 인증은 쿠키 대신 {@code auth.<jwt>} 서브프로토콜 + Origin 화이트리스트.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("WS 채팅 — 핸드셰이크·send/read·시스템 위조 차단·폐기 토큰")
class ChatWebSocketE2eTest extends IntegrationTestSupport {

    private static final String ALLOWED_ORIGIN = "https://krip.site";
    /** WS 메시지/핸드셰이크 대기 상한(ms). */
    private static final long WAIT_MS = 5000;

    @LocalServerPort
    private int port;

    @Autowired
    private RoomService roomService;

    @Autowired
    private TokenRevocationService revocation;

    /** 수신 텍스트 메시지를 큐에 모으는 클라이언트 핸들러. */
    static class CollectingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }

    private WebSocketSession connect(CollectingHandler handler, List<String> subprotocols) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(ALLOWED_ORIGIN);
        headers.setSecWebSocketProtocol(subprotocols);
        return client.execute(handler, headers, URI.create("ws://localhost:" + port + "/api/ws/chat"))
                .get(5, TimeUnit.SECONDS);
    }

    /** 지정 type 의 메시지가 올 때까지 대기(타임아웃 시 AssertionError). */
    private JsonNode awaitType(CollectingHandler handler, String type, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String msg = handler.messages.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (msg == null) {
                break;
            }
            JsonNode node = objectMapper.readTree(msg);
            if (type.equals(node.path("type").asText())) {
                return node;
            }
        }
        throw new AssertionError("WS 메시지 대기 타임아웃: type=" + type);
    }

    @Test
    @DisplayName("유효 토큰(auth 서브프로토콜) + 허용 Origin → 핸드셰이크 성공 + connected 수신")
    void connectsAndReceivesConnected() throws Exception {
        // given
        String a = fixtures.createActiveUser("ws접속유저");
        CollectingHandler h = new CollectingHandler();

        // when
        WebSocketSession session = connect(h, List.of("krip.chat.v1", "auth." + userToken(a)));

        // then
        JsonNode connected = awaitType(h, "connected", WAIT_MS);
        assertThat(connected.path("session_id").asText()).isNotBlank();

        session.close();
    }

    @Test
    @DisplayName("send op → message.sent ACK, 이어서 read op → read_ack")
    void sendThenReadOverWebSocket() throws Exception {
        // given
        String a = fixtures.createActiveUser("ws발신");
        String b = fixtures.createActiveUser("ws수신");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        CollectingHandler h = new CollectingHandler();
        WebSocketSession session = connect(h, List.of("krip.chat.v1", "auth." + userToken(a)));
        awaitType(h, "connected", WAIT_MS);

        session.sendMessage(new TextMessage(json(
                "op", "send", "room_id", room, "client_msg_id", "cm-1",
                "content", "hello ws", "type", "text")));
        JsonNode ack = awaitType(h, "message.sent", WAIT_MS);
        assertThat(ack.path("client_msg_id").asText()).isEqualTo("cm-1");
        long seq = ack.path("server_seq").asLong();
        assertThat(seq).isGreaterThan(0);

        session.sendMessage(new TextMessage(json(
                "op", "read", "room_id", room, "up_to_server_seq", seq)));
        JsonNode readAck = awaitType(h, "read_ack", WAIT_MS);
        assertThat(readAck.path("up_to_server_seq").asLong()).isEqualTo(seq);

        session.close();
    }

    @Test
    @DisplayName("send op 에 type=system → server_error 로 거부 (SYSTEM 위조 차단)")
    void clientSystemTypeRejected() throws Exception {
        // given
        String a = fixtures.createActiveUser("ws위조발신");
        String b = fixtures.createActiveUser("ws위조수신");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        CollectingHandler h = new CollectingHandler();
        WebSocketSession session = connect(h, List.of("krip.chat.v1", "auth." + userToken(a)));
        awaitType(h, "connected", WAIT_MS);

        // when
        // room_id/client_msg_id/content 는 유효 — SYSTEM 가드에 도달하도록. 가드 없으면 message.sent 가 와 타임아웃 실패.
        session.sendMessage(new TextMessage(json(
                "op", "send", "room_id", room, "client_msg_id", "cm-sys",
                "content", "forged", "type", "system")));

        // then
        JsonNode err = awaitType(h, "server_error", WAIT_MS);
        assertThat(err.path("reason").asText()).contains("system");

        session.close();
    }

    @Test
    @DisplayName("토큰 없이 핸드셰이크 시도 → 거부(연결 실패)")
    void handshakeRejectedWithoutToken() {
        CollectingHandler h = new CollectingHandler();
        assertThatThrownBy(() -> connect(h, List.of("krip.chat.v1")))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("refresh op — 폐기된 토큰이면 auth_expired 로 거절한다 (폐기 jti 재무장 차단)")
    void refreshWithRevokedTokenRejected() throws Exception {
        // given
        String a = fixtures.createActiveUser("ws리프레시");
        String token = userToken(a);
        CollectingHandler h = new CollectingHandler();
        WebSocketSession session = connect(h, List.of("krip.chat.v1", "auth." + token));
        awaitType(h, "connected", WAIT_MS);

        // 이 토큰의 jti 를 폐기한 뒤 그 토큰으로 refresh 시도 → 재무장돼선 안 된다.
        String jti = jwtProvider.parse(token).jti();
        revocation.revoke(jti, Instant.now().plusSeconds(3600));

        session.sendMessage(new TextMessage(json("op", "refresh", "token", token)));

        // revocation 체크가 빠지면 auth_expired 가 안 와 타임아웃으로 실패한다.
        awaitType(h, "auth_expired", WAIT_MS);
    }
}
