package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로필 수정/탐색목록/통계/로그아웃 E2E ({@code /api/auth/profile}, {@code /api/auth/logout}).
 * {@link AuthProfileE2eTest} 가 다루지 않는 변경/목록/로그아웃 엔드포인트를 메운다.
 */
class ProfileManageE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("PATCH /me — 이름·나이·여행스타일 부분 수정 반영")
    void updateProfilePartial() throws Exception {
        String userId = fixtures.createActiveUser("수정전이름");

        mockMvc.perform(patch("/api/auth/profile/me")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_name", "수정후이름", "age", 30, "travel_styles", List.of("healing", "food_tour"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value("수정후이름"))
                .andExpect(jsonPath("$.age").value(30))
                .andExpect(jsonPath("$.travel_styles", hasItem("healing")))
                .andExpect(jsonPath("$.travel_styles", hasItem("food_tour")));

        // 재조회로 영속 확인
        mockMvc.perform(get("/api/auth/profile/me")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value("수정후이름"))
                .andExpect(jsonPath("$.age").value(30));
    }

    @Test
    @DisplayName("PATCH /me — travel_styles 빈 배열 → 전체 삭제")
    void updateProfileClearStyles() throws Exception {
        String userId = fixtures.createActiveUser("스타일삭제유저");

        // 먼저 스타일 세팅
        mockMvc.perform(patch("/api/auth/profile/me")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("travel_styles", List.of("healing"))))
                .andExpect(status().isOk());

        // 빈 배열 → 전체 삭제
        mockMvc.perform(patch("/api/auth/profile/me")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("travel_styles", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travel_styles").isEmpty());
    }

    @Test
    @DisplayName("PATCH /me — 잘못된 이메일 형식 → 400")
    void updateProfileInvalidEmail() throws Exception {
        String userId = fixtures.createActiveUser("이메일형식");

        mockMvc.perform(patch("/api/auth/profile/me")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "not-an-email")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /me — 컬럼 길이 초과 전화번호(21자) → 400 (구 동작은 DB 위반 500)")
    void updateProfileOversizedPhoneIs400() throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(patch("/api/auth/profile/me")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("phone_number", "012345678901234567890"))) // 21자 > varchar(20)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /all — 본인 제외, 타 ACTIVE 유저 노출")
    void getAllOtherUsersExcludesSelf() throws Exception {
        String me = fixtures.createActiveUser("탐색본인");
        String other = fixtures.createActiveUser("탐색대상유저");

        mockMvc.perform(get("/api/auth/profile/all")
                        .with(auth(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[*].user_id", hasItem(other)))
                .andExpect(jsonPath("$.users[?(@.user_id == '" + me + "')]").doesNotExist());
    }

    @Test
    @DisplayName("GET /me/stats — 신규 유저 좋아요/친구 0")
    void statsForNewUser() throws Exception {
        String userId = fixtures.createActiveUser("통계신규");

        mockMvc.perform(get("/api/auth/profile/me/stats")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_feed_likes").value(0))
                .andExpect(jsonPath("$.total_friends").value(0));
    }

    @Test
    @DisplayName("POST /logout → 200 + Set-Cookie, 그리고 그 토큰은 폐기돼 재사용 시 401")
    void logoutRevokesToken() throws Exception {
        String userId = fixtures.createActiveUser("로그아웃유저");
        String token = userToken(userId); // 한 번 발급해 캡처 — 같은 jti 로 폐기·재사용을 검증

        // 로그아웃 전: 토큰 정상 동작
        mockMvc.perform(get("/api/auth/profile/me/stats")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", token))
                .andExpect(status().isOk());

        // 로그아웃 → 200 + Set-Cookie + 이 토큰의 jti 폐기
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(jsonPath("$.message").exists());

        // 같은 토큰 재사용 → 폐기돼 401 (revoke 가 빠지면 이 단언이 깨진다)
        mockMvc.perform(get("/api/auth/profile/me/stats")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("로그아웃된 토큰입니다."));
    }
}
