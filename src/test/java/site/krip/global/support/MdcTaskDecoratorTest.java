package site.krip.global.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MdcTaskDecorator} 단위 테스트 — request_id 전파/신규생성/누수방지/반환값.
 */
class MdcTaskDecoratorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("제출 시점의 request_id 를 워커 작업에 전파한다")
    void propagatesRequestId() throws Exception {
        MDC.put(MdcTaskDecorator.REQUEST_ID, "req-123");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable wrapped = MdcTaskDecorator.wrap(
                () -> seen.set(MDC.get(MdcTaskDecorator.REQUEST_ID)));

        // 다른 스레드(워커 모사)에서 실행 — 캡처된 MDC 가 적용되어야 한다.
        runOnFreshThread(wrapped);

        assertThat(seen.get()).isEqualTo("req-123");
    }

    @Test
    @DisplayName("request_id 가 없으면(WS/스케줄러 발) 새로 생성한다")
    void generatesWhenAbsent() throws Exception {
        // 제출 스레드에 MDC 없음
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable wrapped = MdcTaskDecorator.wrap(
                () -> seen.set(MDC.get(MdcTaskDecorator.REQUEST_ID)));

        runOnFreshThread(wrapped);

        assertThat(seen.get()).isNotBlank();
    }

    @Test
    @DisplayName("실행 후 MDC 를 이전 상태로 복원해 풀 스레드 재사용 누수를 막는다")
    void restoresAfterRun() throws Exception {
        // 워커 스레드가 직전 작업의 id 를 들고 있다가 새 작업(전파 없음)을 실행하는 상황 모사
        Thread worker = new Thread(() -> {
            MDC.put(MdcTaskDecorator.REQUEST_ID, "stale-from-prev-task");
            MdcTaskDecorator.wrap(() -> { /* 새 작업 */ }).run();
            // 새 작업이 끝나면 워커의 원래 컨텍스트로 복원되어야 한다.
            assertThat(MDC.get(MdcTaskDecorator.REQUEST_ID)).isEqualTo("stale-from-prev-task");
        });
        worker.start();
        worker.join();
    }

    @Test
    @DisplayName("Supplier 래퍼는 결과를 그대로 반환한다")
    void supplierReturnsValue() {
        String result = MdcTaskDecorator.wrap(() -> "ok").get();
        assertThat(result).isEqualTo("ok");
    }

    private static void runOnFreshThread(Runnable r) throws InterruptedException {
        Thread t = new Thread(r);
        t.start();
        t.join();
    }
}
