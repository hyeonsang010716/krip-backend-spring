package site.krip.domain.friend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 친구 요청 상태머신 + 권한 가드 E2E ({@code /api/friend/friendships}).
 *
 * <p>{@link FriendshipE2eTest} 가 정상 흐름을 다룬다면, 본 테스트는 서비스가 enforce 하지만
 * 기존 E2E 가 비우고 있던 경계를 메운다:
 * <ul>
 *   <li>권한: 잘못된 당사자가 거절/취소/삭제 → 403</li>
 *   <li>상태머신: PENDING 이 아닌 요청의 수락/거절/취소, ACCEPTED 가 아닌 관계의 삭제 → 400</li>
 *   <li>차단 방향: 상대가 나를 차단한 상태에서 요청 → 400</li>
 *   <li>미존재 요청 / 미존재 유저 차단 → 400</li>
 * </ul>
 */
class FriendshipStateMachineE2eTest extends IntegrationTestSupport {

    private final ObjectMapper om = new ObjectMapper();

    @Autowired
    private UserBlockRepository blockRepo;

    /** A → B 친구 요청 생성(201), friendship_id 반환. */
    private String sendRequest(String requester, String addressee) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/friend/friendships/requests")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressee_id\":\"" + addressee + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("friendship_id").asText();
    }

    private void accept(String addressee, String friendshipId) throws Exception {
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(addressee)))
                .andExpect(status().isOk());
    }

    // ──────────────────── 권한 가드 (403) ────────────────────

    @Test
    @DisplayName("requester(권한 없음)가 거절 시도 → 403")
    void rejectByWrongParty() throws Exception {
        String a = fixtures.createActiveUser("거절권한A");
        String b = fixtures.createActiveUser("거절권한B");
        String friendshipId = sendRequest(a, b);

        // 거절은 addressee(B) 만 가능 — requester(A) 가 시도 → 403
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/reject", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("addressee(권한 없음)가 취소 시도 → 403")
    void cancelByNonRequester() throws Exception {
        String a = fixtures.createActiveUser("취소권한A");
        String b = fixtures.createActiveUser("취소권한B");
        String friendshipId = sendRequest(a, b);

        // 취소는 requester(A) 만 가능 — addressee(B) 가 시도 → 403
        mockMvc.perform(delete("/api/friend/friendships/requests/{id}", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(b)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("제3자가 친구 삭제 시도 → 403")
    void removeFriendByOutsider() throws Exception {
        String a = fixtures.createActiveUser("삭제당사자A");
        String b = fixtures.createActiveUser("삭제당사자B");
        String outsider = fixtures.createActiveUser("삭제제3자");
        String friendshipId = sendRequest(a, b);
        accept(b, friendshipId);

        mockMvc.perform(delete("/api/friend/friendships/{id}", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(outsider)))
                .andExpect(status().isForbidden());
    }

    // ──────────────────── 상태머신 (400) ────────────────────

    @Test
    @DisplayName("이미 수락된(ACCEPTED) 요청을 다시 수락 → 400")
    void acceptNonPending() throws Exception {
        String a = fixtures.createActiveUser("재수락A");
        String b = fixtures.createActiveUser("재수락B");
        String friendshipId = sendRequest(a, b);
        accept(b, friendshipId);

        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("이미 수락된(ACCEPTED) 요청을 거절 → 400 (대기 중인 요청만 거절)")
    void rejectNonPending() throws Exception {
        String a = fixtures.createActiveUser("수락후거절A");
        String b = fixtures.createActiveUser("수락후거절B");
        String friendshipId = sendRequest(a, b);
        accept(b, friendshipId);

        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/reject", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(b)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미 수락된(ACCEPTED) 요청을 취소 → 400 (대기 중인 요청만 취소)")
    void cancelNonPending() throws Exception {
        String a = fixtures.createActiveUser("수락후취소A");
        String b = fixtures.createActiveUser("수락후취소B");
        String friendshipId = sendRequest(a, b);
        accept(b, friendshipId);

        mockMvc.perform(delete("/api/friend/friendships/requests/{id}", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PENDING(미수락) 관계를 친구 삭제 시도 → 400 (ACCEPTED 만 삭제 가능)")
    void removePendingFriendship() throws Exception {
        String a = fixtures.createActiveUser("대기삭제A");
        String b = fixtures.createActiveUser("대기삭제B");
        String friendshipId = sendRequest(a, b);

        mockMvc.perform(delete("/api/friend/friendships/{id}", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 차단 방향 / 미존재 ────────────────────

    @Test
    @DisplayName("상대가 나를 차단한 상태에서 친구 요청 → 400")
    void requestToUserWhoBlockedMe() throws Exception {
        String requester = fixtures.createActiveUser("차단당한요청자");
        String addressee = fixtures.createActiveUser("차단한상대");
        // addressee 가 requester 를 차단(내가 건 차단이 아닌 역방향)
        blockRepo.save(new UserBlock(addressee, requester));

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressee_id\":\"" + addressee + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("TOCTOU 방어: PENDING 요청과 차단이 공존(경합 산물)하면 수락 → 400 (addressee 가 requester 차단)")
    void acceptBlockedByAddressee() throws Exception {
        String requester = fixtures.createActiveUser("수락차단요청자");
        String addressee = fixtures.createActiveUser("수락차단상대");
        String friendshipId = sendRequest(requester, addressee);

        // 경합으로 friendship 이 정리되지 않은 채 차단만 들어간 상태를 직접 재현(repo 직삽입)
        blockRepo.save(new UserBlock(addressee, requester));

        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(addressee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("TOCTOU 방어: requester 가 addressee 를 차단한 경우에도 수락 → 400 (방향 무관)")
    void acceptBlockedByRequester() throws Exception {
        String requester = fixtures.createActiveUser("역방향차단요청자");
        String addressee = fixtures.createActiveUser("역방향차단상대");
        String friendshipId = sendRequest(requester, addressee);

        blockRepo.save(new UserBlock(requester, addressee));

        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(addressee)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 친구 요청 수락 → 404")
    void acceptMissingFriendship() throws Exception {
        String a = fixtures.createActiveUser("미존재수락자");

        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", "no-such-friendship")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 유저 차단 → 404")
    void blockNonexistentUser() throws Exception {
        String a = fixtures.createActiveUser("차단요청자");

        mockMvc.perform(post("/api/friend/blocks")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_user_id\":\"no-such-user\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }
}
