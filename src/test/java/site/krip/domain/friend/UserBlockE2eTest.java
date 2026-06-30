package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유저 차단/해제/목록 E2E (/api/friend/blocks).
 * 차단 시 기존 friendship(방향 무관) 정리 + 검색에서 양방향 제외. 관계는 실제 API(befriendViaApi)로 생성.
 */
@DisplayName("유저 차단 — 차단/해제·친구관계 제거·검색 제외")
class UserBlockE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("A 가 B 차단(201) → 친구관계 제거 → 차단목록 노출")
    void blockRemovesFriendshipAndListsBlock() throws Exception {
        // given
        String a = fixtures.createActiveUser("블로커앨리스");
        String b = fixtures.createActiveUser("타겟밥");

        befriendViaApi(a, b);

        // A 가 B 차단
        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", b)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.block_id").exists())
                .andExpect(jsonPath("$.blocked.user_id").value(b));

        // 친구 관계 제거됨(양쪽 모두)
        mockMvc.perform(get("/api/friend/friendships")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/friend/friendships")
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        // 차단 목록에 노출
        mockMvc.perform(get("/api/friend/blocks")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].blocked.user_id").value(b));
    }

    @Test
    @DisplayName("차단 시 검색에서 양방향 제외 → 해제 후 다시 노출")
    void blockExcludesFromSearchBothDirectionsThenUnblock() throws Exception {
        // given
        String a = fixtures.createActiveUser("검색차단A");
        String b = fixtures.createActiveUser("검색차단B");

        // 사전: 서로 검색 가능한지 확인
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(a))
                        .param("keyword", "검색차단B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].user_id").value(b));

        // A 가 B 차단
        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", b)))
                .andExpect(status().isCreated());

        // A 의 검색에서 B 제외(내가 차단한 유저)
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(a))
                        .param("keyword", "검색차단B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        // B 의 검색에서도 A 제외(나를 차단한 유저)
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(b))
                        .param("keyword", "검색차단A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        // A 가 차단 해제(DELETE)
        mockMvc.perform(delete("/api/friend/blocks/{targetUserId}", b)
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 해제 후 양방향 검색 재노출
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(a))
                        .param("keyword", "검색차단B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].user_id").value(b));
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(b))
                        .param("keyword", "검색차단A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].user_id").value(a));
    }

    @Test
    @DisplayName("차단된 상대에게 친구 요청 → 400")
    void cannotRequestBlockedUser() throws Exception {
        // given
        String a = fixtures.createActiveUser("차단요청A");
        String b = fixtures.createActiveUser("차단요청B");

        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", b)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("자기 자신 차단 → 400")
    void cannotBlockSelf() throws Exception {
        // given
        String a = fixtures.createActiveUser("자기차단");

        // when & then
        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", a)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("중복 차단 → 400")
    void duplicateBlock() throws Exception {
        // given
        String a = fixtures.createActiveUser("중복차단A");
        String b = fixtures.createActiveUser("중복차단B");

        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", b)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("2차 미완료(detail==null) 유저 차단 → 400 (목록 매퍼 NPE 방지)")
    void blockPreRegisterUserRejected() throws Exception {
        // given
        String a = fixtures.createActiveUser("차단시도A");
        String preRegister = fixtures.createPreRegisterUser();

        // when & then
        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", preRegister)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("차단 상태가 아닌데 해제 시도 → 404")
    void unblockWhenNotBlocked() throws Exception {
        // given
        String a = fixtures.createActiveUser("미차단A");
        String b = fixtures.createActiveUser("미차단B");

        // when & then
        mockMvc.perform(delete("/api/friend/blocks/{targetUserId}", b)
                        .with(auth(a)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }
}
