package site.krip.domain.friend;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 차단 ↔ friendship 상호작용을 저장소(DB) 수준으로 검증 — 차단 시 friendship 이 실제로 삭제되고,
 * 해제해도 복원되지 않는다.
 */
class UserBlockFriendshipRepoE2eTest extends IntegrationTestSupport {

    @Autowired
    private FriendshipRepository friendshipRepository;

    private void makeFriends(String a, String b) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/friend/friendships/requests")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressee_id\":\"" + b + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String fid = JsonPath.read(res.getResponse().getContentAsString(), "$.friendship_id");
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", fid)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(b)))
                .andExpect(status().isOk());
    }

    private void block(String a, String b) throws Exception {
        mockMvc.perform(post("/api/friend/blocks")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_user_id\":\"" + b + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("차단 시 friendship row 가 삭제되고, 해제해도 복원되지 않는다")
    void blockDeletesFriendshipAndUnblockDoesNotRestore() throws Exception {
        String a = fixtures.createActiveUser("차단관계A");
        String b = fixtures.createActiveUser("차단관계B");

        makeFriends(a, b);
        assertThat(friendshipRepository.findBetween(a, b)).isPresent();

        block(a, b);
        assertThat(friendshipRepository.findBetween(a, b)).isEmpty();

        mockMvc.perform(delete("/api/friend/blocks/{targetUserId}", b)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isOk());

        // 해제 후에도 friendship 은 복원되지 않는다.
        assertThat(friendshipRepository.findBetween(a, b)).isEmpty();
    }
}
