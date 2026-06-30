package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 차단 ↔ friendship 상호작용을 저장소(DB) 수준으로 검증 — 차단 시 friendship 이 실제로 삭제되고,
 * 해제해도 복원되지 않는다.
 */
@DisplayName("차단 repo — friendship 삭제·해제 후 미복원")
class UserBlockFriendshipRepoE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("차단 시 friendship row 가 삭제되고, 해제해도 복원되지 않는다")
    void blockDeletesFriendshipAndUnblockDoesNotRestore() throws Exception {
        // given
        String a = fixtures.createActiveUser("차단관계A");
        String b = fixtures.createActiveUser("차단관계B");

        befriendViaApi(a, b);
        assertThat(friendshipRepository.findBetween(a, b)).isPresent();

        blockViaApi(a, b);
        assertThat(friendshipRepository.findBetween(a, b)).isEmpty();

        mockMvc.perform(delete("/api/friend/blocks/{targetUserId}", b)
                        .with(auth(a)))
                .andExpect(status().isOk());

        // 해제 후에도 friendship 은 복원되지 않는다.
        assertThat(friendshipRepository.findBetween(a, b)).isEmpty();
    }
}
