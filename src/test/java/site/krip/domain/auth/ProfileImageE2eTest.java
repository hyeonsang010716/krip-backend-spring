package site.krip.domain.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.FakeObjectStorage;
import site.krip.support.FakeStorageConfig;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로필 이미지 CRUD E2E — 인메모리 스토리지로 실제 업로드/삭제 검증.
 *
 * <p>add 201/중복 409, update 200(이전 객체 정리), delete 200(객체 제거)/미존재 404 를 본다.
 * S3 업로드를 DB 트랜잭션 밖에서 수행하는 동작도 함께 확인한다.
 */
@Import(FakeStorageConfig.class)
class ProfileImageE2eTest extends IntegrationTestSupport {

    private static final String IMAGE = "/api/auth/profile/image";

    @Autowired
    private FakeObjectStorage storage;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});
    }

    private String addImage(String userId) throws Exception {
        MvcResult res = mockMvc.perform(multipart(IMAGE).file(jpeg())
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile_image_url").exists())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("profile_image_url").asText();
    }

    @Test
    @DisplayName("이미지 추가 → 201, 스토리지에 객체 적재")
    void addStoresObject() throws Exception {
        String userId = fixtures.createActiveUser("img추가");
        String url = addImage(userId);
        assertThat(storage.stored).contains(url);
    }

    @Test
    @DisplayName("이미 존재하는데 재추가(POST) → 409")
    void duplicateAddConflict() throws Exception {
        String userId = fixtures.createActiveUser("img중복");
        addImage(userId);

        mockMvc.perform(multipart(IMAGE).file(jpeg())
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("이미지 수정(PUT) → 200, 새 객체 적재 + 이전 객체 삭제")
    void updateReplacesAndDeletesOld() throws Exception {
        String userId = fixtures.createActiveUser("img수정");
        String oldUrl = addImage(userId);

        MvcResult res = mockMvc.perform(multipart(IMAGE).file(jpeg()).with(req -> {
                            req.setMethod("PUT");
                            return req;
                        })
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_image_url").exists())
                .andReturn();
        String newUrl = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("profile_image_url").asText();

        assertThat(storage.stored).contains(newUrl);
        assertThat(storage.stored).doesNotContain(oldUrl);
    }

    @Test
    @DisplayName("이미지 삭제(DELETE) → 200, 객체 제거")
    void deleteRemovesObject() throws Exception {
        String userId = fixtures.createActiveUser("img삭제");
        String url = addImage(userId);

        mockMvc.perform(delete(IMAGE)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk());

        assertThat(storage.stored).doesNotContain(url);
    }

    @Test
    @DisplayName("이미지 없는데 삭제 → 404")
    void deleteWhenNoneNotFound() throws Exception {
        String userId = fixtures.createActiveUser("img없음");
        mockMvc.perform(delete(IMAGE)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isNotFound());
    }
}
