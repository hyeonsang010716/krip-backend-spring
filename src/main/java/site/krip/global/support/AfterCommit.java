package site.krip.global.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 커밋 성공 후 실행한다. 롤백 시 실행되지 않으며, 활성 트랜잭션이 없으면 즉시 실행한다.
 * 알림 fan-out·스토리지 정리 등 커밋된 본 작업에 영향을 주면 안 되는 부수효과 전용이라 예외는 로깅 후 무시한다.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            execute(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                execute(task);
            }
        });
    }

    private static void execute(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("afterCommit 부수효과 실패", e);
        }
    }
}
