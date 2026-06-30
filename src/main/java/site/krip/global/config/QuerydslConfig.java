package site.krip.global.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL 동적 쿼리용 {@link JPAQueryFactory} 빈.
 * 커서/조건 분기가 있는 리포지토리 커스텀 fragment 에서 주입해 사용한다.
 */
@Configuration
public class QuerydslConfig {

    @Bean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
