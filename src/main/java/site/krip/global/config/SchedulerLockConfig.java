package site.krip.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 분산 스케줄러 락(ShedLock) — 멀티 노드에서 {@code @Scheduled} cron 잡의 중복 실행을 방지.
 *
 * <p>{@code @SchedulerLock} 이 붙은 메서드는 Redis 락을 잡은 단 한 노드에서만 실행된다.
 * "클러스터에서 한 번만" 돌아야 하는 잡(예: 탈퇴 purge)에만 적용한다.
 * {@code defaultLockAtMostFor} 는 락 보유 노드가 죽어도 락이 영구 점유되지 않게 하는 상한이며,
 * 잡별로 {@code @SchedulerLock(lockAtMostFor=...)} 로 override 한다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerLockConfig {

    /** Redis 기반 LockProvider — 락 키를 "krip" 환경으로 네임스페이스(공유 Redis 충돌 방지). */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "krip");
    }
}
