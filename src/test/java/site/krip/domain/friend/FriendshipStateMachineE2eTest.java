package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 친구 요청 상태머신 + 권한 가드 E2E (/api/friend/friendships).
 * FriendshipE2eTest 의 정상 흐름 밖 경계: 잘못된 당사자 403, 비-PENDING/비-ACCEPTED 전이 400, 차단 방향/미존재 400.
 */
@DisplayName("친구 상태 전이 — 권한·PENDING 전용·TOCTOU 방어")
class FriendshipStateMachineE2eTest extends IntegrationTestSupport {

    /** ACCEPTED 상태에서 거부돼야 하는 PENDING 전용 전이 — 엔드포인트/요청자(actor) 만 다르다. */
    private enum NonPendingTransition {
        재수락(true) {
            @Override MockHttpServletRequestBuilder request(String friendshipId) {
                return patch("/api/friend/friendships/requests/{id}/accept", friendshipId);
            }
        },
        거절(true) {
            @Override MockHttpServletRequestBuilder request(String friendshipId) {
                return patch("/api/friend/friendships/requests/{id}/reject", friendshipId);
            }
        },
        취소(false) {
            @Override MockHttpServletRequestBuilder request(String friendshipId) {
                return delete("/api/friend/friendships/requests/{id}", friendshipId);
            }
        };

        /** true=수신자(addressee), false=요청자(requester) 가 수행하는 전이. */
        final boolean byAddressee;

        NonPendingTransition(boolean byAddressee) {
            this.byAddressee = byAddressee;
        }

        abstract MockHttpServletRequestBuilder request(String friendshipId);
    }

    // ──────────────────── 권한 가드 (403) ────────────────────

    @Test
    @DisplayName("requester(권한 없음)가 거절 시도 → 403")
    void rejectByWrongParty() throws Exception {
        // given
        String a = fixtures.createActiveUser("거절권한A");
        String b = fixtures.createActiveUser("거절권한B");
        String friendshipId = sendFriendRequest(a, b);

        // 거절은 addressee(B) 만 가능 — requester(A) 가 시도 → 403
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/reject", friendshipId)
                        .with(auth(a)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("addressee(권한 없음)가 취소 시도 → 403")
    void cancelByNonRequester() throws Exception {
        // given
        String a = fixtures.createActiveUser("취소권한A");
        String b = fixtures.createActiveUser("취소권한B");
        String friendshipId = sendFriendRequest(a, b);

        // 취소는 requester(A) 만 가능 — addressee(B) 가 시도 → 403
        mockMvc.perform(delete("/api/friend/friendships/requests/{id}", friendshipId)
                        .with(auth(b)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("제3자가 친구 삭제 시도 → 403")
    void removeFriendByOutsider() throws Exception {
        // given
        String a = fixtures.createActiveUser("삭제당사자A");
        String b = fixtures.createActiveUser("삭제당사자B");
        String outsider = fixtures.createActiveUser("삭제제3자");
        String friendshipId = sendFriendRequest(a, b);
        acceptFriendRequest(b, friendshipId);

        // when & then
        mockMvc.perform(delete("/api/friend/friendships/{id}", friendshipId)
                        .with(auth(outsider)))
                .andExpect(status().isForbidden());
    }

    // ──────────────────── 상태머신 (400) ────────────────────

    @ParameterizedTest(name = "ACCEPTED 상태에서 {0} → 400")
    @EnumSource(NonPendingTransition.class)
    @DisplayName("이미 수락된(ACCEPTED) 요청의 재수락/거절/취소 → 400 (PENDING 전용 전이)")
    void nonPendingTransitionRejected(NonPendingTransition transition) throws Exception {
        // given
        String a = fixtures.createActiveUser("전이A");
        String b = fixtures.createActiveUser("전이B");
        String friendshipId = sendFriendRequest(a, b);
        acceptFriendRequest(b, friendshipId);

        String actor = transition.byAddressee ? b : a;

        // when & then
        mockMvc.perform(transition.request(friendshipId).with(auth(actor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("PENDING(미수락) 관계를 친구 삭제 시도 → 400 (ACCEPTED 만 삭제 가능)")
    void removePendingFriendship() throws Exception {
        // given
        String a = fixtures.createActiveUser("대기삭제A");
        String b = fixtures.createActiveUser("대기삭제B");
        String friendshipId = sendFriendRequest(a, b);

        // when & then
        mockMvc.perform(delete("/api/friend/friendships/{id}", friendshipId)
                        .with(auth(a)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ──────────────────── 차단 방향 / 미존재 ────────────────────

    @Test
    @DisplayName("상대가 나를 차단한 상태에서 친구 요청 → 400")
    void requestToUserWhoBlockedMe() throws Exception {
        // given
        String requester = fixtures.createActiveUser("차단당한요청자");
        String addressee = fixtures.createActiveUser("차단한상대");
        // addressee 가 requester 를 차단(내가 건 차단이 아닌 역방향)
        block(addressee, requester);

        // when & then
        mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", addressee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @ParameterizedTest(name = "addressee 가 차단자={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("TOCTOU 방어: PENDING 요청과 차단이 공존(경합 산물)하면 수락 → 400 (방향 무관)")
    void acceptRejectedWhenBlockCoexists(boolean addresseeBlocksRequester) throws Exception {
        // given
        String requester = fixtures.createActiveUser("수락차단요청자");
        String addressee = fixtures.createActiveUser("수락차단상대");
        String friendshipId = sendFriendRequest(requester, addressee);

        // 경합으로 friendship 이 정리되지 않은 채 차단만 들어간 상태를 직접 재현
        if (addresseeBlocksRequester) {
            block(addressee, requester);
        } else {
            block(requester, addressee);
        }

        // when & then
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .with(auth(addressee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("존재하지 않는 친구 요청 수락 → 404")
    void acceptMissingFriendship() throws Exception {
        // given
        String a = fixtures.createActiveUser("미존재수락자");

        // when & then
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", "no-such-friendship")
                        .with(auth(a)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 유저 차단 → 404")
    void blockNonexistentUser() throws Exception {
        // given
        String a = fixtures.createActiveUser("차단요청자");

        // when & then
        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", "no-such-user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }
}
