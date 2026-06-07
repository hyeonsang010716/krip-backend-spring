package site.krip.domain.notification.fcm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * FCM 전송 전용 경량 서킷 브레이커.
 *
 * <p>전송(배치) 연속 실패가 임계치에 도달하면 cooldown 동안 open 되어 후속 발송을 즉시 단락(fast-fail)한다 —
 * FCM 장애 시 push 워커 스레드가 타임아웃마다 묶여 풀이 고갈되는 것을 막는다. cooldown 경과 후 1건을
 * probe(half-open)해 성공하면 close, 실패하면 재open. nanoTime 기반이라 wall-clock 점프에 영향받지 않는다.
 */
final class FcmCircuitBreaker {

    private final int failureThreshold;
    private final long openCooldownNanos;
    private final LongSupplier nanoClock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilNanos = new AtomicLong(0);

    FcmCircuitBreaker(int failureThreshold, long openCooldownMillis) {
        this(failureThreshold, openCooldownMillis, System::nanoTime);
    }

    // 테스트용 — nanoClock 주입으로 open/half-open 전이를 결정적으로 검증한다.
    FcmCircuitBreaker(int failureThreshold, long openCooldownMillis, LongSupplier nanoClock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openCooldownNanos = Math.max(0, openCooldownMillis) * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    /** open 이면 true. cooldown 경과 시 probe 1건을 허용하기 위해 false 를 반환한다(half-open). */
    boolean isOpen() {
        long until = openUntilNanos.get();
        return until != 0 && nanoClock.getAsLong() - until < 0; // nanoTime wrap-safe 비교
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilNanos.set(0);
    }

    void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilNanos.set(nanoClock.getAsLong() + openCooldownNanos);
        }
    }
}
