package site.krip.domain.tripmate;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate E2E 공통 베이스 — 모집글 생성 본문/요청 헬퍼를 모은다.
 * 본문 포맷은 음수 테스트의 {@code .replace(...)} 와 호환되도록 필드 형식을 고정한다.
 */
abstract class TripmateTestSupport extends IntegrationTestSupport {

    protected static final String POSTS = "/api/tripmate/posts";

    /** canonical 모집글 본문 (image_urls=[]). */
    protected String postBody(String title, String content, String region) {
        return postBody(title, content, region, "[]");
    }

    /** image_urls 까지 지정하는 본문. imageUrlsJson 예: {@code "[]"}, {@code "[\"https://...\"]"}. */
    protected String postBody(String title, String content, String region, String imageUrlsJson) {
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
                  "image_urls": %s
                }
                """.formatted(title, content, region, imageUrlsJson);
    }

    /** 기본 본문으로 모집글 생성 — 글 내용이 무관한(좋아요 등) 테스트용. */
    protected String createPost(String userId) throws Exception {
        return createPost(userId, "동행 모집", "여행을 함께할 동행을 찾는 충분히 긴 본문입니다.", "서울");
    }

    /** canonical 본문으로 모집글 생성(201) 후 post_id 반환. */
    protected String createPost(String userId, String title, String content, String region) throws Exception {
        return createPostRaw(userId, postBody(title, content, region));
    }

    /** 임의 본문(JSON 문자열)으로 모집글 생성(201) 후 post_id 반환. */
    protected String createPostRaw(String userId, String body) throws Exception {
        MvcResult res = mockMvc.perform(post(POSTS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(res, "post_id");
    }
}
