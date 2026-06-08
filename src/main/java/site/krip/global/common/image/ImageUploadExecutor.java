package site.krip.global.common.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.MdcTaskDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 이미지 처리/업로드 전용 스레드 풀 — 무거운 decode/resize·S3 I/O 를 요청(Tomcat) 스레드에서 분리한다.
 *
 * <p>{@code processingPool}: 이미지 1건의 처리+업로드 작업을 실행. 고정 풀+유계 큐로 동시 처리량을 제한하고,
 * 포화 시 429 로 거절(backpressure)해 Tomcat 풀 고갈·메모리 폭증을 막는다.
 * {@code uploadPool}: 한 게시물의 variant(original/small/medium) S3 업로드를 병렬화한다 — processingPool 과
 * 분리된 별도 풀이라 처리 워커가 업로드 완료를 기다려도 데드락이 없다.
 */
public class ImageUploadExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadExecutor.class);

    private final ThreadPoolExecutor processingPool;
    private final ThreadPoolExecutor uploadPool;

    public ImageUploadExecutor(int processPoolSize, int processQueueCapacity,
                               int uploadPoolSize, int uploadQueueCapacity) {
        this.processingPool = new ThreadPoolExecutor(
                processPoolSize, processPoolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(processQueueCapacity),
                namedFactory("img-process-"),
                new ThreadPoolExecutor.AbortPolicy());      // 포화 → RejectedExecutionException → 429
        this.processingPool.allowCoreThreadTimeOut(true);
        this.uploadPool = new ThreadPoolExecutor(
                uploadPoolSize, uploadPoolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(uploadQueueCapacity),
                namedFactory("img-upload-"),
                new ThreadPoolExecutor.CallerRunsPolicy());  // 포화 시 호출 스레드에서 실행(거절 없음)
        this.uploadPool.allowCoreThreadTimeOut(true);
    }

    /** 이미지 1건의 처리+업로드 작업을 전용 풀에서 실행. 풀+큐 포화 시 429. */
    public <T> T process(Supplier<T> task) {
        return await(submit(task));
    }

    /** 다건(예: tripmate 최대 10장)을 풀 한도 내에서 동시 실행. 일부 거절 시 진행분 취소 후 429. */
    public <T> List<T> processAll(List<Supplier<T>> tasks) {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Supplier<T> task : tasks) {
            try {
                futures.add(processingPool.submit(MdcTaskDecorator.wrap(task)::get));
            } catch (RejectedExecutionException e) {
                futures.forEach(f -> f.cancel(true));
                throw overloaded();
            }
        }
        List<T> results = new ArrayList<>(tasks.size());
        for (Future<T> future : futures) {
            results.add(await(future));
        }
        return results;
    }

    /** 독립 업로드 작업들(variant 등)을 IO 풀에서 병렬 실행 후 결과를 입력 순서대로 수집. */
    public <T> List<T> uploadInParallel(List<Supplier<T>> tasks) {
        List<CompletableFuture<T>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(MdcTaskDecorator.wrap(task), uploadPool))
                .toList();
        try {
            return futures.stream().map(CompletableFuture::join).toList();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private <T> Future<T> submit(Supplier<T> task) {
        try {
            return processingPool.submit(MdcTaskDecorator.wrap(task)::get);
        } catch (RejectedExecutionException e) {
            throw overloaded();
        }
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw ApiException.internalError("이미지 처리가 중단되었습니다.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw ApiException.internalError("이미지 처리에 실패했습니다.");
        }
    }

    private static ApiException overloaded() {
        return ApiException.tooManyRequests("이미지 업로드 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void destroy() {
        shutdown(processingPool, "img-process");
        shutdown(uploadPool, "img-upload");
    }

    private static void shutdown(ThreadPoolExecutor pool, String name) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("{} 풀 graceful shutdown 타임아웃 — 강제 종료", name);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
