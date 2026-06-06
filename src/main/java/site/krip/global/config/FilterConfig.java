package site.krip.global.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.krip.global.auth.filter.RequestIdFilter;

/**
 * 요청 추적 필터 등록.
 *
 * <p>인증 필터(Bearer/Login/RegisterCheck)는 {@link SecurityConfig} 의 체인에서 실행된다.
 * RequestId 는 인증 실패 로그에도 추적 ID 가 남도록 Security 체인보다 앞에 둔다.
 */
@Configuration
public class FilterConfig {

    /** Spring Security 필터 체인 순서(-100)보다 앞. */
    private static final int REQUEST_ID_ORDER = -200;

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(REQUEST_ID_ORDER);
        return reg;
    }
}
