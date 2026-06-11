package site.krip.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.global.config.ChatProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FanoutService} 단위 테스트 (in_process 모드).
 * Redis/노드레지스트리 없이 로컬 메모리 직배송 경로만 검증한다(가짜 WS 세션으로 송신 캡처).
 *
 * <p>핵심: ① 발신자 자기-에코 skip(sender_session_id) ② 유저의 모든 세션 전달 ③ 닫힌 소켓 정리.
 */
class FanoutServiceTest {

    private FanoutService fanout;
    private final Map<WebSocketSession, List<String>> captured = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        ChatProperties props = new ChatProperties("in_process", "node-1", 1, 60_000, 1 << 20, 1000);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // 동기 실행기(Runnable::run) — 송신을 인라인 유지해 단위 테스트가 결정적이게 한다.
        fanout = new FanoutService(props, redis, new ObjectMapper(), Runnable::run);
    }

    /** session_id/user_id/subscribed_rooms 속성을 가진 열린(또는 닫힌) 가짜 WS 세션. 송신을 captured 에 적재. */
    private WebSocketSession session(String sessionId, String userId, boolean open) {
        WebSocketSession ws = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(FanoutService.ATTR_SESSION_ID, sessionId);
        attrs.put(FanoutService.ATTR_USER_ID, userId);
        attrs.put(FanoutService.ATTR_ROOMS, ConcurrentHashMap.newKeySet());
        when(ws.getAttributes()).thenReturn(attrs);
        when(ws.isOpen()).thenReturn(open);
        List<String> sink = new ArrayList<>();
        captured.put(ws, sink);
        try {
            doAnswer(inv -> {
                sink.add(((TextMessage) inv.getArgument(0)).getPayload());
                return null;
            }).when(ws).sendMessage(any());
        } catch (Exception ignored) {
            // mock 설정엔 체크예외가 실제로 발생하지 않음
        }
        return ws;
    }

    private Map<String, Object> payload(String type, String senderSessionId) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", type);
        if (senderSessionId != null) {
            p.put("sender_session_id", senderSessionId);
        }
        return p;
    }

    @Test
    @DisplayName("방 fan-out 은 sender_session_id 세션(자기 에코)을 건너뛴다")
    void roomFanoutSkipsSenderSession() {
        WebSocketSession sender = session("s-sender", "u1", true);
        WebSocketSession other = session("s-other", "u2", true);
        fanout.registerSession(sender);
        fanout.registerSession(other);
        fanout.registerWsToRoom(sender, "room-1");
        fanout.registerWsToRoom(other, "room-1");

        fanout.fanOutToRoom("room-1", payload("message.new", "s-sender"));

        assertThat(captured.get(sender)).isEmpty();
        assertThat(captured.get(other)).hasSize(1);
        assertThat(captured.get(other).get(0)).contains("message.new");
    }

    @Test
    @DisplayName("유저 fan-out 은 해당 유저의 모든 세션에 전달된다")
    void userFanoutDeliversToAllSessions() {
        WebSocketSession s1 = session("s1", "u1", true);
        WebSocketSession s2 = session("s2", "u1", true);
        WebSocketSession otherUser = session("s3", "u2", true);
        fanout.registerSession(s1);
        fanout.registerSession(s2);
        fanout.registerSession(otherUser);

        fanout.fanOutToUser("u1", payload("room_left", null));

        assertThat(captured.get(s1)).hasSize(1);
        assertThat(captured.get(s2)).hasSize(1);
        assertThat(captured.get(otherUser)).isEmpty();
    }

    @Test
    @DisplayName("닫힌 소켓은 전달에서 제외되고 등록에서 정리된다")
    void closedSocketSkippedAndUnregistered() {
        WebSocketSession open = session("s-open", "u1", true);
        WebSocketSession closed = session("s-closed", "u2", false);
        fanout.registerSession(open);
        fanout.registerSession(closed);
        fanout.registerWsToRoom(open, "room-1");
        fanout.registerWsToRoom(closed, "room-1");

        fanout.fanOutToRoom("room-1", payload("message.new", null));

        assertThat(captured.get(open)).hasSize(1);
        assertThat(captured.get(closed)).isEmpty();

        // 정리되었으므로 이후 유저 fan-out 에도 닫힌 세션은 더 이상 대상이 아니다.
        fanout.fanOutToUser("u2", payload("ping", null));
        assertThat(captured.get(closed)).isEmpty();
    }

    @Test
    @DisplayName("unregisterWs 후에는 방 fan-out 대상에서 빠진다")
    void unregisterRemovesFromRoom() {
        WebSocketSession ws = session("s1", "u1", true);
        fanout.registerSession(ws);
        fanout.registerWsToRoom(ws, "room-1");

        fanout.unregisterWs(ws);
        fanout.fanOutToRoom("room-1", payload("message.new", null));

        assertThat(captured.get(ws)).isEmpty();
    }
}
