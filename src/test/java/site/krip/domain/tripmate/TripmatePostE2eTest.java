package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 게시글 CRUD E2E — 생성→단건→목록→검색→수정→display 토글→삭제→삭제후 404 의 핵심 흐름과
 * 권한/유효성 에러 케이스 검증. 경로: {@code /api/tripmate/posts}, 요청/응답 JSON snake_case.
 */
class TripmatePostE2eTest extends TripmateTestSupport {

    @Test
    @DisplayName("검색: 제목/내용에 없어도 작성자 닉네임 부분일치로 글이 검색된다")
    void searchByAuthorName() throws Exception {
        String author = fixtures.createActiveUser("김탐험가");
        String searcher = fixtures.createActiveUser("검색자");
        // 제목/내용에는 '탐험가' 가 없음 — 작성자 닉네임 분기로만 매칭되어야 한다.
        String postId = createPost(author, "여행 동행 모집", "제주 한 달 살기 같이 하실 분 찾습니다.", "제주");

        // 작성자 닉네임 부분일치 → 검색됨
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .with(auth(searcher))
                        .param("keyword", "탐험가"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").exists());

        // 어디에도 없는 키워드 → 검색 안 됨
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .with(auth(searcher))
                        .param("keyword", "존재하지않는키워드zzz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").doesNotExist());
    }

    @Test
    @DisplayName("생성→단건→목록→검색→수정→display 토글→삭제→삭제후 404 전체 흐름")
    void fullLifecycle() throws Exception {
        String userId = fixtures.createActiveUser("작성자");

        // 생성 (201)
        MvcResult created = mockMvc.perform(post("/api/tripmate/posts")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("부산 같이 가실 분", "부산 여행 같이 떠나실 동행을 찾습니다.", "부산")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(userId))
                .andExpect(jsonPath("$.title").value("부산 같이 가실 분"))
                .andExpect(jsonPath("$.preferred_gender").value("any"))
                .andExpect(jsonPath("$.companion_type").value("friend"))
                .andExpect(jsonPath("$.is_displayed").value(true))
                .andExpect(jsonPath("$.post_id").exists())
                .andReturn();
        String postId = idFrom(created, "post_id");

        // 단건 (200)
        mockMvc.perform(get("/api/tripmate/posts/{postId}", postId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.title").value("부산 같이 가실 분"))
                .andExpect(jsonPath("$.like_count").value(0))
                .andExpect(jsonPath("$.is_liked").value(false));

        // 목록 (200, 커서 페이지네이션)
        mockMvc.perform(get("/api/tripmate/posts")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").exists());

        // 검색 (200)
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .with(auth(userId))
                        .param("keyword", "부산"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").exists());

        // 수정 (200) — region 을 부산→제주 로 변경
        mockMvc.perform(put("/api/tripmate/posts/{postId}", postId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("부산 수정됨", "수정된 부산 여행 동행 모집 글입니다.", "제주")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("부산 수정됨"))
                .andExpect(jsonPath("$.region").value("제주"));

        // display 토글 (200) — true→false
        mockMvc.perform(patch("/api/tripmate/posts/{postId}/display", postId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.is_displayed").value(false));

        // 삭제 (200)
        mockMvc.perform(delete("/api/tripmate/posts/{postId}", postId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 삭제 후 단건 → 404
        mockMvc.perform(get("/api/tripmate/posts/{postId}", postId)
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 게시글 단건 조회 → 404")
    void getMissingPost() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get("/api/tripmate/posts/no-such-post")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 유저가 남의 글 수정 → 403")
    void updateByOtherUserForbidden() throws Exception {
        String author = fixtures.createActiveUser("원작성자");
        String other = fixtures.createActiveUser("타인");
        String postId = createPost(author, "원본 글", "원본 글 본문 내용입니다.", "서울");

        mockMvc.perform(put("/api/tripmate/posts/{postId}", postId)
                        .with(auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("뺏으려는 수정", "남의 글을 수정하려는 시도입니다.", "서울")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른 유저가 남의 글 삭제 → 403")
    void deleteByOtherUserForbidden() throws Exception {
        String author = fixtures.createActiveUser("원작성자2");
        String other = fixtures.createActiveUser("타인2");
        String postId = createPost(author, "삭제대상 글", "삭제 권한 테스트용 본문입니다.", "대구");

        mockMvc.perform(delete("/api/tripmate/posts/{postId}", postId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 게시글 수정 → 404")
    void updateMissingPost() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(put("/api/tripmate/posts/no-such-post")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("제목", "본문 내용은 충분히 길게 작성합니다.", "서울")))
                .andExpect(status().isNotFound());
    }

    /** 헬퍼(postBody) 접근을 위해 테스트 인스턴스를 받아 본문을 만든다. */
    @FunctionalInterface
    interface BodyFn {
        String of(TripmatePostE2eTest t);
    }

    @ParameterizedTest(name = "{0} → 400")
    @MethodSource("invalidPostBodies")
    @DisplayName("단일 필드 유효성 위반 → 400")
    void createInvalidBodyRejected(String label, BodyFn body) throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(post(POSTS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.of(this)))
                .andExpect(status().isBadRequest());
    }

    static Stream<Arguments> invalidPostBodies() {
        return Stream.of(
                arguments("content 10자 미만(@Size)", (BodyFn) t ->
                        t.postBody("제목", "짧음", "서울")),
                arguments("region 공백(@NotBlank)", (BodyFn) t ->
                        t.postBody("제목입니다", "충분히 긴 본문 내용을 작성합니다.", "   ")),
                arguments("companion_type 잘못된 enum", (BodyFn) t ->
                        t.postBody("제목입니다", "충분히 긴 본문 내용을 작성합니다.", "서울", "bogus", List.of())));
    }

    @Test
    @DisplayName("인증 없이 생성 → 401")
    void createUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/tripmate/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("제목입니다", "충분히 긴 본문 내용을 작성합니다.", "서울")))
                .andExpect(status().isUnauthorized());
    }
}
