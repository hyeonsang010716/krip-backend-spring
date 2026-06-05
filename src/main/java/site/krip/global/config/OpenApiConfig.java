package site.krip.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import site.krip.global.auth.CurrentUserId;

/**
 * Swagger 문서에 글로벌 Bearer(ACCESS_TOKEN) 인증 스킴을 노출한다.
 */
@Configuration
public class OpenApiConfig {

    /**
     * {@link CurrentUserId} 파라미터는 리졸버가 주입하므로 실제 query 파라미터가 아니다.
     * springdoc 이 이를 query 파라미터로 잘못 문서화하지 않도록 문서에서 제외한다.
     */
    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class);
    }

    @Bean
    public OpenAPI kripOpenAPI() {
        final String scheme = "BearerAuth";
        return new OpenAPI()
                .info(new Info().title("Krip API").version("0.3.0"))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("ACCESS_TOKEN 값")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
