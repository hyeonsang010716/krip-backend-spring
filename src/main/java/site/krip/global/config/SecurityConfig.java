package site.krip.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.global.auth.filter.BearerTokenFilter;
import site.krip.global.auth.filter.LoginAuthFilter;
import site.krip.global.auth.filter.RegisterCheckFilter;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.cache.RegisteredCacheManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security 필터 체인.
 *
 * <p>인증 자체는 커스텀 필터(Bearer → Login → RegisterCheck)에 위임하고,
 * Security 는 CORS/CSRF/세션 정책/인가 매칭/미인증 응답을 담당한다.
 */
@Configuration
public class SecurityConfig {

    /** 무인증 허용 경로. {@code /error} 는 컨테이너 에러 디스패치가 401 로 가려지지 않도록 포함. */
    private static final String[] PUBLIC_PATHS = {
            "/error",
            "/health", "/ready",
            "/docs", "/openapi.json", "/openapi.json/**", "/swagger-ui/**",
            "/api/auth/login/**", "/api/public/**", "/api/ws/**"
    };

    /** 인증은 필요하나 2차 가입/상태 검증은 면제하는 경로 (PUBLIC_PATHS 에 더해짐). */
    private static final String[] REGISTRATION_EXEMPT_PATHS = {
            "/api/auth/register/**", "/api/auth/logout/**", "/api/auth/withdraw/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AuthProperties authProps,
                                            JwtProvider jwtProvider,
                                            UserRepository userRepository,
                                            RegisteredCacheManager registeredCache,
                                            CorsConfigurationSource corsConfigurationSource,
                                            AuthenticationEntryPoint authenticationEntryPoint,
                                            ObjectMapper mapper) throws Exception {
        RequestMatcher publicMatcher = anyOf(PUBLIC_PATHS);
        RequestMatcher registrationExempt = anyOf(concat(PUBLIC_PATHS, REGISTRATION_EXEMPT_PATHS));

        BearerTokenFilter bearer = new BearerTokenFilter(authProps.accessToken(), mapper, publicMatcher);
        LoginAuthFilter login =
                new LoginAuthFilter(jwtProvider, authProps.jwt().cookieName(), mapper, publicMatcher);
        RegisterCheckFilter register =
                new RegisterCheckFilter(userRepository, registeredCache, mapper, registrationExempt);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(login, BearerTokenFilter.class)
                .addFilterAfter(register, LoginAuthFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static RequestMatcher anyOf(String... patterns) {
        PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> matchers = new ArrayList<>();
        for (String pattern : patterns) {
            matchers.add(builder.matcher(pattern));
        }
        return new OrRequestMatcher(matchers);
    }

    private static String[] concat(String[] a, String[] b) {
        List<String> all = new ArrayList<>(List.of(a));
        all.addAll(List.of(b));
        return all.toArray(String[]::new);
    }
}
