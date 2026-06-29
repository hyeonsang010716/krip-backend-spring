package site.krip.domain.chat;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 E2E 공통 베이스 — 방 생성 REST 헬퍼를 모은다. 응답 JSON 은 snake_case,
 * room type 은 소문자 enum({@code direct/group}).
 */
abstract class ChatTestSupport extends IntegrationTestSupport {

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
