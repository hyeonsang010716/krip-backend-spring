package site.krip.domain.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 방 퇴장/강퇴 규칙 경계 E2E ({@code /api/chat/rooms}) — DIRECT 방·자기 강퇴·이미 떠난 멤버 강퇴는 모두 400.
 */
class ChatRoomRulesE2eTest extends ChatTestSupport {

    @Test
    @DisplayName("DIRECT 방 퇴장 시도 → 400 (그룹 방만 퇴장 가능)")
    void leaveDirectRoomRejected() throws Exception {
        String a = fixtures.createActiveUser("다이렉트퇴장A");
        String b = fixtures.createActiveUser("다이렉트퇴장B");
        String roomId = createDirectRoom(a, b);

        mockMvc.perform(post("/api/chat/rooms/{id}/leave", roomId)
                        .with(auth(a)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DIRECT 방 강퇴 시도 → 400 (그룹 방에서만 강퇴 가능)")
    void kickInDirectRoomRejected() throws Exception {
        String a = fixtures.createActiveUser("다이렉트강퇴A");
        String b = fixtures.createActiveUser("다이렉트강퇴B");
        String roomId = createDirectRoom(a, b);

        mockMvc.perform(post("/api/chat/rooms/{id}/kick", roomId)
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_id", b)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("방장이 자기 자신을 강퇴 → 400 (퇴장 API 사용)")
    void kickSelfRejected() throws Exception {
        String owner = fixtures.createActiveUser("자기강퇴방장");
        String member = fixtures.createActiveUser("자기강퇴멤버");
        makeFriends(owner, member);
        String roomId = createGroup(owner, "자기강퇴방", member);

        mockMvc.perform(post("/api/chat/rooms/{id}/kick", roomId)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_id", owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미 떠난 멤버 강퇴 → 400 (활성 멤버 아님)")
    void kickAlreadyLeftMember() throws Exception {
        String owner = fixtures.createActiveUser("재강퇴방장");
        String member = fixtures.createActiveUser("먼저나간멤버");
        makeFriends(owner, member);
        String roomId = createGroup(owner, "재강퇴방", member);

        // member 가 먼저 퇴장(204)
        mockMvc.perform(post("/api/chat/rooms/{id}/leave", roomId)
                        .with(auth(member)))
                .andExpect(status().isNoContent());

        // 방장이 이미 떠난 member 를 강퇴 시도 → 400
        mockMvc.perform(post("/api/chat/rooms/{id}/kick", roomId)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_id", member)))
                .andExpect(status().isBadRequest());
    }
}
