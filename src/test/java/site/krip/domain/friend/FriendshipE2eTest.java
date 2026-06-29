package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 친구 요청/수락/거절/취소/삭제 E2E (/api/friend/friendships).
 * 모든 관계는 실제 API 로 생성. 응답 JSON 은 snake_case, status 는 소문자(pending/accepted/rejected).
 */
class FriendshipE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("친구 요청 생성 → 201, status=pending·is_requester=true·peer 노출")
    void createRequestReturnsPendingShape() throws Exception {
        String a = fixtures.createActiveUser("요청생성자");
        String b = fixtures.createActiveUser("요청대상");

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", b)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.is_requester").value(true))
                .andExpect(jsonPath("$.peer.user_id").value(b));
    }

    @Test
    @DisplayName("A→B 요청 → B 받은목록 노출 → B 수락 → 양쪽 친구목록 노출 → A stats friend=1")
    void fullAcceptFlow() throws Exception {
        String a = fixtures.createActiveUser("앨리스");
        String b = fixtures.createActiveUser("밥");

        String friendshipId = sendFriendRequest(a, b);

        // B 의 받은 요청 목록에 노출 (B 기준 is_requester=false, peer=A)
        mockMvc.perform(get("/api/friend/friendships/requests/received")
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].friendship_id").value(friendshipId))
                .andExpect(jsonPath("$.items[0].status").value("pending"))
                .andExpect(jsonPath("$.items[0].is_requester").value(false))
                .andExpect(jsonPath("$.items[0].peer.user_id").value(a));

        // A 의 보낸 요청 목록에도 노출
        mockMvc.perform(get("/api/friend/friendships/requests/sent")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].friendship_id").value(friendshipId))
                .andExpect(jsonPath("$.items[0].is_requester").value(true));

        // B 가 수락
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 양쪽 친구 목록에 ACCEPTED 로 노출
        mockMvc.perform(get("/api/friend/friendships")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].friendship_id").value(friendshipId))
                .andExpect(jsonPath("$.items[0].status").value("accepted"))
                .andExpect(jsonPath("$.items[0].peer.user_id").value(b));

        mockMvc.perform(get("/api/friend/friendships")
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].friendship_id").value(friendshipId))
                .andExpect(jsonPath("$.items[0].status").value("accepted"))
                .andExpect(jsonPath("$.items[0].peer.user_id").value(a));

        // A 의 프로필 통계 친구 수 = 1
        mockMvc.perform(get("/api/auth/profile/me/stats")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_friends").value(1));
    }

    @Test
    @DisplayName("친구 삭제 → 친구 목록 비고, stats friend=0")
    void deleteFriendship() throws Exception {
        String a = fixtures.createActiveUser("정한");
        String b = fixtures.createActiveUser("준");

        String friendshipId = sendFriendRequest(a, b);
        acceptFriendRequest(b, friendshipId);

        // A 가 친구 삭제
        mockMvc.perform(delete("/api/friend/friendships/{id}", friendshipId)
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/friend/friendships")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(get("/api/auth/profile/me/stats")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_friends").value(0));
    }

    @Test
    @DisplayName("B 가 요청 거절 → 양쪽 PENDING 목록에서 사라짐")
    void rejectRequest() throws Exception {
        String a = fixtures.createActiveUser("리쿠");
        String b = fixtures.createActiveUser("샤오");

        String friendshipId = sendFriendRequest(a, b);

        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/reject", friendshipId)
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // B 받은 목록 비고
        mockMvc.perform(get("/api/friend/friendships/requests/received")
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        // A 보낸 목록도 비고(REJECTED 는 PENDING 목록에 안 잡힘)
        mockMvc.perform(get("/api/friend/friendships/requests/sent")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("A 가 보낸 요청 취소 → B 받은목록 비고")
    void cancelRequest() throws Exception {
        String a = fixtures.createActiveUser("호시");
        String b = fixtures.createActiveUser("원우");

        String friendshipId = sendFriendRequest(a, b);

        mockMvc.perform(delete("/api/friend/friendships/requests/{id}", friendshipId)
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/friend/friendships/requests/received")
                        .with(auth(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("거절된 뒤 B→A 재요청 시 방향 전환(B 가 requester)")
    void reRequestAfterRejectDirectionSwitch() throws Exception {
        String a = fixtures.createActiveUser("디에잇");
        String b = fixtures.createActiveUser("민규");

        // A→B 요청 후 B 거절(REJECTED 로 잔존)
        String friendshipId = sendFriendRequest(a, b);
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/reject", friendshipId)
                        .with(auth(b)))
                .andExpect(status().isOk());

        // 이제 B 가 A 에게 재요청 → REJECTED 재오픈 + 방향 반전(B 가 requester)
        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", a)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.is_requester").value(true))
                .andExpect(jsonPath("$.peer.user_id").value(a));

        // A 의 받은 요청 목록에 노출(A 기준 is_requester=false)
        mockMvc.perform(get("/api/friend/friendships/requests/received")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].is_requester").value(false))
                .andExpect(jsonPath("$.items[0].peer.user_id").value(b));
    }

    @Test
    @DisplayName("자기 자신에게 요청 → 400")
    void selfRequestRejected() throws Exception {
        String a = fixtures.createActiveUser("승철");

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", a)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("PENDING 상태에서 중복 요청 → 400")
    void duplicatePendingRequest() throws Exception {
        String a = fixtures.createActiveUser("버논");
        String b = fixtures.createActiveUser("도겸");

        sendFriendRequest(a, b);

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("권한 없는 유저가 수락 → 403")
    void acceptWithoutPermission() throws Exception {
        String a = fixtures.createActiveUser("우지");
        String b = fixtures.createActiveUser("에스쿱스");

        String friendshipId = sendFriendRequest(a, b);

        // requester(A) 가 자기 요청을 수락 시도 → addressee 만 가능하므로 403
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .with(auth(a)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("addressee_id 누락 → 400")
    void missingAddresseeId() throws Exception {
        String a = fixtures.createActiveUser("디노");

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("2차 미완료(detail==null) 유저에게 친구 요청 → 400 (보낸목록 매퍼 NPE 방지)")
    void requestToPreRegisterUserRejected() throws Exception {
        String requester = fixtures.createActiveUser("요청자에코");
        String preRegister = fixtures.createPreRegisterUser();

        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", preRegister)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }
}
