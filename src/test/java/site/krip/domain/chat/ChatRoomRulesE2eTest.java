package site.krip.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 방 퇴장/강퇴 규칙 경계 E2E ({@code /api/chat/rooms}).
 *
 * <p>{@link ChatRoomE2eTest} 가 그룹 방의 정상 퇴장/강퇴(204)와 방장 권한(403)을 다룬다. 본 테스트는
 * 서비스가 enforce 하지만 비어 있던 경계를 메운다:
 * <ul>
 *   <li>DIRECT 방은 퇴장/강퇴 불가 → 400</li>
 *   <li>자기 자신 강퇴 → 400 (퇴장 API 사용해야 함)</li>
 *   <li>이미 떠난 멤버 강퇴 → 400</li>
 * </ul>
 */
class ChatRoomRulesE2eTest extends IntegrationTestSupport {

    private final ObjectMapper om = new ObjectMapper();

    @Autowired
    private FriendshipRepository friendshipRepo;

    private void makeFriends(String a, String b) {
        Friendship f = new Friendship(a, b);
        f.accept();
        friendshipRepo.save(f);
    }

    private String createDirect(String me, String peer) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/chat/rooms/direct")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peer_user_id\":\"" + peer + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("chat_room_id").asText();
    }

    private String createGroup(String me, String title, String... memberIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < memberIds.length; i++) {
            if (i > 0) {
                ids.append(",");
            }
            ids.append("\"").append(memberIds[i]).append("\"");
        }
        MvcResult res = mockMvc.perform(post("/api/chat/rooms/group")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"member_ids\":[" + ids + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("chat_room_id").asText();
    }

    @Test
    @DisplayName("DIRECT 방 퇴장 시도 → 400 (그룹 방만 퇴장 가능)")
    void leaveDirectRoomRejected() throws Exception {
        String a = fixtures.createActiveUser("다이렉트퇴장A");
        String b = fixtures.createActiveUser("다이렉트퇴장B");
        String roomId = createDirect(a, b);

        mockMvc.perform(post("/api/chat/rooms/{id}/leave", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DIRECT 방 강퇴 시도 → 400 (그룹 방에서만 강퇴 가능)")
    void kickInDirectRoomRejected() throws Exception {
        String a = fixtures.createActiveUser("다이렉트강퇴A");
        String b = fixtures.createActiveUser("다이렉트강퇴B");
        String roomId = createDirect(a, b);

        mockMvc.perform(post("/api/chat/rooms/{id}/kick", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + b + "\"}"))
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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + owner + "\"}"))
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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(member)))
                .andExpect(status().isNoContent());

        // 방장이 이미 떠난 member 를 강퇴 시도 → 400
        mockMvc.perform(post("/api/chat/rooms/{id}/kick", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + member + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
