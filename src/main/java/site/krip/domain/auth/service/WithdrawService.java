package site.krip.domain.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.exception.UserNotFoundException;
import site.krip.domain.auth.exception.WithdrawalAlreadyRequestedException;
import site.krip.domain.auth.exception.WithdrawalNotPendingException;
import site.krip.domain.auth.port.ExternalUserDataPurgePort;
import site.krip.domain.auth.port.InboxCascadePort;
import site.krip.domain.auth.port.UserPurgeCachePort;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.auth.repository.WithdrawalRequestRepository;
import site.krip.global.cache.RegisteredCacheManager;
import site.krip.global.config.WithdrawProperties;
import site.krip.global.storage.ObjectStorage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 회원 탈퇴 — 30일 유예 정책.
 *
 * <ul>
 *   <li>{@link #requestWithdraw} — INACTIVE 전환 + Mongo 적재 (soft)</li>
 *   <li>{@link #cancelWithdraw} — 유예 내 INACTIVE→ACTIVE 복구</li>
 *   <li>{@link #purge} — 스케줄러 hard delete (RDB CASCADE + 외부 리소스)</li>
 * </ul>
 *
 * cancel ↔ purge 는 {@code findByIdForUpdate} row lock 으로 상호배타. 트랜잭션 구간은
 * self-invocation 프록시 한계를 피해 {@link TransactionTemplate} 으로 연다.
 */
@Service
public class WithdrawService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawService.class);

    /** {@code _purge_rdb} 결과 — 후속 분기 결정용. */
    public enum PurgeOutcome {
        DELETED,    // INACTIVE → hard delete 완료 → 외부 정리 진행
        NO_USER,    // RDB 에 user 없음 (이전 사이클 잔존) → 외부 정리 진행
        STALE_DOC   // status != INACTIVE (cancel 복구/dual-write) → doc 만 청소
    }

    private final UserRepository userRepository;
    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final ObjectStorage storage;
    private final RegisteredCacheManager registeredCache;
    private final UserPurgeCachePort chatPurge;
    private final InboxCascadePort inboxCascade;
    // 외부 도메인(tripmate/friend/...)의 Mongo 유저 데이터 정리 어댑터 — 도메인별 1개씩 등록되어 모두 호출된다.
    private final List<ExternalUserDataPurgePort> externalPurges;
    private final TransactionTemplate txTemplate;
    private final int graceDays;

    public WithdrawService(UserRepository userRepository,
                           WithdrawalRequestRepository withdrawalRequestRepository,
                           ObjectStorage storage,
                           RegisteredCacheManager registeredCache,
                           UserPurgeCachePort chatPurge,
                           InboxCascadePort inboxCascade,
                           List<ExternalUserDataPurgePort> externalPurges,
                           PlatformTransactionManager txManager,
                           WithdrawProperties props) {
        this.userRepository = userRepository;
        this.withdrawalRequestRepository = withdrawalRequestRepository;
        this.storage = storage;
        this.registeredCache = registeredCache;
        this.chatPurge = chatPurge;
        this.inboxCascade = inboxCascade;
        this.externalPurges = externalPurges;
        this.txTemplate = new TransactionTemplate(txManager);
        this.graceDays = props.gracePeriodDays();
    }

    // ──────────────────── HTTP: 탈퇴 요청 (soft) ────────────────────

    /**
     * 탈퇴 요청 — INACTIVE 전환 + MongoDB 적재.
     *
     * @return 영구 삭제 예정 시각 (UTC). 호출자(컨트롤러)는 commit 이후 캐시 무효화 + chat revoke 수행.
     */
    @Transactional
    public Instant requestWithdraw(String userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        if (user.getStatus() == UserStatus.INACTIVE) {
            throw new WithdrawalAlreadyRequestedException();
        }

        user.changeStatus(UserStatus.INACTIVE);

        Instant now = Instant.now();
        Instant purgeAt = now.plus(graceDays, ChronoUnit.DAYS);
        withdrawalRequestRepository.upsert(userId, now, purgeAt);

        log.info("탈퇴 요청 접수 (user_id={}, purge_at={})", userId, purgeAt);
        return purgeAt;
    }

    /** request_withdraw post-commit 훅 — chat 활성 세션 즉시 종료. */
    public void revokeUserChatState(String userId) {
        chatPurge.revokeAllSessions(userId);
    }

    // ──────────────────── 스케줄러: 영구 삭제 (hard) ────────────────────

    public void purge(String userId) {
        PurgeOutcome outcome = purgeRdb(userId);

        if (outcome == PurgeOutcome.STALE_DOC) {
            log.warn("탈퇴 영구 삭제 — RDB status 가 INACTIVE 아님, 외부 리소스 보존 + stale doc 만 정리 (user_id={})",
                    userId);
            try {
                withdrawalRequestRepository.deleteByUserId(userId);
            } catch (Exception e) {
                log.error("탈퇴 영구 삭제 — stale doc 정리 실패, 다음 tick 재시도 (user_id={}): {}", userId, e.toString());
            }
            return;
        }

        purgeExternal(userId);
    }

    /** RDB row lock → status 검사 → 조건부 hard delete. 단일 트랜잭션. */
    private PurgeOutcome purgeRdb(String userId) {
        return txTemplate.execute(status -> {
            User user = userRepository.findByIdForUpdate(userId).orElse(null);
            if (user == null) {
                log.info("탈퇴 영구 삭제 — RDB 에 user 없음, 외부 리소스 정리만 진행 (user_id={})", userId);
                return PurgeOutcome.NO_USER;
            }
            if (user.getStatus() != UserStatus.INACTIVE) {
                return PurgeOutcome.STALE_DOC;
            }
            userRepository.hardDeleteById(userId);
            log.info("탈퇴 영구 삭제 — RDB 삭제 완료 (user_id={})", userId);
            return PurgeOutcome.DELETED;
        });
    }

    // ──────────────────── HTTP: 탈퇴 취소 (soft 복구) ────────────────────

    public void cancelWithdraw(String userId) {
        setActive(userId);

        // post-commit Mongo doc 청소. 실패해도 worker STALE_DOC 가드가 다음 사이클에서 정리.
        try {
            withdrawalRequestRepository.deleteByUserId(userId);
        } catch (Exception e) {
            log.warn("탈퇴 취소 — Mongo doc 정리 실패 (status 는 이미 ACTIVE), 다음 worker 사이클 정리 (user_id={}): {}",
                    userId, e.toString());
        }
        log.info("탈퇴 요청 취소 (user_id={})", userId);
    }

    /** RDB 만 ACTIVE 복구 (row lock). Mongo doc 정리는 호출자가 commit 후 처리. */
    private void setActive(String userId) {
        txTemplate.executeWithoutResult(status -> {
            User user = userRepository.findByIdForUpdate(userId).orElseThrow(UserNotFoundException::new);
            if (user.getStatus() != UserStatus.INACTIVE) {
                throw new WithdrawalNotPendingException(user.getStatus().getValue());
            }
            user.changeStatus(UserStatus.ACTIVE);
        });
    }

    // ──────────────────── 외부 리소스 정리 (best-effort) ────────────────────

    private void purgeExternal(String userId) {
        // 도메인별 어댑터를 독립 best-effort 로 호출 — 한 도메인 실패가 다른 도메인 정리를 막지 않는다.
        for (ExternalUserDataPurgePort purge : externalPurges) {
            try {
                purge.purgeUserMongoData(userId);
            } catch (Exception e) {
                log.error("탈퇴 영구 삭제 — 외부 도메인 Mongo 삭제 실패, orphan 정리 필요 (port={}, user_id={}): {}",
                        purge.getClass().getSimpleName(), userId, e.toString());
            }
        }
        log.info("탈퇴 영구 삭제 — 외부 도메인 MongoDB 정리 완료 ({}개 어댑터, user_id={})",
                externalPurges.size(), userId);

        inboxCascade.cascadeUserWithdrawn(userId); // self-swallow

        try {
            storage.deleteByPrefix(userId);
            log.info("탈퇴 영구 삭제 — Object Storage 삭제 완료 (user_id={})", userId);
        } catch (Exception e) {
            log.error("탈퇴 영구 삭제 — Object Storage 삭제 실패 (user_id={}): {}", userId, e.toString());
        }

        registeredCache.invalidate(userId);
        chatPurge.cleanupUserData(userId);

        try {
            withdrawalRequestRepository.deleteByUserId(userId);
        } catch (Exception e) {
            log.error("탈퇴 영구 삭제 — withdrawal_request doc 삭제 실패, 다음 tick 재시도 (user_id={}): {}",
                    userId, e.toString());
        }
    }
}
