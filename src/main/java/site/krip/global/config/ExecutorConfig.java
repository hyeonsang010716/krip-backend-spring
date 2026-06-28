package site.krip.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import site.krip.global.common.image.ImageUploadExecutor;
import site.krip.global.support.MdcTaskDecorator;

/**
 * 비동기 executor — 블로킹 I/O 를 공용 {@code ForkJoinPool.commonPool()} 에서 격리한다.
 *
 * <p>{@code pushExecutor}: 채팅 FCM 푸시(블로킹 Redis+FCM) 전용. 고정 풀+유계 큐로,
 * 푸시 폭주가 앱 전역 async/parallelStream 을 starvation 시키지 않게 한다. 푸시는 best-effort 이므로
 * 큐 포화 시 호출 스레드(메시지 핫패스)를 막거나 예외를 던지지 않고 로그 후 드롭한다.
 * {@code recoverExecutor}: 접속 시 unread 백그라운드 복구(블로킹 Mongo) 전용. WS 하트비트 sweep 스케줄러를
 * 점유하지 않도록 분리한다. best-effort 이므로 포화 시 동일하게 드롭한다.
 * {@code chatOpExecutor}: WS op(send/read)의 블로킹 DB/Redis/Mongo 처리를 컨테이너 I/O 스레드에서 격리한다.
 * 포화 시 AbortPolicy(기본)로 제출이 거부돼 핸들러가 server_busy 로 백프레셔한다(드롭 아님).
 */
@Configuration
@Slf4j
public class ExecutorConfig {

    @Bean
    public ThreadPoolTaskExecutor pushExecutor(ExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.pushPoolSize());
        executor.setMaxPoolSize(props.pushPoolSize());
        executor.setQueueCapacity(props.pushQueueCapacity());
        executor.setThreadNamePrefix("chat-push-");
        executor.setTaskDecorator(MdcTaskDecorator.instance());
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("push 큐 포화 — 작업 드롭 (active={}, queue={})",
                        pool.getActiveCount(), pool.getQueue().size()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor recoverExecutor(ExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.recoverPoolSize());
        executor.setMaxPoolSize(props.recoverPoolSize());
        executor.setQueueCapacity(props.recoverQueueCapacity());
        executor.setThreadNamePrefix("chat-recover-");
        executor.setTaskDecorator(MdcTaskDecorator.instance());
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("unread 복구 큐 포화 — 작업 드롭 (active={}, queue={})",
                        pool.getActiveCount(), pool.getQueue().size()));
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor chatOpExecutor(ExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.chatOpPoolSize());
        executor.setMaxPoolSize(props.chatOpPoolSize());
        executor.setQueueCapacity(props.chatOpQueueCapacity());
        executor.setThreadNamePrefix("chat-op-");
        executor.setTaskDecorator(MdcTaskDecorator.instance());
        // 기본 AbortPolicy: 포화 시 RejectedExecutionException → 핸들러가 server_busy 로 백프레셔(소켓 유지).
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor chatDeliveryExecutor(ExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.chatDeliveryPoolSize());
        executor.setMaxPoolSize(props.chatDeliveryPoolSize());
        executor.setQueueCapacity(props.chatDeliveryQueueCapacity());
        executor.setThreadNamePrefix("chat-deliver-");
        executor.setTaskDecorator(MdcTaskDecorator.instance());
        // 기본 AbortPolicy: 포화 시 RejectedExecutionException → FanoutService 가 해당 전달만 드롭(best-effort).
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean
    public ImageUploadExecutor imageUploadExecutor(ExecutorProperties props) {
        return new ImageUploadExecutor(
                props.imageProcessPoolSize(), props.imageProcessQueueCapacity(),
                props.imageUploadPoolSize(), props.imageUploadQueueCapacity());
    }
}
