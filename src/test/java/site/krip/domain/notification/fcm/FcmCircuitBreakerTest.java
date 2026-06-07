package site.krip.domain.notification.fcm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FcmCircuitBreaker} 단위 테스트 — nanoClock 주입으로 open/half-open/close 전이를 결정적으로 검증.
 */
class FcmCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);

    private FcmCircuitBreaker breaker(int threshold, long cooldownMs) {
        return new FcmCircuitBreaker(threshold, cooldownMs, now::get);
    }

    @Test
    @DisplayName("임계치 미만 연속 실패는 open 시키지 않는다")
    void belowThresholdStaysClosed() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    @DisplayName("임계치 도달 시 cooldown 동안 open")
    void opensAtThreshold() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.isOpen()).isTrue();

        now.addAndGet(500_000_000L); // cooldown(1s) 중간
        assertThat(cb.isOpen()).isTrue();
    }

    @Test
    @DisplayName("cooldown 경과 후 half-open(probe 허용)")
    void halfOpenAfterCooldown() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();

        now.addAndGet(1_000_000_000L); // cooldown 1s 경과
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    @DisplayName("성공 기록 시 즉시 close + 실패 카운트 리셋")
    void successClosesAndResets() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.isOpen()).isTrue();

        cb.recordSuccess();
        assertThat(cb.isOpen()).isFalse();

        // 리셋되었으므로 다시 임계치만큼 실패해야 재open
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.isOpen()).isFalse();
        cb.recordFailure();
        assertThat(cb.isOpen()).isTrue();
    }

    @Test
    @DisplayName("half-open 에서 추가 실패하면 즉시 재open")
    void failureWhileHalfOpenReopens() {
        FcmCircuitBreaker cb = breaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        now.addAndGet(1_000_000_000L); // half-open
        assertThat(cb.isOpen()).isFalse();

        cb.recordFailure(); // 이미 임계치 이상 → 즉시 재open
        assertThat(cb.isOpen()).isTrue();
    }
}
