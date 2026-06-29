package site.krip.global.common.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * process()→uploadInParallel 합성이 업로드를 풀 워커에서 실행하는지 검증(회귀).
 * uploadPool 포화(CallerRuns) 시에도 요청(Tomcat) 스레드가 아닌 img-* 워커에서 돌아 스레드 격리가 유지돼야 한다.
 */
class ImageUploadExecutorIsolationTest {

    @Test
    @DisplayName("process() 로 감싼 uploadInParallel 은 호출 스레드가 아닌 풀 워커에서 실행된다")
    void uploadRunsOnPoolWorkerNotCaller() {
        ImageUploadExecutor executor = new ImageUploadExecutor(2, 4, 2, 4);
        try {
            String callerThread = Thread.currentThread().getName();
            AtomicReference<String> uploadThread = new AtomicReference<>();

            List<String> result = executor.process(() ->
                    executor.uploadInParallel(List.<Supplier<String>>of(() -> {
                        uploadThread.set(Thread.currentThread().getName());
                        return "ok";
                    })));

            assertThat(result).containsExactly("ok");
            assertThat(uploadThread.get())
                    .isNotEqualTo(callerThread)
                    .startsWith("img-");
        } finally {
            executor.destroy();
        }
    }

    @Test
    @DisplayName("processAll 은 입력 순서대로 결과를 반환한다")
    void processAllReturnsInInputOrder() {
        ImageUploadExecutor executor = new ImageUploadExecutor(3, 8, 2, 4);
        try {
            List<Supplier<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3, () -> 4);
            assertThat(executor.processAll(tasks)).containsExactly(1, 2, 3, 4);
        } finally {
            executor.destroy();
        }
    }

    @Test
    @DisplayName("processAll 은 한 작업이 실패하면 그 예외를 전파한다(나머지 형제는 취소)")
    void processAllPropagatesTaskFailure() {
        ImageUploadExecutor executor = new ImageUploadExecutor(2, 8, 2, 4);
        try {
            List<Supplier<Integer>> tasks = List.of(
                    () -> 1,
                    () -> {
                        throw new IllegalStateException("boom");
                    },
                    () -> 3);
            assertThatThrownBy(() -> executor.processAll(tasks))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
        } finally {
            executor.destroy();
        }
    }
}
