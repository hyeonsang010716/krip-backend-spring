package site.krip.domain.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.notification.entity.FcmToken;
import site.krip.domain.notification.repository.FcmTokenRepository;
import site.krip.support.IntegrationTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FCM 토큰 등록/해제 E2E — 경로 {@code /api/notification/fcm-token}. FCM 자격증명 미설정이라 발송은 no-op 이지만
 * 토큰 등록/해제 자체는 RDB 기준으로 정상 동작한다. 요청/응답 JSON snake_case.
 *
 * <p>커버: 등록(201) → 동일 토큰 재등록(멱등, 같은 fcm_token_id) → owner 교체(타 유저 재등록) →
 * 해제(200) → 미존재 토큰 해제(멱등 200) → 빈 토큰 본문(400).
 */
class FcmTokenE2eTest extends IntegrationTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FcmTokenRepository tokenRepo;

    private static String body(String token) {
        return "{\"token\": \"%s\"}".formatted(token);
    }

    @Test
    @DisplayName("토큰 등록 → 201, fcm_token_id/created_at 반환")
    void register() throws Exception {
        String userId = fixtures.createActiveUser("토큰등록자");

        mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("device-token-aaa")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fcm_token_id").exists())
                .andExpect(jsonPath("$.created_at").exists());
    }

    @Test
    @DisplayName("동일 유저가 같은 토큰 재등록 → 멱등(같은 fcm_token_id)")
    void reRegisterSameTokenIdempotent() throws Exception {
        String userId = fixtures.createActiveUser("재등록자");
        String token = "device-token-idem";

        MvcResult first = mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("fcm_token_id").asText();

        MvcResult second = mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isCreated())
                .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("fcm_token_id").asText();

        assertEquals(firstId, secondId, "같은 토큰 재등록은 동일 fcm_token_id 를 돌려줘야 한다");
    }

    @Test
    @DisplayName("동일 유저 재등록 → updated_at 갱신(같은 토큰도 마지막 등록 시각 반영)")
    void reRegisterRefreshesUpdatedAt() throws Exception {
        String userId = fixtures.createActiveUser("타임스탬프재등록자");
        String token = "device-token-touch";

        mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isCreated());

        // 등록 직후엔 created_at == updated_at.
        FcmToken afterCreate = tokenRepo.findByToken(token).orElseThrow();
        assertEquals(afterCreate.getCreatedAt(), afterCreate.getUpdatedAt(),
                "최초 등록 시 created_at 과 updated_at 이 같아야 한다");

        Thread.sleep(10); // updated_at 이 확실히 뒤가 되도록 최소 간격 확보.

        mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isCreated());

        // 동일 owner 재등록인데도 updated_at 이 created_at 보다 뒤로 갱신되어야 한다(early-return 회귀 방지).
        FcmToken afterReRegister = tokenRepo.findByToken(token).orElseThrow();
        assertTrue(afterReRegister.getUpdatedAt().isAfter(afterReRegister.getCreatedAt()),
                "동일 토큰 재등록 시 updated_at 이 갱신되어야 한다");
    }

    @Test
    @DisplayName("다른 유저가 같은 토큰 등록 → owner 교체(같은 행, 소유자만 바뀜)")
    void reRegisterSwapsOwner() throws Exception {
        String ownerA = fixtures.createActiveUser("소유자A");
        String ownerB = fixtures.createActiveUser("소유자B");
        String token = "device-token-swap";

        MvcResult first = mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(ownerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("fcm_token_id").asText();

        MvcResult second = mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(ownerB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isCreated())
                .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("fcm_token_id").asText();

        // 같은 토큰 → 같은 행(같은 id), 소유자만 B 로 교체.
        assertEquals(firstId, secondId, "동일 토큰은 owner 교체일 뿐 새 행이 아니다");
        FcmToken row = tokenRepo.findByToken(token).orElseThrow();
        assertEquals(ownerB, row.getUserId(), "토큰 소유자가 B 로 교체되어야 한다");
    }

    @Test
    @DisplayName("본인 소유 토큰 해제 → 200, 메시지 + 실제 삭제")
    void unregister() throws Exception {
        String userId = fixtures.createActiveUser("해제자");
        String token = "device-token-del";

        mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        assertTrue(tokenRepo.findByToken(token).isEmpty(), "해제 후 토큰이 삭제되어야 한다");
    }

    @Test
    @DisplayName("등록된 적 없는 토큰 해제 → 멱등 200")
    void unregisterAbsentIdempotent() throws Exception {
        String userId = fixtures.createActiveUser("미존재해제자");

        mockMvc.perform(delete("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("never-registered-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("빈 토큰 본문 → 400 (@Size min=1)")
    void emptyTokenBadRequest() throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("token 필드 누락 → 400 (@NotNull)")
    void missingTokenBadRequest() throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(post("/api/notification/fcm-token")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이 등록 → 401")
    void registerUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/notification/fcm-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("some-token")))
                .andExpect(status().isUnauthorized());
    }
}
