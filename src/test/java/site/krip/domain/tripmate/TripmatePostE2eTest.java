package site.krip.domain.tripmate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

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
class TripmatePostE2eTest extends IntegrationTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

    /** 유효한 게시글 생성 본문(snake_case) — 필드 일부를 덮어쓸 수 있도록 베이스를 제공. */
    private static String createBody(String title, String content, String region) {
        return """
                {
                  "title": "%s",
                  "content": "%s",
                  "preferred_age_min": 20,
                  "preferred_age_max": 35,
                  "preferred_gender": "any",
                  "region": "%s",
                  "travel_start_date": "2026-09-01",
                  "travel_end_date": "2026-09-07",
                  "companion_type": "friend",
                  "image_urls": []
                }
                """.formatted(title, content, region);
    }

    /** 게시글 생성 후 post_id 반환 (다른 테스트의 선행 데이터 준비용). */
    private String createPost(String userId, String title, String content, String region) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/tripmate/posts")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(title, content, region)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        return body.get("post_id").asText();
    }

    @Test
    @DisplayName("검색: 제목/내용에 없어도 작성자 닉네임 부분일치로 글이 검색된다")
    void searchByAuthorName() throws Exception {
        String author = fixtures.createActiveUser("김탐험가");
        String searcher = fixtures.createActiveUser("검색자");
        // 제목/내용에는 '탐험가' 가 없음 — 작성자 닉네임 분기로만 매칭되어야 한다.
        String postId = createPost(author, "여행 동행 모집", "제주 한 달 살기 같이 하실 분 찾습니다.", "제주");

        // 작성자 닉네임 부분일치 → 검색됨
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(searcher))
                        .param("keyword", "탐험가"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").exists());

        // 어디에도 없는 키워드 → 검색 안 됨
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(searcher))
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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("부산 같이 가실 분", "부산 여행 같이 떠나실 동행을 찾습니다.", "부산")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(userId))
                .andExpect(jsonPath("$.title").value("부산 같이 가실 분"))
                .andExpect(jsonPath("$.preferred_gender").value("any"))
                .andExpect(jsonPath("$.companion_type").value("friend"))
                .andExpect(jsonPath("$.is_displayed").value(true))
                .andExpect(jsonPath("$.post_id").exists())
                .andReturn();
        String postId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("post_id").asText();

        // 단건 (200)
        mockMvc.perform(get("/api/tripmate/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.title").value("부산 같이 가실 분"))
                .andExpect(jsonPath("$.like_count").value(0))
                .andExpect(jsonPath("$.is_liked").value(false));

        // 목록 (200, 커서 페이지네이션)
        mockMvc.perform(get("/api/tripmate/posts")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").exists());

        // 검색 (200)
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .param("keyword", "부산"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + postId + "')]").exists());

        // 수정 (200)
        String updateBody = createBody("부산 수정됨", "수정된 부산 여행 동행 모집 글입니다.", "부산")
                .replace("\"region\": \"부산\"", "\"region\": \"제주\"");
        mockMvc.perform(put("/api/tripmate/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("부산 수정됨"))
                .andExpect(jsonPath("$.region").value("제주"));

        // display 토글 (200) — true→false
        mockMvc.perform(patch("/api/tripmate/posts/" + postId + "/display")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.is_displayed").value(false));

        // 삭제 (200)
        mockMvc.perform(delete("/api/tripmate/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 삭제 후 단건 → 404
        mockMvc.perform(get("/api/tripmate/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 게시글 단건 조회 → 404")
    void getMissingPost() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get("/api/tripmate/posts/no-such-post")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 유저가 남의 글 수정 → 403")
    void updateByOtherUserForbidden() throws Exception {
        String author = fixtures.createActiveUser("원작성자");
        String other = fixtures.createActiveUser("타인");
        String postId = createPost(author, "원본 글", "원본 글 본문 내용입니다.", "서울");

        mockMvc.perform(put("/api/tripmate/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("뺏으려는 수정", "남의 글을 수정하려는 시도입니다.", "서울")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른 유저가 남의 글 삭제 → 403")
    void deleteByOtherUserForbidden() throws Exception {
        String author = fixtures.createActiveUser("원작성자2");
        String other = fixtures.createActiveUser("타인2");
        String postId = createPost(author, "삭제대상 글", "삭제 권한 테스트용 본문입니다.", "대구");

        mockMvc.perform(delete("/api/tripmate/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 게시글 수정 → 404")
    void updateMissingPost() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(put("/api/tripmate/posts/no-such-post")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("제목", "본문 내용은 충분히 길게 작성합니다.", "서울")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("제목 누락 등 유효성 위반 → 400")
    void createValidationError() throws Exception {
        String userId = fixtures.createActiveUser();
        // content 가 10자 미만 → @Size 위반
        String body = createBody("제목", "짧음", "서울");
        mockMvc.perform(post("/api/tripmate/posts")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("region 공백 → 400 (@NotBlank)")
    void createBlankRegion() throws Exception {
        String userId = fixtures.createActiveUser();
        String body = createBody("제목입니다", "충분히 긴 본문 내용을 작성합니다.", "   ");
        mockMvc.perform(post("/api/tripmate/posts")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잘못된 enum 값(companion_type) → 400")
    void createBadEnum() throws Exception {
        String userId = fixtures.createActiveUser();
        String body = createBody("제목입니다", "충분히 긴 본문 내용을 작성합니다.", "서울")
                .replace("\"companion_type\": \"friend\"", "\"companion_type\": \"bogus\"");
        mockMvc.perform(post("/api/tripmate/posts")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이 생성 → 401")
    void createUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/tripmate/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("제목입니다", "충분히 긴 본문 내용을 작성합니다.", "서울")))
                .andExpect(status().isUnauthorized());
    }
}
