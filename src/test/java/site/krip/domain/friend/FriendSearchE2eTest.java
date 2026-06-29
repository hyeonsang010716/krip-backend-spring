package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 친구 검색/검색기록/상세 E2E — 검색(ILIKE, 본인/차단 제외, 기록 저장), 검색기록(목록/삭제, Mongo), 상세(공개 프로필 + 관계).
 */
class FriendSearchE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("user_name 부분일치로 검색 → 해당 유저 노출")
    void searchByName() throws Exception {
        String viewer = fixtures.createActiveUser("검색뷰어");
        String target = fixtures.createActiveUser("코스모스타겟");
        fixtures.createActiveUser("관계없는유저");

        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", "코스모스"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].user_id").value(target))
                .andExpect(jsonPath("$.items[0].user_name").value("코스모스타겟"));
    }

    @Test
    @DisplayName("user_id 부분일치로 검색 → 해당 유저 노출")
    void searchById() throws Exception {
        String viewer = fixtures.createActiveUser("아이디뷰어");
        String target = fixtures.createActiveUser("아이디타겟");

        // 전체 user_id(UUID)로 검색 → ILIKE 가 user_id 컬럼에 매칭(공유 DB 충돌 회피 위해 풀 ID 사용)
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].user_id", hasItem(target)));
    }

    @Test
    @DisplayName("검색 결과는 본인을 제외한다")
    void searchExcludesSelf() throws Exception {
        String viewer = fixtures.createActiveUser("자기검색유저");

        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", "자기검색유저"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("PENDING 친구요청이 있으면 검색결과에 friendship_status/is_requester 노출")
    void searchShowsRelationStatus() throws Exception {
        String viewer = fixtures.createActiveUser("관계뷰어");
        String target = fixtures.createActiveUser("관계타겟유저");

        // viewer → target 요청
        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", target)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", "관계타겟유저"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].user_id").value(target))
                .andExpect(jsonPath("$.items[0].friendship_status").value("pending"))
                .andExpect(jsonPath("$.items[0].is_requester").value(true));
    }

    @Test
    @DisplayName("빈 keyword → 400")
    void searchEmptyKeyword() throws Exception {
        String viewer = fixtures.createActiveUser("빈검색유저");

        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("100자 초과 keyword → 400 (쿼리 DoS 방지 바운드)")
    void searchOverLongKeyword() throws Exception {
        String viewer = fixtures.createActiveUser("긴검색유저");

        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", "가".repeat(101)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검색 기록: 검색 후 목록 노출 → 단건 삭제 → 전체 삭제")
    void searchHistoryFlow() throws Exception {
        String viewer = fixtures.createActiveUser("기록뷰어");
        fixtures.createActiveUser("히스토리대상A");
        fixtures.createActiveUser("히스토리대상B");

        // 두 번 검색(첫 페이지 → 기록 저장)
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", "히스토리대상A"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(viewer))
                        .param("keyword", "히스토리대상B"))
                .andExpect(status().isOk());

        // 목록 노출(최신순, created_at DESC) — 두 검색어 모두 포함
        mockMvc.perform(get("/api/friend/search/history")
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories.length()").value(2))
                .andExpect(jsonPath("$.histories[*].search_name",
                        hasItems("히스토리대상A", "히스토리대상B")));

        // 단건 삭제
        mockMvc.perform(delete("/api/friend/search/history/one")
                        .with(auth(viewer))
                        .param("search_name", "히스토리대상B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/friend/search/history")
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories.length()").value(1))
                .andExpect(jsonPath("$.histories[0].search_name").value("히스토리대상A"));

        // 전체 삭제
        mockMvc.perform(delete("/api/friend/search/history")
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/friend/search/history")
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories").isEmpty());
    }

    @Test
    @DisplayName("상세: 관계 없는 상대 → 공개 프로필 + friendship_status null")
    void detailNoRelation() throws Exception {
        String viewer = fixtures.createActiveUser("상세뷰어");
        String target = fixtures.createActiveUser("상세타겟");

        mockMvc.perform(get("/api/friend/detail/{userId}", target)
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(target))
                .andExpect(jsonPath("$.user_name").value("상세타겟"))
                .andExpect(jsonPath("$.friendship_status").value(nullValue()))
                .andExpect(jsonPath("$.friendship_id").value(nullValue()))
                .andExpect(jsonPath("$.i_blocked_peer").value(false));
    }

    @Test
    @DisplayName("상세: PENDING 요청 상대 → friendship_id/status/is_requester 노출")
    void detailWithPendingRequest() throws Exception {
        String viewer = fixtures.createActiveUser("상세관계뷰어");
        String target = fixtures.createActiveUser("상세관계타겟");

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", target)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/friend/detail/{userId}", target)
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(target))
                .andExpect(jsonPath("$.friendship_id").exists())
                .andExpect(jsonPath("$.friendship_status").value("pending"))
                .andExpect(jsonPath("$.is_requester").value(true));
    }
}
