package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2차 회원가입 E2E.
 * 경로: {@code POST /api/auth/register} (RegisterCheckFilter 제외 경로).
 */
@DisplayName("2차 회원가입 — 완료·재가입·필수 필드")
class RegisterE2eTest extends IntegrationTestSupport {

    /** 모든 필수 필드를 채운 유효 가입 본문. */
    private String validBody() {
        return json(
                "email", "new@test.local",
                "user_name", "신규유저",
                "phone_number", "01012345678",
                "age", 28,
                "gender", "male",
                "nationality", "KR",
                "travel_styles", List.of("activity", "healing"));
    }

    @Test
    @DisplayName("1차 유저가 2차 가입 → 200, 이후 프로필 조회 가능")
    void registerSucceedsThenProfileAccessible() throws Exception {
        // given
        String userId = fixtures.createPreRegisterUser();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody())
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 2차 가입 완료 → RegisterCheckFilter 통과 → 프로필 조회 200
        mockMvc.perform(get("/api/auth/profile/me")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value("신규유저"));
    }

    @Test
    @DisplayName("이미 2차 가입 완료된 유저가 재가입 → 409")
    void duplicateRegisterConflict() throws Exception {
        // given
        String userId = fixtures.createActiveUser("이미가입");

        // when & then
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody())
                        .with(auth(userId)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("필수 필드 누락(user_name) → 400")
    void missingRequiredFieldBadRequest() throws Exception {
        // given
        String userId = fixtures.createPreRegisterUser();
        // user_name 누락
        String invalid = json("email", "x@test.local", "phone_number", "010", "age", 20,
                "gender", "male", "nationality", "KR");

        // when & then
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid)
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }
}
