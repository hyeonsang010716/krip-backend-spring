package site.krip.domain.auth;

import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.auth.document.WithdrawalRequest;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.auth.repository.WithdrawalRequestRepository;
import site.krip.domain.auth.service.WithdrawService;
import site.krip.support.IntegrationTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 회원 탈퇴 영구 삭제(purge) 통합 테스트 공통 베이스 — 서비스/리포지토리 와이어링과 due-doc 시드 헬퍼. */
abstract class WithdrawPurgeTestSupport extends IntegrationTestSupport {

    @Autowired
    protected WithdrawService withdrawService;

    @Autowired
    protected WithdrawalRequestRepository withdrawalRequestRepository;

    @Autowired
    protected UserRepository userRepository;

    /** 해당 유저의 탈퇴 요청 doc 존재 여부 (먼 미래 기준 findDue 로 조회). */
    protected boolean withdrawalDocExists(String userId) {
        Instant farFuture = Instant.now().plus(3650, ChronoUnit.DAYS);
        return withdrawalRequestRepository.findDue(farFuture).stream()
                .map(WithdrawalRequest::getUserId)
                .anyMatch(userId::equals);
    }

    /** purge_at 을 과거로 덮어 유예 만료(due) 상태로 만든다. */
    protected void makeDue(String userId) {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        withdrawalRequestRepository.upsert(userId, past, past);
    }
}
