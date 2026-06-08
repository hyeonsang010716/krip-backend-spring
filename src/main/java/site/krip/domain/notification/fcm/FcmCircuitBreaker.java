package site.krip.domain.notification.fcm;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * FCM 전송 전용 경량 서킷 브레이커.
 *
 * <p>배치 연속 실패가 임계치에 도달하면 cooldown 동안 open 되어 후속 발송을 즉시 단락(fast-fail)한다 —
 * FCM 장애 시 push 워커가 타임아웃마다 묶여 풀이 고갈되는 것을 막는다. cooldown 경과 후에는 단 1건만
 * probe(half-open)로 통과시켜 복구를 확인하고, 성공하면 close·실패하면 재open 한다.
 * nanoTime 기반이라 wall-clock 점프에 영향받지 않는다.
 */
final class FcmCircuitBreaker {

    private final int failureThreshold;
    private final long openCooldownNanos;
    private final LongSupplier nanoClock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilNanos = new AtomicLong(0);
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    FcmCircuitBreaker(int failureThreshold, long openCooldownMillis) {
        this(failureThreshold, openCooldownMillis, System::nanoTime);
    }

    // 테스트용 — nanoClock 주입으로 open/half-open 전이를 결정적으로 검증한다.
    FcmCircuitBreaker(int failureThreshold, long openCooldownMillis, LongSupplier nanoClock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openCooldownNanos = Math.max(0, openCooldownMillis) * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    /**
     * 통과 가능하면 true. closed 면 항상 통과, open(cooldown 중)이면 차단,
     * cooldown 경과 후에는 단 한 호출만 probe 로 통과시킨다(half-open single-flight).
     * 통과한 호출은 반드시 {@link #recordSuccess()}/{@link #recordFailure()} 로 결과를 보고해야 한다.
     */
    boolean tryAcquire() {
        long until = openUntilNanos.get();
        if (until == 0) {
            return true; // closed
        }
        if (nanoClock.getAsLong() - until < 0) {
            return false; // open — cooldown 중 (nanoTime wrap-safe 비교)
        }
        return probeInFlight.compareAndSet(false, true); // half-open — 1건만 선점 통과
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilNanos.set(0);
        probeInFlight.set(false);
    }

    void recordFailure() {
        // openUntil 갱신을 먼저 해야 재open 직후 probe 가 새지 않는다 — 그 후 probe 해제.
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilNanos.set(nanoClock.getAsLong() + openCooldownNanos);
        }
        probeInFlight.set(false);
    }
}
