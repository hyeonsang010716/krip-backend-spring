package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 탈퇴/취소 E2E.
 * 경로: {@code DELETE /api/auth/withdraw}, {@code POST /api/auth/withdraw/cancel} (RegisterCheckFilter 제외).
 */
class WithdrawE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("탈퇴 요청 → 200 + scheduled_purge_at + 쿠키 만료, 이후 INACTIVE 라 프로필은 419")
    void withdrawThenPendingBlocksProfile() throws Exception {
        String userId = fixtures.createActiveUser("탈퇴유저");

        mockMvc.perform(delete("/api/auth/withdraw")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduled_purge_at").exists())
                .andExpect(cookie().maxAge("utk", 0));

        // INACTIVE → RegisterCheckFilter 가 419(withdrawal_pending) 로 차단
        mockMvc.perform(get("/api/auth/profile/me")
                        .with(auth(userId)))
                .andExpect(status().is(419))
                .andExpect(jsonPath("$.status").value("withdrawal_pending"));
    }

    @Test
    @DisplayName("탈퇴 후 유예 내 취소 → ACTIVE 복구, 프로필 다시 200")
    void cancelRestoresActive() throws Exception {
        String userId = fixtures.createActiveUser("복구유저");

        mockMvc.perform(delete("/api/auth/withdraw")
                        .with(auth(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/withdraw/cancel")
                        .with(auth(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/profile/me")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value("복구유저"));
    }

    @Test
    @DisplayName("이미 탈퇴 진행 중인 유저가 재탈퇴 → 409")
    void doubleWithdrawConflict() throws Exception {
        String userId = fixtures.createActiveUser("중복탈퇴");

        mockMvc.perform(delete("/api/auth/withdraw")
                        .with(auth(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/auth/withdraw")
                        .with(auth(userId)))
                .andExpect(status().isConflict());
    }
}
