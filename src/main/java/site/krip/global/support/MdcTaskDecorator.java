package site.krip.global.support;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 비동기/스케줄 작업에 MDC({@code request_id})를 전파한다.
 *
 * <p>제출 시점의 MDC 를 캡처해 워커 스레드에 적용한다. request_id 가 없으면(WS·스케줄러 발 작업) 새로 생성해
 * 모든 비동기 로그가 추적 ID 를 갖게 한다. 실행 후 이전 컨텍스트로 복원해 풀 스레드 재사용 시 MDC 누수를 막는다.
 *
 * <p>{@link TaskDecorator} 로 스프링 풀(executor/scheduler)에 연결하고, raw 풀에는 {@link #wrap} 헬퍼로 적용한다.
 */
public final class MdcTaskDecorator implements TaskDecorator {

    /** MDC 및 로그 패턴({@code %X{request_id}})의 키 — RequestIdFilter 와 동일해야 한다. */
    public static final String REQUEST_ID = "request_id";

    @Override
    public Runnable decorate(Runnable runnable) {
        return wrap(runnable);
    }

    /** 제출 시점 MDC 를 캡처해 실행 시 적용(+request_id 보강)하고 종료 시 복원하는 Runnable 래퍼. */
    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> runWithMdc(captured, () -> {
            task.run();
            return null;
        });
    }

    /** {@link #wrap(Runnable)} 의 Supplier 버전 — 값을 반환하는 작업용(raw 풀 submit). */
    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> runWithMdc(captured, task);
    }

    private static <T> T runWithMdc(Map<String, String> captured, Supplier<T> task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            if (MDC.get(REQUEST_ID) == null) {
                MDC.put(REQUEST_ID, UUID.randomUUID().toString());
            }
            return task.get();
        } finally {
            if (previous != null) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        }
    }

    private MdcTaskDecorator() {
        // 헬퍼는 static, TaskDecorator 인스턴스는 팩토리로 생성.
    }

    /** 스프링 풀(executor/scheduler) 연결용 인스턴스. */
    public static MdcTaskDecorator instance() {
        return new MdcTaskDecorator();
    }
}
