package site.krip.domain.ai.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AiCircuitBreaker} 단위 테스트 — nanoClock 주입으로 open/half-open/close 전이를 결정적으로 검증하고,
 * cooldown 경과 후 probe 가 단 1건만 통과하는지(single-flight) 동시성으로 검증한다.
 */
class AiCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);

    private AiCircuitBreaker breaker(int threshold, long cooldownMs) {
        return new AiCircuitBreaker(threshold, cooldownMs, now::get);
    }

    @Test
    @DisplayName("임계치 미만 연속 실패는 통과(closed)")
    void belowThresholdStaysClosed() {
        AiCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("임계치 도달 시 cooldown 동안 차단(open)")
    void opensAtThreshold() {
        AiCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isFalse();

        now.addAndGet(500_000_000L); // cooldown(1s) 중간
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("cooldown 경과 후 probe 1건만 통과(half-open single-flight)")
    void halfOpenAllowsSingleProbe() {
        AiCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();

        now.addAndGet(1_000_000_000L); // cooldown 1s 경과
        assertThat(cb.tryAcquire()).isTrue();  // 첫 호출 = probe
        assertThat(cb.tryAcquire()).isFalse(); // 후속은 probe 결과 전까지 차단
    }

    @Test
    @DisplayName("성공 기록 시 즉시 close + 실패 카운트 리셋")
    void successClosesAndResets() {
        AiCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isFalse();

        cb.recordSuccess();
        assertThat(cb.tryAcquire()).isTrue();

        // 리셋되었으므로 다시 임계치만큼 실패해야 재open
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isTrue();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("half-open probe 실패 시 재open, 다음 cooldown 후 다시 probe 1건")
    void failureWhileHalfOpenReopens() {
        AiCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        now.addAndGet(1_000_000_000L);
        assertThat(cb.tryAcquire()).isTrue(); // probe 획득

        cb.recordFailure(); // probe 실패 → 재open
        assertThat(cb.tryAcquire()).isFalse();

        now.addAndGet(1_000_000_000L); // 새 cooldown 경과
        assertThat(cb.tryAcquire()).isTrue(); // 다음 probe 1건 허용
    }

    @Test
    @DisplayName("release: probe 획득 후 결과 미기록(클라이언트 4xx 등) 중단 시 probe 해제로 재probe 가능 — 영구 고착 방지")
    void releaseFreesStuckProbe() {
        AiCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        now.addAndGet(1_000_000_000L);          // cooldown 경과 — half-open

        assertThat(cb.tryAcquire()).isTrue();    // probe 획득 (probeInFlight=true)
        assertThat(cb.tryAcquire()).isFalse();   // single-flight — in-flight 동안 차단

        cb.release();                            // 결과 미기록 — probe 만 해제(카운터/open 불변)

        // release 없으면 probeInFlight 가 고착돼 이후 영원히 false. release 로 재probe 가능해야 한다.
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("cooldown 경과 직후 다수 스레드가 동시에 진입해도 probe 는 정확히 1건만 통과")
    void concurrentProbeIsSingleFlight() throws Exception {
        AiCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        now.addAndGet(1_000_000_000L); // cooldown 경과 — half-open

        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (cb.tryAcquire()) {
                        acquired.incrementAndGet();
                    }
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(acquired.get()).isEqualTo(1);
    }
}
