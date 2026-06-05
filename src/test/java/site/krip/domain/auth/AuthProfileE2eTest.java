package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth 프로필 E2E — 3계층 인증(글로벌 Bearer → 유저 JWT → 가입/상태 체크)이 실제로 동작하는지 검증.
 * 이 테스트가 통과하면 이후 모든 도메인 E2E 가 같은 인증 헬퍼로 신뢰성 있게 호출된다.
 */
class AuthProfileE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Bearer 누락 → 401")
    void missingBearer() throws Exception {
        mockMvc.perform(get("/api/auth/profile/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("글로벌 Bearer 만 있고 유저 토큰 없음 → 401")
    void missingUserToken() throws Exception {
        mockMvc.perform(get("/api/auth/profile/me")
                        .header("Authorization", bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("잘못된 access token → 401")
    void wrongAccessToken() throws Exception {
        mockMvc.perform(get("/api/auth/profile/me")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bearer + 유효 유저 JWT + ACTIVE+detail 유저 → 200, 프로필 반환")
    void authenticatedProfile() throws Exception {
        String userId = fixtures.createActiveUser("프로필테스터");

        mockMvc.perform(get("/api/auth/profile/me")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value("프로필테스터"));
    }
}
