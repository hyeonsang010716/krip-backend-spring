package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 세션 단위 직렬 실행기 — 제출 순서대로 한 번에 하나씩, 공유 풀 위에서 실행한다.
 *
 * <p>WS op(인바운드) 또는 fan-out 전송(아웃바운드)을 I/O/폴 스레드에서 떼어내되, 같은 세션의 순서는
 * 보존해야 한다. 드레인 1회당 풀 스레드 1개가 그 세션 큐를 빌 때까지 순차 처리하므로 세션당 최대 1개의
 * 풀 스레드만 점유한다. 세션당 대기 한도({@code maxQueued}) 초과 시 제출이 거부돼 호출측이 백프레셔할 수 있다.
 *
 * <p>{@code submit}은 thread-safe 다(synchronized). 같은 세션에 여러 스레드가 제출해도 안전하며,
 * 각 제출원의 순서는 보존되고 제출원 간 인터리브만 비결정적이다.
 */
public final class SessionSerialExecutor {

    private static final Logger log = LoggerFactory.getLogger(SessionSerialExecutor.class);

    private final Executor pool;
    private final int maxQueued;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private boolean running = false;

    public SessionSerialExecutor(Executor pool, int maxQueued) {
        this.pool = pool;
        this.maxQueued = maxQueued;
    }

    /**
     * 작업을 큐에 넣고, 드레인 중이 아니면 풀에서 드레인을 시작한다.
     *
     * @throws RejectedExecutionException 세션 대기 한도 초과 또는 공유 풀 포화
     */
    public void submit(Runnable task) {
        synchronized (this) {
            if (queue.size() >= maxQueued) {
                throw new RejectedExecutionException("session op queue full (" + maxQueued + ")");
            }
            queue.add(task);
            if (running) {
                return; // 이미 드레인 중 — 큐 추가만으로 순서 보존
            }
            running = true;
        }
        try {
            pool.execute(this::drain);
        } catch (RuntimeException e) {
            // 풀 포화 — 방금 넣은 작업을 회수하고 호출측에 백프레셔 전파(거부 = 미실행 보장).
            synchronized (this) {
                running = false;
                queue.removeLastOccurrence(task);
            }
            throw e;
        }
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                task = queue.poll();
                if (task == null) {
                    running = false;
                    return;
                }
            }
            try {
                task.run();
            } catch (Throwable t) {
                log.warn("세션 직렬 작업 실행 중 예외 (무시하고 계속)", t);
            }
        }
    }
}
