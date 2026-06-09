package site.krip.global.common.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * process()→uploadInParallel 합성이 업로드 작업을 풀 워커에서 실행하는지 검증(회귀).
 *
 * <p>tripmate 가 이 합성을 쓰므로, uploadPool 포화(CallerRuns) 시에도 업로드가 요청(Tomcat) 스레드가
 * 아닌 img-* 풀 워커에서 돌아 스레드 격리가 유지된다. 직접 {@code uploadInParallel} 을 호출하면 CallerRuns
 * 가 호출 스레드에서 돌아 격리가 깨진다(이번 수정 이전의 tripmate Phase 2).
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
}
