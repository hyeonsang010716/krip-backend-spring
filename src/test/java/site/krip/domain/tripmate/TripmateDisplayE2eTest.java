package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate display 토글 권한 + 비표시 필터링 E2E ({@code /api/tripmate/posts}).
 * {@link TripmatePostE2eTest} 의 정상 흐름 외 경계만: 남이 토글(403), 미존재(404), 비표시 글 목록 제외.
 */
@DisplayName("트립메이트 display 토글 — 권한·공개 목록 제외")
class TripmateDisplayE2eTest extends TripmateTestSupport {

    /** 제주 지역 모집글 생성 후 post_id 반환 (display 토글 대상). */
    private String createPost(String userId, String title) throws Exception {
        return createPost(userId, title, "동행을 찾는 충분히 긴 본문입니다.", "제주");
    }

    @Test
    @DisplayName("display 토글 — 작성자가 아닌 유저 → 403")
    void toggleDisplayByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("토글주인");
        String other = fixtures.createActiveUser("토글타인");
        String postId = createPost(owner, "토글 권한 글");

        mockMvc.perform(patch("/api/tripmate/posts/{postId}/display", postId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("display 토글 — 존재하지 않는 게시글 → 404")
    void toggleDisplayMissingPost() throws Exception {
        String userId = fixtures.createActiveUser("토글미존재");

        mockMvc.perform(patch("/api/tripmate/posts/no-such-post/display")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비표시(display=false)로 토글된 게시글은 공개 목록에서 제외된다")
    void hiddenPostExcludedFromList() throws Exception {
        String owner = fixtures.createActiveUser("비표시작성자");
        String viewer = fixtures.createActiveUser("목록뷰어");
        String postId = createPost(owner, "곧 숨길 글");

        // 토글 → display=false
        mockMvc.perform(patch("/api/tripmate/posts/{postId}/display", postId)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_displayed").value(false));

        // 다른 유저의 공개 목록에서 해당 글이 보이지 않아야 한다
        mockMvc.perform(get("/api/tripmate/posts")
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").doesNotExist());
    }
}
