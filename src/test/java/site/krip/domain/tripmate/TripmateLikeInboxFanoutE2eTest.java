package site.krip.domain.tripmate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 좋아요 → notification 인박스 fan-out E2E (소스 도메인 관점).
 * 외부 유저 좋아요는 게시자 인박스로 fan-out 되고, 본인 좋아요는 skip 되는지 검증한다.
 */
class TripmateLikeInboxFanoutE2eTest extends IntegrationTestSupport {

    private final ObjectMapper om = new ObjectMapper();

    private static String createBody() {
        return """
                {
                  "title": "좋아요 fan-out 테스트",
                  "content": "인박스 fan-out 검증을 위한 게시글 본문입니다.",
                  "preferred_age_min": 20,
                  "preferred_age_max": 40,
                  "preferred_gender": "any",
                  "region": "서울",
                  "travel_start_date": "2026-10-01",
                  "travel_end_date": "2026-10-05",
                  "companion_type": "friend",
                  "image_urls": []
                }
                """;
    }

    private String createPost(String userId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/tripmate/posts")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("post_id").asText();
    }

    private void like(String liker, String postId) throws Exception {
        mockMvc.perform(post("/api/tripmate/posts/" + postId + "/like")
                        .with(auth(liker)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("외부 유저 좋아요 → 게시자 인박스에 tripmate_like 1건")
    void externalLikeCreatesInboxItem() throws Exception {
        String owner = fixtures.createActiveUser("트립주인");
        String liker = fixtures.createActiveUser("트립좋아요러");
        String post = createPost(owner);

        like(liker, post);

        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='tripmate_like')]", hasSize(1)))
                .andExpect(jsonPath("$.items[0].actor_id").value(liker))
                .andExpect(jsonPath("$.items[0].target_id").value(post));
    }

    @Test
    @DisplayName("본인 게시물 좋아요 → 인박스 생성 없음(self-skip)")
    void selfLikeCreatesNoInboxItem() throws Exception {
        String owner = fixtures.createActiveUser("트립자기좋아요");
        String post = createPost(owner);

        like(owner, post);

        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}
