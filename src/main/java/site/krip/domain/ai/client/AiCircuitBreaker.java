package site.krip.domain.ai.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * AI 서비스 호출 전용 경량 서킷 브레이커.
 *
 * <p>연속 실패가 임계치에 도달하면 cooldown 동안 open 되어 후속 호출을 즉시 단락(fast-fail)한다 —
 * FastAPI 장애 시 요청 스레드가 read timeout 마다 묶여 풀이 고갈되는 것을 막는다. cooldown 경과 후엔
 * 단 1건만 probe(half-open)로 통과시켜 복구를 확인한다. nanoTime 기반이라 wall-clock 점프에 무관하다.
 */
final class AiCircuitBreaker {

    private final int failureThreshold;
    private final long openCooldownNanos;
    private final LongSupplier nanoClock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilNanos = new AtomicLong(0);
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    AiCircuitBreaker(int failureThreshold, long openCooldownMillis) {
        this(failureThreshold, openCooldownMillis, System::nanoTime);
    }

    // 테스트용 — nanoClock 주입으로 open/half-open 전이를 결정적으로 검증한다.
    AiCircuitBreaker(int failureThreshold, long openCooldownMillis, LongSupplier nanoClock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openCooldownNanos = Math.max(0, openCooldownMillis) * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    /**
     * 통과 가능하면 true. closed 면 항상 통과, open(cooldown 중)이면 차단,
     * cooldown 경과 후엔 단 한 호출만 probe 로 통과시킨다(half-open single-flight).
     * 통과한 호출은 반드시 {@link #recordSuccess()}/{@link #recordFailure()}/{@link #release()} 로 결과를 보고해야 한다.
     */
    boolean tryAcquire() {
        long until = openUntilNanos.get();
        if (until == 0) {
            return true;
        }
        if (nanoClock.getAsLong() - until < 0) {
            return false;
        }
        return probeInFlight.compareAndSet(false, true);
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilNanos.set(0);
        probeInFlight.set(false);
    }

    /** 결과 기록 없이 probe 만 해제 — AI 건강과 무관한 중단(클라이언트 4xx 등) 시. */
    void release() {
        probeInFlight.set(false);
    }

    void recordFailure() {
        // openUntil 갱신을 먼저 — 재open 직후 probe 가 새지 않도록. 그 후 probe 해제.
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilNanos.set(nanoClock.getAsLong() + openCooldownNanos);
        }
        probeInFlight.set(false);
    }
}
