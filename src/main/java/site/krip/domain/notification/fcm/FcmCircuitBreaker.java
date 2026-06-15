package site.krip.domain.notification.fcm;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * FCM 전송 전용 경량 서킷 브레이커.
 *
 * <p>배치 연속 실패가 임계치에 도달하면 cooldown 동안 open 되어 발송을 단락(fast-fail)한다 — FCM 장애가
 * 타임아웃으로 push 워커 풀을 고갈시키는 것을 막는다. cooldown 후엔 probe 1건만 통과시켜 복구를 확인한다.
 *
 * <p>상태(실패수·open 만료·probe)를 불변 {@link State} 로 묶어 CAS 로만 전이한다 — stale 성공이 방금
 * 설정된 open 을 덮어쓰지 못하게(latch). open 중 성공은 무시, half-open probe 성공만 close.
 * 전송 timeout(5s) < cooldown(30s)이라 in-flight 발송은 cooldown 종료 전에 끝나 stale 엣지가 없다.
 */
final class FcmCircuitBreaker {

    /** open 만료 sentinel(=closed). nanoTime 이 0 일 확률은 무시. */
    private static final long NOT_OPEN = 0L;

    private final int failureThreshold;
    private final long openCooldownNanos;
    private final LongSupplier nanoClock;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    FcmCircuitBreaker(int failureThreshold, long openCooldownMillis) {
        this(failureThreshold, openCooldownMillis, System::nanoTime);
    }

    // 테스트용 — nanoClock 주입으로 전이를 결정적으로 검증.
    FcmCircuitBreaker(int failureThreshold, long openCooldownMillis, LongSupplier nanoClock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openCooldownNanos = Math.max(0, openCooldownMillis) * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    /** closed=항상 통과, open(cooldown)=차단, cooldown 후=probe 1건만. 통과 시 success/failure/release 보고 필수. */
    boolean tryAcquire() {
        while (true) {
            State s = state.get();
            if (s.openUntilNanos == NOT_OPEN) {
                return true; // closed
            }
            if (nanoClock.getAsLong() - s.openUntilNanos < 0) {
                return false; // open — cooldown 중 (wrap-safe)
            }
            if (s.probeInFlight) {
                return false; // half-open — probe 이미 점유
            }
            if (state.compareAndSet(s, s.withProbe())) {
                return true; // half-open — probe 선점
            }
        }
    }

    void recordSuccess() {
        while (true) {
            State s = state.get();
            if (s.openUntilNanos == NOT_OPEN) {
                if (s.failures == 0) {
                    return; // 이미 깨끗한 closed
                }
                if (state.compareAndSet(s, State.CLOSED)) {
                    return; // closed 성공 — 카운터 리셋
                }
            } else if (s.probeInFlight) {
                if (state.compareAndSet(s, State.CLOSED)) {
                    return; // probe 성공 — close
                }
            } else {
                return; // open 중 도착 성공 — 무시(latch 보존)
            }
        }
    }

    /** 결과 미기록으로 probe 만 해제 — FCM 무관 중단(빌드 실패 등). closed/probe 미점유면 no-op. */
    void release() {
        while (true) {
            State s = state.get();
            if (!s.probeInFlight) {
                return;
            }
            // open 만료는 유지(이미 과거)라 다음 호출이 즉시 재probe.
            if (state.compareAndSet(s, s.withoutProbe())) {
                return;
            }
        }
    }

    void recordFailure() {
        while (true) {
            State s = state.get();
            if (s.openUntilNanos != NOT_OPEN) {
                if (s.probeInFlight) {
                    if (state.compareAndSet(s, openState())) {
                        return; // probe 실패 → 재open
                    }
                } else {
                    return; // 이미 open — stale 실패 무시
                }
            } else {
                int f = s.failures + 1;
                State next = f >= failureThreshold ? openState() : new State(f, NOT_OPEN, false);
                if (state.compareAndSet(s, next)) {
                    return;
                }
            }
        }
    }

    private State openState() {
        return new State(failureThreshold, nanoClock.getAsLong() + openCooldownNanos, false);
    }

    /** 불변 상태 — 실패수 / open 만료(nanoTime, 0=closed) / probe 점유. */
    private record State(int failures, long openUntilNanos, boolean probeInFlight) {
        static final State CLOSED = new State(0, NOT_OPEN, false);

        State withProbe() {
            return new State(failures, openUntilNanos, true);
        }

        State withoutProbe() {
            return new State(failures, openUntilNanos, false);
        }
    }
}
