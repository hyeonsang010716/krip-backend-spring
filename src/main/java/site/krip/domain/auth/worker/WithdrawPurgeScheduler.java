package site.krip.domain.auth.worker;

import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.krip.domain.auth.document.WithdrawalRequest;
import site.krip.domain.auth.repository.WithdrawalRequestRepository;
import site.krip.domain.auth.service.WithdrawService;
import site.krip.global.support.MdcTaskDecorator;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 회원 탈퇴 영구 삭제 스케줄러.
 *
 * <p>매일 KST 04:00 ({@code krip.withdraw.purge-cron}) 에 {@code scheduled_purge_at <= now} 인
 * 요청을 1명씩 삭제한다. 한 유저의 실패는 격리되며 {@link WithdrawService#purge} 가 멱등이라 재시도 안전.
 *
 * <p>안전장치:
 * <ul>
 *   <li>유저별 타임아웃 — 단일 purge 가 무한 대기해도 사이클이 멈추지 않게 {@link #PURGE_PER_USER_TIMEOUT_SEC}
 *       초로 격리. 초과 시 인터럽트 후 다음 유저로 진행.</li>
 *   <li>graceful shutdown — 종료 시 {@link #PURGE_SHUTDOWN_GRACE_SEC} 초 유예 후 강제 종료.</li>
 * </ul>
 * cached 풀: 인터럽트 무반응 task 가 스레드를 점유해도 다음 유저는 새 스레드로 진행돼 사이클이 지속된다.
 */
@Component
@Slf4j
public class WithdrawPurgeScheduler {

    /** 단일 유저 purge 격리 타임아웃 — 외부 리소스 지연으로 사이클 전체가 무한 대기되는 것을 방지. */
    private static final long PURGE_PER_USER_TIMEOUT_SEC = 30 * 60;

    /** shutdown 시 실행자가 종료되길 기다리는 최대 유예. */
    private static final long PURGE_SHUTDOWN_GRACE_SEC = 30;

    private final WithdrawalRequestRepository requestRepository;
    private final WithdrawService withdrawService;
    private final Clock clock;

    /**
     * 유저별 purge 를 실행하고 타임아웃을 거는 전용 실행자. cached 풀(0~N, 60s keep-alive, 데몬 스레드) —
     * 평시엔 스레드 1개로 순차 처리되지만, 인터럽트 무반응 task 가 스레드를 점유하면 새 스레드를 띄워 사이클 지속.
     */
    private final ExecutorService purgeExecutor;

    public WithdrawPurgeScheduler(WithdrawalRequestRepository requestRepository,
                                  WithdrawService withdrawService,
                                  Clock clock) {
        this.requestRepository = requestRepository;
        this.withdrawService = withdrawService;
        this.clock = clock;
        this.purgeExecutor = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new PurgeThreadFactory());
    }

    /**
     * 멀티 노드에서 단 한 노드만 실행 (ShedLock). lockAtMostFor=2h: 사이클이 길어도(유저별 30분 격리×N)
     * 다른 노드가 끼어들지 않도록 충분히 길게, 단 노드가 죽으면 2h 후 락 자동 해제.
     * lockAtLeastFor=1m: 처리 대상이 0이라 즉시 끝나도 최소 1분 락 유지 → 동시 fire 시 중복 진입 차단.
     */
    @Scheduled(cron = "${krip.withdraw.purge-cron}", zone = "${krip.withdraw.purge-zone}")
    @SchedulerLock(name = "withdrawPurge", lockAtMostFor = "2h", lockAtLeastFor = "1m")
    public void run() {
        purgeDueWithdrawalsOnce();
    }

    /** 한 사이클 — 실제 처리한 수(성공+실패) 반환. 조기 중단 시 전체 대상보다 적을 수 있다. */
    public int purgeDueWithdrawalsOnce() {
        Instant now = clock.instant();
        List<WithdrawalRequest> due = requestRepository.findDue(now);

        if (due.isEmpty()) {
            log.info("withdraw purge: 처리 대상 없음 (now={})", now);
            return 0;
        }

        log.info("withdraw purge: 사이클 시작 — 대상 {} 명", due.size());

        int succeeded = 0;
        int failed = 0;
        for (WithdrawalRequest req : due) {
            String userId = req.getUserId();
            // 매 유저마다 타임아웃을 걸어 격리 실행 — 한 유저의 외부 리소스 지연이 사이클 전체를 멈추지 않게.
            Future<?> future;
            try {
                future = purgeExecutor.submit(MdcTaskDecorator.wrap(() -> withdrawService.purge(userId)));
            } catch (RejectedExecutionException e) {
                // 셧다운으로 실행자가 이미 종료됨 — 남은 유저는 다음 사이클에서 재시도(purge 멱등).
                log.warn("withdraw purge: 실행자 종료로 사이클 중단 — 처리됨 {} / 전체 {} (다음 사이클 재시도)",
                        succeeded + failed, due.size());
                break;
            }
            try {
                future.get(PURGE_PER_USER_TIMEOUT_SEC, TimeUnit.SECONDS);
                succeeded++;
            } catch (TimeoutException e) {
                failed++;
                future.cancel(true); // 인터럽트 — 협조적이라 즉시 멈추지 않을 수 있으나 다음 유저는 새 스레드로 진행.
                log.error("withdraw purge: 유저 처리 타임아웃 (user_id={}, timeout={}s)",
                        userId, PURGE_PER_USER_TIMEOUT_SEC);
            } catch (ExecutionException e) {
                failed++;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("withdraw purge: 유저 처리 실패 (user_id={}): {}", userId, cause.toString(), cause);
            } catch (InterruptedException e) {
                // 워커 자체가 인터럽트 — 셧다운 신호로 보고 현재 사이클 중단.
                future.cancel(true);
                Thread.currentThread().interrupt();
                log.warn("withdraw purge: 사이클 중단(인터럽트) — 처리됨 {} / 전체 {}", succeeded + failed, due.size());
                break;
            }
        }

        log.info("withdraw purge: 사이클 완료 — 성공 {} / 실패 {} / 전체 {}", succeeded, failed, due.size());
        return succeeded + failed;
    }

    /** 앱 종료 시 실행자를 graceful 종료 — 유예 초과 시 강제(shutdownNow). */
    @PreDestroy
    public void shutdown() {
        purgeExecutor.shutdown();
        try {
            if (!purgeExecutor.awaitTermination(PURGE_SHUTDOWN_GRACE_SEC, TimeUnit.SECONDS)) {
                log.warn("withdraw purge: 실행자가 {}s 내에 종료되지 않아 강제 종료", PURGE_SHUTDOWN_GRACE_SEC);
                purgeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            purgeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 데몬 + 식별 가능한 이름의 purge 워커 스레드 팩토리. */
    private static final class PurgeThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "withdraw-purge-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
