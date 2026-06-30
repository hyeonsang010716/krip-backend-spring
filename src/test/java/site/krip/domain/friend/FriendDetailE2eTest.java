package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 친구 상세 조회 에러/관계상태 E2E (/api/friend/detail/{userId}).
 * FriendSearchE2eTest 가 무관계/PENDING(요청자)을 다루고, 본 테스트는 나머지 경계: 404/400/ACCEPTED/요청 수신(is_requester=false).
 */
@DisplayName("친구 상세 — 관계 상태 노출·거절 마스킹·차단 방향별 처리")
class FriendDetailE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("존재하지 않는 유저 상세 → 404")
    void detailUserNotFound() throws Exception {
        String viewer = fixtures.createActiveUser("상세404뷰어");

        mockMvc.perform(get("/api/friend/detail/{userId}", "no-such-user")
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("2차 회원가입 미완료(detail 없음) 유저 상세 → 400")
    void detailIncompleteProfile() throws Exception {
        String viewer = fixtures.createActiveUser("상세400뷰어");
        String preRegister = fixtures.createPreRegisterUser();

        mockMvc.perform(get("/api/friend/detail/{userId}", preRegister)
                        .with(auth(viewer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("상세: ACCEPTED 친구 → friendship_status=accepted")
    void detailAcceptedRelation() throws Exception {
        String viewer = fixtures.createActiveUser("상세친구뷰어");
        String target = fixtures.createActiveUser("상세친구타겟");
        String friendshipId = sendFriendRequest(viewer, target);
        acceptFriendRequest(target, friendshipId);

        mockMvc.perform(get("/api/friend/detail/{userId}", target)
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(target))
                .andExpect(jsonPath("$.friendship_status").value("accepted"))
                .andExpect(jsonPath("$.friendship_id").value(friendshipId))
                .andExpect(jsonPath("$.is_requester").value(true));
    }

    @Test
    @DisplayName("상세: 요청을 받은 시점 → is_requester=false")
    void detailPendingAsAddressee() throws Exception {
        String requester = fixtures.createActiveUser("상세받은요청자");
        String addressee = fixtures.createActiveUser("상세받은당사자");
        sendFriendRequest(requester, addressee);

        // addressee 가 requester 의 상세를 보면 is_requester=false (내가 요청자가 아님)
        mockMvc.perform(get("/api/friend/detail/{userId}", requester)
                        .with(auth(addressee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(requester))
                .andExpect(jsonPath("$.friendship_status").value("pending"))
                .andExpect(jsonPath("$.is_requester").value(false));
    }

    @Test
    @DisplayName("상세: 거절(REJECTED) 관계 → 관계 없음으로 마스킹(status/id/is_requester=null)")
    void detailRejectedRelationMasked() throws Exception {
        String viewer = fixtures.createActiveUser("상세거절뷰어");
        String target = fixtures.createActiveUser("상세거절타겟");
        String friendshipId = sendFriendRequest(viewer, target);
        // addressee(target) 가 거절 → REJECTED 는 재요청으로 되살아나는 상태라 노출하면 안 됨
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/reject", friendshipId)
                        .with(auth(target)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/friend/detail/{userId}", target)
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(target))
                .andExpect(jsonPath("$.friendship_status").value(nullValue()))
                .andExpect(jsonPath("$.friendship_id").value(nullValue()))
                .andExpect(jsonPath("$.is_requester").value(nullValue()));
    }

    @Test
    @DisplayName("상세: 상대가 나를 차단(peer→viewer)하면 존재 은닉 → 404")
    void detailHiddenWhenPeerBlockedViewer() throws Exception {
        String viewer = fixtures.createActiveUser("피차단뷰어");
        String peer = fixtures.createActiveUser("차단한상대");
        block(peer, viewer); // peer 가 viewer 를 차단

        mockMvc.perform(get("/api/friend/detail/{userId}", peer)
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("상세: 내가 상대를 차단(viewer→peer)해도 상세는 노출 + i_blocked_peer=true (차단 해제 동선)")
    void detailVisibleWhenViewerBlockedPeer() throws Exception {
        String viewer = fixtures.createActiveUser("차단한뷰어");
        String peer = fixtures.createActiveUser("내가차단한상대");
        block(viewer, peer); // viewer 가 peer 를 차단

        mockMvc.perform(get("/api/friend/detail/{userId}", peer)
                        .with(auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(peer))
                .andExpect(jsonPath("$.i_blocked_peer").value(true));
    }
}
