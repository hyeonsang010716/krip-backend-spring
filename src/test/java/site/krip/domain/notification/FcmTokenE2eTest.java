package site.krip.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.notification.entity.FcmToken;
import site.krip.domain.notification.repository.FcmTokenRepository;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FCM 토큰 등록/해제 E2E — 경로 {@code /api/notification/fcm-token}. FCM 자격증명 미설정이라
 * 발송은 no-op 이지만 등록/해제는 RDB 기준 정상 동작. 요청/응답 JSON snake_case.
 */
class FcmTokenE2eTest extends IntegrationTestSupport {

    @Autowired
    private FcmTokenRepository tokenRepo;

    @Test
    @DisplayName("토큰 등록 → 201, fcm_token_id/created_at 반환")
    void register() throws Exception {
        String userId = fixtures.createActiveUser("토큰등록자");

        mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", "device-token-aaa")))
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
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = idFrom(first, "fcm_token_id");

        MvcResult second = mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isCreated())
                .andReturn();
        String secondId = idFrom(second, "fcm_token_id");

        assertThat(secondId).as("같은 토큰 재등록은 동일 fcm_token_id 를 돌려줘야 한다").isEqualTo(firstId);
    }

    @Test
    @DisplayName("동일 유저 재등록 → updated_at 갱신(같은 토큰도 마지막 등록 시각 반영)")
    void reRegisterRefreshesUpdatedAt() throws Exception {
        String userId = fixtures.createActiveUser("타임스탬프재등록자");
        String token = "device-token-touch";

        mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isCreated());

        // 등록 직후엔 created_at == updated_at.
        FcmToken afterCreate = tokenRepo.findByToken(token).orElseThrow();
        assertThat(afterCreate.getUpdatedAt()).as("최초 등록 시 created_at 과 updated_at 이 같아야 한다")
                .isEqualTo(afterCreate.getCreatedAt());

        Thread.sleep(10); // updated_at 이 확실히 뒤가 되도록 최소 간격 확보.

        mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isCreated());

        // 동일 owner 재등록인데도 updated_at 이 created_at 보다 뒤로 갱신되어야 한다(early-return 회귀 방지).
        FcmToken afterReRegister = tokenRepo.findByToken(token).orElseThrow();
        assertThat(afterReRegister.getUpdatedAt()).as("동일 토큰 재등록 시 updated_at 이 갱신되어야 한다")
                .isAfter(afterReRegister.getCreatedAt());
    }

    @Test
    @DisplayName("다른 유저가 같은 토큰 등록 → owner 교체(같은 행, 소유자만 바뀜)")
    void reRegisterSwapsOwner() throws Exception {
        String ownerA = fixtures.createActiveUser("소유자A");
        String ownerB = fixtures.createActiveUser("소유자B");
        String token = "device-token-swap";

        MvcResult first = mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(ownerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = idFrom(first, "fcm_token_id");

        MvcResult second = mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(ownerB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isCreated())
                .andReturn();
        String secondId = idFrom(second, "fcm_token_id");

        // 같은 토큰 → 같은 행(같은 id), 소유자만 B 로 교체.
        assertThat(secondId).as("동일 토큰은 owner 교체일 뿐 새 행이 아니다").isEqualTo(firstId);
        FcmToken row = tokenRepo.findByToken(token).orElseThrow();
        assertThat(row.getUserId()).as("토큰 소유자가 B 로 교체되어야 한다").isEqualTo(ownerB);
    }

    @Test
    @DisplayName("본인 소유 토큰 해제 → 200, 메시지 + 실제 삭제")
    void unregister() throws Exception {
        String userId = fixtures.createActiveUser("해제자");
        String token = "device-token-del";

        mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        assertThat(tokenRepo.findByToken(token)).as("해제 후 토큰이 삭제되어야 한다").isEmpty();
    }

    @Test
    @DisplayName("등록된 적 없는 토큰 해제 → 멱등 200")
    void unregisterAbsentIdempotent() throws Exception {
        String userId = fixtures.createActiveUser("미존재해제자");

        mockMvc.perform(delete("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", "never-registered-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("빈 토큰 본문 → 400 (@Size min=1)")
    void emptyTokenBadRequest() throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("token 필드 누락 → 400 (@NotNull)")
    void missingTokenBadRequest() throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(post("/api/notification/fcm-token")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이 등록 → 401")
    void registerUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/notification/fcm-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", "some-token")))
                .andExpect(status().isUnauthorized());
    }
}
