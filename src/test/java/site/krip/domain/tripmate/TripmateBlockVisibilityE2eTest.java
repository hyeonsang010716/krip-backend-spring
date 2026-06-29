package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 차단/숨김 가시성 E2E ({@code /api/tripmate/posts}).
 *
 * <p>차단 관계(방향 무관) 작성자의 글은 목록·검색·단건에서 제외되고, 숨김(display=false) 글은
 * 작성자 본인만 단건 조회할 수 있다(나머지는 404 로 존재 은닉).
 */
class TripmateBlockVisibilityE2eTest extends TripmateTestSupport {

    /** 서울 지역 모집글 생성 후 post_id 반환. */
    private String createPost(String userId, String title, String content) throws Exception {
        return createPost(userId, title, content, "서울");
    }

    private void hide(String owner, String postId) throws Exception {
        mockMvc.perform(patch(POSTS + "/" + postId + "/display")
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_displayed").value(false));
    }

    @Test
    @DisplayName("목록: 차단 관계 작성자의 글은 양방향으로 제외된다")
    void listExcludesBlockedBothDirections() throws Exception {
        String a = fixtures.createActiveUser("tmbvListA");
        String b = fixtures.createActiveUser("tmbvListB");
        String postA = createPost(a, "A의 모집글", "A 가 올린 충분히 긴 본문입니다.");
        String postB = createPost(b, "B의 모집글", "B 가 올린 충분히 긴 본문입니다.");

        blockViaApi(a, b);

        // A 의 목록에 B 의 글이 없어야 한다
        mockMvc.perform(get(POSTS)
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postB + "')]").doesNotExist());

        // B 의 목록에도 A 의 글이 없어야 한다(차단은 방향 무관)
        mockMvc.perform(get(POSTS)
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postA + "')]").doesNotExist());
    }

    @Test
    @DisplayName("검색: 차단 관계 작성자의 글은 검색 결과에서 제외된다")
    void searchExcludesBlocked() throws Exception {
        String a = fixtures.createActiveUser("tmbvSearchA");
        String b = fixtures.createActiveUser("tmbvSearchB");
        String keyword = "유니크검색어ZZZ";
        String postB = createPost(b, keyword + " 동행", "검색에 걸릴 충분히 긴 본문입니다.");

        blockViaApi(a, b);

        mockMvc.perform(get(POSTS + "/search")
                        .with(auth(a))
                        .param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postB + "')]").doesNotExist());
    }

    @Test
    @DisplayName("단건: 차단 관계 작성자의 글 조회 → 404 (양방향)")
    void detailBlockedReturns404() throws Exception {
        String a = fixtures.createActiveUser("tmbvDetailA");
        String b = fixtures.createActiveUser("tmbvDetailB");
        String postA = createPost(a, "A 단건", "A 단건 본문 충분히 깁니다.");
        String postB = createPost(b, "B 단건", "B 단건 본문 충분히 깁니다.");

        blockViaApi(a, b);

        mockMvc.perform(get(POSTS + "/" + postB)
                        .with(auth(a)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(POSTS + "/" + postA)
                        .with(auth(b)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("단건: 숨김(display=false) 글은 타인 404, 작성자 본인은 200")
    void detailHiddenOnlyOwnerCanView() throws Exception {
        String owner = fixtures.createActiveUser("tmbvHideOwner");
        String other = fixtures.createActiveUser("tmbvHideOther");
        String postId = createPost(owner, "곧 숨길 단건", "숨김 처리할 단건 본문 충분히 깁니다.");

        hide(owner, postId);

        mockMvc.perform(get(POSTS + "/" + postId)
                        .with(auth(other)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(POSTS + "/" + postId)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.is_displayed").value(false));
    }

    @Test
    @DisplayName("회귀: 차단·숨김이 없으면 타인도 단건/목록에서 정상 조회된다")
    void noBlockNoHideStillVisible() throws Exception {
        String owner = fixtures.createActiveUser("tmbvOkOwner");
        String viewer = fixtures.createActiveUser("tmbvOkViewer");
        String postId = createPost(owner, "정상 노출 글", "정상적으로 노출되는 본문 충분히 깁니다.");

        mockMvc.perform(get(POSTS + "/" + postId)
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId));

        mockMvc.perform(get(POSTS)
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").exists());
    }

    @Test
    @DisplayName("좋아요: 차단 관계 작성자 글은 좋아요/likers 조회/취소 모두 404 (IDOR 차단)")
    void likesBlockedByVisibility() throws Exception {
        String owner = fixtures.createActiveUser("tmbvLikeBlkOwner");
        String viewer = fixtures.createActiveUser("tmbvLikeBlkViewer");
        String postId = createPost(owner, "차단 글", "차단 관계 좋아요 게이트 테스트 본문입니다.");
        blockViaApi(owner, viewer);

        mockMvc.perform(post(POSTS + "/" + postId + "/like")
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(POSTS + "/" + postId + "/likes")
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(POSTS + "/" + postId + "/like")
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("좋아요: 숨김(display=false) 글은 타인이 좋아요/likers 조회 불가 404")
    void likesHiddenPostBlocked() throws Exception {
        String owner = fixtures.createActiveUser("tmbvLikeHideOwner");
        String viewer = fixtures.createActiveUser("tmbvLikeHideViewer");
        String postId = createPost(owner, "숨김 글", "숨김 글 좋아요 게이트 테스트 본문입니다.");
        hide(owner, postId);

        mockMvc.perform(post(POSTS + "/" + postId + "/like")
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(POSTS + "/" + postId + "/likes")
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
    }
}
