package site.krip.domain.notification.fcm;

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
 * {@link FcmCircuitBreaker} 단위 테스트 — nanoClock 주입으로 open/half-open/close 전이를 결정적으로 검증하고,
 * cooldown 경과 후 probe 가 단 1건만 통과하는지(single-flight) 동시성으로 검증한다.
 */
@DisplayName("FCM 서킷브레이커 — open/half-open/probe 전이·latch 경합")
class FcmCircuitBreakerTest {

    /** breaker(.., 1000) 의 cooldown(1s) — now 는 ns 로 전진하므로 ns 단위. */
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    /** cooldown(1s) 중간 지점. */
    private static final long HALF_SECOND_NANOS = 500_000_000L;

    private final AtomicLong now = new AtomicLong(0);

    private FcmCircuitBreaker breaker(int threshold, long cooldownMs) {
        return new FcmCircuitBreaker(threshold, cooldownMs, now::get);
    }

    /** 임계치(3)만큼 연속 실패시켜 open 으로 전이. */
    private static void trip(FcmCircuitBreaker cb) {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
    }

    @Test
    @DisplayName("임계치 미만 연속 실패는 통과(closed)")
    void belowThresholdStaysClosed() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("임계치 도달 시 cooldown 동안 차단(open)")
    void opensAtThreshold() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        trip(cb);
        assertThat(cb.tryAcquire()).isFalse();

        now.addAndGet(HALF_SECOND_NANOS); // cooldown(1s) 중간
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("cooldown 경과 후 probe 1건만 통과(half-open single-flight)")
    void halfOpenAllowsSingleProbe() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        trip(cb);

        now.addAndGet(ONE_SECOND_NANOS); // cooldown 1s 경과
        assertThat(cb.tryAcquire()).isTrue();  // 첫 호출 = probe
        assertThat(cb.tryAcquire()).isFalse(); // 후속은 probe 결과 전까지 차단
    }

    @Test
    @DisplayName("half-open probe 성공 시 close + 실패 카운트 리셋")
    void probeSuccessClosesAndResets() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        trip(cb);
        assertThat(cb.tryAcquire()).isFalse(); // open

        now.addAndGet(ONE_SECOND_NANOS);        // cooldown 경과 — half-open
        assertThat(cb.tryAcquire()).isTrue();   // probe 획득
        cb.recordSuccess();                     // probe 성공 → close
        assertThat(cb.tryAcquire()).isTrue();   // closed

        // 리셋되었으므로 다시 임계치만큼 실패해야 재open
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isTrue();
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("open(cooldown 중) 도착한 성공은 open 을 풀지 않는다 — latch 경합 회귀(#3)")
    void successDuringOpenDoesNotClobberOpen() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        trip(cb);
        assertThat(cb.tryAcquire()).isFalse();  // open, cooldown 중

        // stale 성공 모사 — 옛 구현이면 여기서 open 이 풀렸다.
        cb.recordSuccess();
        assertThat(cb.tryAcquire()).isFalse();  // 여전히 open (latch 보존)

        now.addAndGet(ONE_SECOND_NANOS);        // 정상 cooldown 경과 후에만 probe 허용
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("동시: 임계치 도달 실패와 stale 성공이 경쟁해도 open 이 풀리지 않는다(latch 경합)")
    void concurrentStaleSuccessNeverClobbersOpen() throws Exception {
        int rounds = 2000; // latch 경합은 드물게 재현되므로 충분한 반복으로 스트레스
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                FcmCircuitBreaker cb = breaker(1, 1000); // 실패 1건이면 즉시 open
                CountDownLatch start = new CountDownLatch(1);
                Runnable fail = () -> { await(start); cb.recordFailure(); };
                Runnable ok = () -> { await(start); cb.recordSuccess(); };
                var f1 = pool.submit(fail);
                var f2 = pool.submit(ok);
                start.countDown();
                f1.get(5, TimeUnit.SECONDS);
                f2.get(5, TimeUnit.SECONDS);
                // 임계치=1 → 어떤 인터리빙이든 최종은 open.
                assertThat(cb.tryAcquire()).as("round %d", i).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("half-open probe 실패 시 재open, 다음 cooldown 후 다시 probe 1건")
    void failureWhileHalfOpenReopens() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        trip(cb);
        now.addAndGet(ONE_SECOND_NANOS);
        assertThat(cb.tryAcquire()).isTrue(); // probe 획득

        cb.recordFailure(); // probe 실패 → 재open
        assertThat(cb.tryAcquire()).isFalse();

        now.addAndGet(ONE_SECOND_NANOS); // 새 cooldown 경과
        assertThat(cb.tryAcquire()).isTrue(); // 다음 probe 1건 허용
    }

    @Test
    @DisplayName("release: probe 획득 후 결과 미기록(빌드 실패 등) 중단 시 probe 해제로 재probe 가능 — 영구 고착 방지")
    void releaseFreesStuckProbe() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        trip(cb);
        now.addAndGet(ONE_SECOND_NANOS);         // cooldown 경과 — half-open

        assertThat(cb.tryAcquire()).isTrue();    // probe 획득 (probeInFlight=true)
        assertThat(cb.tryAcquire()).isFalse();   // single-flight — in-flight 동안 차단

        cb.release();                            // 결과 미기록 — probe 만 해제(카운터/open 불변)

        // release 없으면 probeInFlight 가 고착돼 이후 영원히 false. release 로 재probe 가능해야 한다.
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("cooldown 경과 직후 다수 스레드가 동시에 진입해도 probe 는 정확히 1건만 통과")
    void concurrentProbeIsSingleFlight() throws Exception {
        FcmCircuitBreaker cb = breaker(3, 1000);
        trip(cb);
        now.addAndGet(ONE_SECOND_NANOS); // cooldown 경과 — half-open

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
