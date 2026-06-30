package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import site.krip.domain.friend.adapter.FriendUserDataPurgeAdapter;
import site.krip.domain.tour.adapter.TourUserDataPurgeAdapter;
import site.krip.domain.tripmate.adapter.TripmateUserDataPurgeAdapter;
import site.krip.global.storage.ObjectStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 외부 PII purge 포트 격리 검증 — 한 도메인 포트가 실패해도 나머지 포트는 계속 실행되고(루프 try/catch),
 * 실패 시 작업 큐(doc)가 보존돼 PII 가 영구 누수되지 않는지 확인한다.
 */
@DisplayName("탈퇴 purge — 외부 포트 격리 실행·작업 큐 보존")
class WithdrawPurgePortIsolationIntegrationTest extends WithdrawPurgeTestSupport {

    @MockitoBean
    private ObjectStorage storage; // no-op 성공 — 실패원은 포트로 한정

    @MockitoBean
    private TripmateUserDataPurgeAdapter tripmatePurge;

    @MockitoBean
    private TourUserDataPurgeAdapter tourPurge;

    @MockitoBean
    private FriendUserDataPurgeAdapter friendPurge;

    @Test
    @DisplayName("외부 purge 포트 하나가 실패해도 나머지 포트는 격리돼 실행되고, 작업 큐(doc)는 보존된다")
    void portFailureIsolatedAndDocRetained() {
        String userId = fixtures.createActiveUser("포트격리");
        withdrawService.requestWithdraw(userId);
        makeDue(userId);

        doThrow(new RuntimeException("tripmate purge down"))
                .when(tripmatePurge).purgeUserMongoData(userId);

        withdrawService.purge(userId);

        // 한 포트 실패가 나머지 포트를 건너뛰지 않는다(격리).
        verify(tourPurge).purgeUserMongoData(userId);
        verify(friendPurge).purgeUserMongoData(userId);
        // 실패 → allCleaned=false → 유저는 hard delete 되더라도 doc 은 보존(재시도) → PII 영구 누수 차단.
        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(withdrawalDocExists(userId)).isTrue();
    }
}
