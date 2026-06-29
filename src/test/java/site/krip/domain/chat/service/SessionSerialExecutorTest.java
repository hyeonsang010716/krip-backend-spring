package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@link SessionSerialExecutor} 단위 테스트 — 순서 보존, reject 후 복구, 경합 시 무유실(strand 방지).
 */
class SessionSerialExecutorTest {

    @Test
    @DisplayName("제출 순서대로 직렬 실행한다")
    void runsTasksInSubmissionOrder() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        SessionSerialExecutor exec = new SessionSerialExecutor(pool, 1000);
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            int n = i;
            exec.submit(() -> {
                order.add(n);
                done.countDown();
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(0, 1, 2, 3, 4);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("execute 가 reject 돼도 executor 는 깨지지 않고 다음 submit 이 정상 드레인한다")
    void recoversAfterPoolRejection() throws Exception {
        ExecutorService real = Executors.newSingleThreadExecutor();
        AtomicInteger executeCalls = new AtomicInteger();
        Executor flaky = r -> {
            if (executeCalls.getAndIncrement() == 0) {
                throw new RejectedExecutionException("first execute rejects");
            }
            real.execute(r);
        };
        SessionSerialExecutor exec = new SessionSerialExecutor(flaky, 1000);
        AtomicInteger ran = new AtomicInteger();

        // 첫 submit — execute reject → throw, 작업은 회수돼 미실행.
        assertThatThrownBy(() -> exec.submit(ran::incrementAndGet))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(ran.get()).isZero();

        // 다음 submit — execute 정상 → 드레인. executor 가 깨지지 않았음.
        CountDownLatch done = new CountDownLatch(1);
        exec.submit(() -> {
            ran.incrementAndGet();
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isEqualTo(1);
        real.shutdownNow();
    }

    @Test
    @DisplayName("경합 + reject 풀에서도 수락된 작업은 모두 실행된다 — strand 없음")
    void noTaskLostUnderContentionWithRejectingPool() throws Exception {
        // 작은 풀 + AbortPolicy → 부하 시 execute 가 reject. 여러 세션·스레드가 같은 executor 에 동시 제출해도
        // submit 이 예외 없이 반환한(수락된) 작업은 전부 실행돼야 한다. 구버그면 일부가 strand 돼 executed < accepted.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());

        int sessions = 16;
        int threads = 6;
        int perThread = 300;
        SessionSerialExecutor[] execs = new SessionSerialExecutor[sessions];
        for (int i = 0; i < sessions; i++) {
            execs[i] = new SessionSerialExecutor(pool, 1_000_000);
        }

        AtomicInteger executed = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService submitters = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            submitters.execute(() -> {
                try {
                    start.await();
                    Random rnd = new Random(seed);
                    for (int k = 0; k < perThread; k++) {
                        SessionSerialExecutor e = execs[rnd.nextInt(sessions)];
                        // retry-until-accepted: reject 는 미실행 보장이므로 재시도하면 결국 수락된다.
                        while (true) {
                            try {
                                e.submit(executed::incrementAndGet);
                                accepted.incrementAndGet();
                                break;
                            } catch (RejectedExecutionException ignore) {
                                Thread.yield();
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

        // 수락된 작업이 전부 실행될 때까지 대기 — strand 면 영영 도달 못 해 타임아웃.
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executed.get()).isEqualTo(accepted.get()));
        assertThat(accepted.get()).isEqualTo(threads * perThread);

        submitters.shutdownNow();
        pool.shutdownNow();
    }
}
