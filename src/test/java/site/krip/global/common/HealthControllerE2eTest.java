package site.krip.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 헬스/레디니스 프로브 E2E ({@code /health}, {@code /ready}).
 * 인증 필터 제외 경로 — 토큰 없이 200 + 고정 JSON 을 반환해야 한다(K8s liveness/readiness 의존).
 */
class HealthControllerE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("GET /health — 토큰 없이 200, status=ok")
    void health() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("GET /ready — 토큰 없이 200, status=ready")
    void ready() throws Exception {
        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"));
    }
}
