package site.krip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부팅 스모크 — 실 PG/Mongo/Redis 위에서 Flyway·ddl-auto=validate·Mongo 인덱스·전체 빈 와이어링이 끝까지 올라오는지 검증.
 */
class ApplicationBootTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("실 PG/Mongo/Redis 위에서 컨텍스트가 정상 기동한다")
    void contextLoads() {
        assertThat(context).isNotNull();
        // 핵심 빈이 실제로 등록되었는지 표본 확인.
        assertThat(context.getBeanDefinitionCount()).isPositive();
        assertThat(context.containsBean("kripApplication")).isTrue();
    }
}
