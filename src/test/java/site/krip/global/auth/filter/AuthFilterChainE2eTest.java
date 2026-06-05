package site.krip.global.auth.filter;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.global.cache.RegisteredCacheManager;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 인증 필터 체인(Bearer / LoginAuth / RegisterCheck) E2E.
 *
 * <p>화이트리스트 우회와 419(탈퇴 유예) 동작까지 본다. 보호 엔드포인트로는
 * 로그인+가입확인이 모두 필요한 {@code GET /api/friend/friendships} 를 사용한다.
 */
class AuthFilterChainE2eTest extends IntegrationTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegisteredCacheManager registeredCache;

    @Test
    @DisplayName("/api/public 은 세 필터를 모두 우회한다 — 무인증 호출이 401 이 아니라 400(무효 토큰)으로 처리")
    void publicEndpointBypassesAuthFilters() throws Exception {
        // 인증 헤더 없이 호출. 우회되지 않으면 Bearer 필터가 401 을 냈을 것. 우회되므로 share 토큰 파싱 실패 → 400.
        mockMvc.perform(get("/api/public/share/plan/garbage-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Bearer 헤더 없음 → 401")
    void bearerMissingRejected() throws Exception {
        String u = fixtures.createActiveUser("bearerless");
        mockMvc.perform(get("/api/friend/friendships")
                        .header("X-Auth-Token", userToken(u)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인 토큰(X-Auth-Token/utk) 없음 → 401")
    void loginTokenMissingRejected() throws Exception {
        mockMvc.perform(get("/api/friend/friendships")
                        .header("Authorization", bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("utk 쿠키만으로도 인증된다(헤더 부재 시 쿠키 fallback)")
    void utkCookieAuthenticates() throws Exception {
        String u = fixtures.createActiveUser("쿠키유저");
        mockMvc.perform(get("/api/friend/friendships")
                        .header("Authorization", bearer())
                        .cookie(new Cookie("utk", userToken(u))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").exists());
    }

    @Test
    @DisplayName("Swagger 문서 경로는 무인증으로 통과 — /docs, /openapi.json, /openapi.json/swagger-config")
    void swaggerDocsBypassAuthFilters() throws Exception {
        // /docs 는 swagger-ui 로 리다이렉트(302). 필터에 막히면 401 이었을 것.
        mockMvc.perform(get("/docs"))
                .andExpect(status().is3xxRedirection());
        // OpenAPI 스펙 — 무인증 200.
        mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk());
        // swagger-ui 설정 엔드포인트(스펙 path 하위) — prefix 제외로 무인증 200.
        // (회귀: 이 경로가 BearerTokenFilter 에 막혀 401 → "Failed to load remote configuration" 이던 버그)
        mockMvc.perform(get("/openapi.json/swagger-config"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("INACTIVE(탈퇴 유예) 유저 → 419 + status=withdrawal_pending")
    void inactiveUserGets419() throws Exception {
        String u = fixtures.createActiveUser("탈퇴유예유저");
        User user = userRepository.findById(u).orElseThrow();
        user.changeStatus(UserStatus.INACTIVE);
        userRepository.save(user);
        registeredCache.invalidate(u);

        mockMvc.perform(get("/api/friend/friendships")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(u)))
                .andExpect(status().is(419))
                .andExpect(jsonPath("$.status").value("withdrawal_pending"))
                .andExpect(jsonPath("$.detail").exists());
    }
}
