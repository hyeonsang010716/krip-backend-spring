package site.krip.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.global.auth.filter.BearerTokenFilter;
import site.krip.global.auth.filter.LoginAuthFilter;
import site.krip.global.auth.filter.RegisterCheckFilter;
import site.krip.global.auth.filter.RequestIdFilter;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.cache.RegisteredCacheManager;

/**
 * 인증 필터 체인 등록 + 실행 순서 지정.
 *
 * <p>실행 순서: RequestId → Bearer(글로벌) → Login(유저 JWT) → RegisterCheck(가입/상태).
 * CORS 는 Spring 의 {@code CorsFilter} 가 더 앞에서 처리한다.
 */
@Configuration
public class FilterConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(1);
        return reg;
    }

    @Bean
    FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(AuthProperties props, ObjectMapper mapper) {
        FilterRegistrationBean<BearerTokenFilter> reg =
                new FilterRegistrationBean<>(new BearerTokenFilter(props.accessToken(), mapper));
        reg.setOrder(2);
        return reg;
    }

    @Bean
    FilterRegistrationBean<LoginAuthFilter> loginAuthFilter(JwtProvider jwtProvider, AuthProperties props,
                                                            ObjectMapper mapper) {
        FilterRegistrationBean<LoginAuthFilter> reg = new FilterRegistrationBean<>(
                new LoginAuthFilter(jwtProvider, props.jwt().cookieName(), mapper));
        reg.setOrder(3);
        return reg;
    }

    @Bean
    FilterRegistrationBean<RegisterCheckFilter> registerCheckFilter(UserRepository userRepository,
                                                                    RegisteredCacheManager cache,
                                                                    ObjectMapper mapper) {
        FilterRegistrationBean<RegisterCheckFilter> reg = new FilterRegistrationBean<>(
                new RegisterCheckFilter(userRepository, cache, mapper));
        reg.setOrder(4);
        return reg;
    }
}
