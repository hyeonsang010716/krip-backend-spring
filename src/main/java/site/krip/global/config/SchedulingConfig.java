package site.krip.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import site.krip.global.support.MdcTaskDecorator;

/**
 * 스케줄링 스레드 풀 — 스프링이 생명주기(종료)를 관리하므로 누수 없음.
 *
 * <p>커스텀 {@link org.springframework.scheduling.TaskScheduler} 빈이 있으면 Boot 자동설정이 backoff 되므로,
 * 전역 {@code @Scheduled}용 {@code taskScheduler}(이 이름이어야 {@code @Scheduled}가 자동 사용)와 채팅 WS 전용 {@code chatWsScheduler}를 모두 여기서 명시적으로 정의한다.
 * WS 하트비트가 reconcile 블로킹 drain 에 밀려 세션 TTL 이 만료되지 않도록 풀을 분리한다.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(SchedulingProperties props) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.poolSize());
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setTaskDecorator(MdcTaskDecorator.instance());
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler chatWsScheduler(SchedulingProperties props) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.chatWsPoolSize());
        scheduler.setThreadNamePrefix("chat-ws-");
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setTaskDecorator(MdcTaskDecorator.instance());
        return scheduler;
    }
}
