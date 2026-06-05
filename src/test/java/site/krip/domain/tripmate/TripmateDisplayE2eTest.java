package site.krip.domain.tripmate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate display 토글 권한 + 비표시 필터링 E2E ({@code /api/tripmate/posts}).
 *
 * <p>{@link TripmatePostE2eTest} 가 작성자 본인의 토글 정상 흐름(true→false)만 다룬다. 본 테스트는
 * 남은 경계를 메운다: 남이 토글(403), 미존재 게시글 토글(404), 비표시 게시글이 공개 목록에서 제외되는지.
 */
class TripmateDisplayE2eTest extends IntegrationTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

    private static String createBody(String title, String content) {
        return """
                {
                  "title": "%s",
                  "content": "%s",
                  "preferred_age_min": 20,
                  "preferred_age_max": 35,
                  "preferred_gender": "any",
                  "region": "제주",
                  "travel_start_date": "2026-09-01",
                  "travel_end_date": "2026-09-07",
                  "companion_type": "friend",
                  "image_urls": []
                }
                """.formatted(title, content);
    }

    private String createPost(String userId, String title) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/tripmate/posts")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(title, "동행을 찾는 충분히 긴 본문입니다.")))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("post_id").asText();
    }

    @Test
    @DisplayName("display 토글 — 작성자가 아닌 유저 → 403")
    void toggleDisplayByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("토글주인");
        String other = fixtures.createActiveUser("토글타인");
        String postId = createPost(owner, "토글 권한 글");

        mockMvc.perform(patch("/api/tripmate/posts/" + postId + "/display")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("display 토글 — 존재하지 않는 게시글 → 404")
    void toggleDisplayMissingPost() throws Exception {
        String userId = fixtures.createActiveUser("토글미존재");

        mockMvc.perform(patch("/api/tripmate/posts/no-such-post/display")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비표시(display=false)로 토글된 게시글은 공개 목록에서 제외된다")
    void hiddenPostExcludedFromList() throws Exception {
        String owner = fixtures.createActiveUser("비표시작성자");
        String viewer = fixtures.createActiveUser("목록뷰어");
        String postId = createPost(owner, "곧 숨길 글");

        // 토글 → display=false
        mockMvc.perform(patch("/api/tripmate/posts/" + postId + "/display")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_displayed").value(false));

        // 다른 유저의 공개 목록에서 해당 글이 보이지 않아야 한다
        mockMvc.perform(get("/api/tripmate/posts")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").doesNotExist());
    }
}
