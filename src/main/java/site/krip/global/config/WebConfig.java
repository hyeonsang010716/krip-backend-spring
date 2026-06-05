package site.krip.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import site.krip.global.auth.CurrentUserIdArgumentResolver;

import java.util.List;

/**
 * MVC 설정 — CORS + {@code @CurrentUserId} 리졸버 등록.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;
    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    public WebConfig(CorsProperties corsProperties,
                     CurrentUserIdArgumentResolver currentUserIdArgumentResolver) {
        this.corsProperties = corsProperties;
        this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }
}
