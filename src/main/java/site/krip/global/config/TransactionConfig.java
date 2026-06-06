package site.krip.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공유 {@link TransactionTemplate} 빈.
 *
 * <p>기본 설정이며 런타임에 설정을 바꾸지 않으므로 thread-safe — 모든 서비스가 공유한다.
 */
@Configuration
public class TransactionConfig {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
