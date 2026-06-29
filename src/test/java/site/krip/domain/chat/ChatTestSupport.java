package site.krip.domain.chat;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.domain.chat.service.FanoutService;
import site.krip.support.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 E2E 공통 베이스 — 방 생성 REST 헬퍼 + fan-out mock WS 세션 헬퍼를 모은다. 응답 JSON 은 snake_case,
 * room type 은 소문자 enum({@code direct/group}). WS mock 헬퍼는 하위 패키지(worker/ws)에서도 쓰도록 public static.
 */
public abstract class ChatTestSupport extends IntegrationTestSupport {

    /** open 상태 + session/user/rooms attribute 를 갖춘 mock WS 세션 — fan-out/liveness 대상용. */
    public static WebSocketSession mockWsSession(String sessionId, String userId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(FanoutService.ATTR_SESSION_ID, sessionId);
        attrs.put(FanoutService.ATTR_USER_ID, userId);
        attrs.put(FanoutService.ATTR_ROOMS, ConcurrentHashMap.<String>newKeySet());
        when(ws.getAttributes()).thenReturn(attrs);
        return ws;
    }

    /** ws.sendMessage(TextMessage) 호출마다 latch 를 1 내린다 — 전달 도착 신호용. */
    public static void latchOnSend(WebSocketSession ws, CountDownLatch latch) throws Exception {
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));
    }

    /** 1:1 방 생성 요청을 보내고 raw 결과 반환(상태 검증 없음) — idempotency 등 상태를 직접 보는 경우용. */
    protected MvcResult createDirect(String me, String peer) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms/direct")
                        .with(auth(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("peer_user_id", peer)))
                .andReturn();
    }

    /** 1:1 방 생성(201 확인) 후 chat_room_id 반환 — 방을 precondition 으로 쓸 때. */
    protected String createDirectRoom(String me, String peer) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/chat/rooms/direct")
                        .with(auth(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("peer_user_id", peer)))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(res, "chat_room_id");
    }

    /** 그룹 방 생성(201 확인) 후 chat_room_id 반환. */
    protected String createGroup(String me, String title, String... memberIds) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/chat/rooms/group")
                        .with(auth(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", title, "member_ids", List.of(memberIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(res, "chat_room_id");
    }
}
