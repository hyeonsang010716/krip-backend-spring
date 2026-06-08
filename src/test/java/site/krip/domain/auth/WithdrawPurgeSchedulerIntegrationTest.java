package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import site.krip.domain.auth.document.WithdrawalRequest;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.auth.repository.WithdrawalRequestRepository;
import site.krip.domain.auth.service.WithdrawService;
import site.krip.domain.auth.worker.WithdrawPurgeScheduler;
import site.krip.support.FakeStorageConfig;
import site.krip.support.IntegrationTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 탈퇴 영구 삭제(GDPR hard delete) 통합 테스트 — 스케줄러 선택 + purge 분기.
 *
 * <p>외부 정리(스토리지/외부도메인 Mongo/캐시)는 best-effort try/catch 라 실 빈 그대로 결정적으로 검증된다.
 * 커버: DELETED(due+INACTIVE→하드딜리트) / STALE_DOC(ACTIVE→유저 보존, doc 만 청소) /
 * 스케줄러 due 선택(유예 내 유저는 미삭제).
 *
 * <p>외부 정리가 모두 성공해야 작업 큐(doc)가 제거되므로, 결정적 성공 경로를 위해 {@link FakeStorageConfig}
 * 로 스토리지를 주입한다(실 S3 빈은 테스트 환경에서 자격증명 부재로 실패 → doc 보존됨). 실패→보존→재시도
 * 경로는 별도 테스트가 검증한다.
 */
@Import(FakeStorageConfig.class)
class WithdrawPurgeSchedulerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private WithdrawService withdrawService;

    @Autowired
    private WithdrawPurgeScheduler scheduler;

    @Autowired
    private WithdrawalRequestRepository withdrawalRequestRepository;

    @Autowired
    private UserRepository userRepository;

    /** 해당 유저의 탈퇴 요청 doc 존재 여부 (먼 미래 기준 findDue 로 조회). */
    private boolean withdrawalDocExists(String userId) {
        Instant farFuture = Instant.now().plus(3650, ChronoUnit.DAYS);
        return withdrawalRequestRepository.findDue(farFuture).stream()
                .map(WithdrawalRequest::getUserId)
                .anyMatch(userId::equals);
    }

    @Test
    @DisplayName("DELETED — purge_at 지난 INACTIVE 유저 → RDB hard delete + doc 청소")
    void purgeDeletesDueInactiveUser() {
        String userId = fixtures.createActiveUser("퍼지대상");
        withdrawService.requestWithdraw(userId); // INACTIVE + doc
        // 유예 만료를 시뮬레이션 — purge_at 을 과거로 덮어 due 로 만든다.
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        withdrawalRequestRepository.upsert(userId, past, past);

        withdrawService.purge(userId);

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(withdrawalDocExists(userId)).isFalse();
    }

    @Test
    @DisplayName("STALE_DOC — due doc 이 있어도 status 가 ACTIVE 면 유저 보존, doc 만 청소")
    void purgeSkipsActiveUserButCleansStaleDoc() {
        String userId = fixtures.createActiveUser("취소후잔존");
        // 유저는 ACTIVE 유지(취소된 상태) + 잔존하는 due doc 을 직접 주입.
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        withdrawalRequestRepository.upsert(userId, past, past);

        withdrawService.purge(userId);

        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(userRepository.findById(userId).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(withdrawalDocExists(userId)).isFalse();
    }

    @Test
    @DisplayName("스케줄러 — due 유저만 삭제, 유예 기간 내 유저는 보존")
    void schedulerPurgesOnlyDueUsers() {
        String dueUser = fixtures.createActiveUser("due유저");
        withdrawService.requestWithdraw(dueUser);
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        withdrawalRequestRepository.upsert(dueUser, past, past); // due

        String graceUser = fixtures.createActiveUser("유예유저");
        withdrawService.requestWithdraw(graceUser); // purge_at = now + 30d → not due

        int processed = scheduler.purgeDueWithdrawalsOnce();

        assertThat(processed).isGreaterThanOrEqualTo(1);
        assertThat(userRepository.findById(dueUser)).isEmpty();             // due → 삭제
        assertThat(userRepository.findById(graceUser)).isPresent();         // 유예 내 → 보존
        assertThat(userRepository.findById(graceUser).orElseThrow().getStatus())
                .isEqualTo(UserStatus.INACTIVE);
    }
}
