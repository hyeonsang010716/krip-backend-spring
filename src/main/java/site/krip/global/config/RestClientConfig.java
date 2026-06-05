package site.krip.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * OAuth 토큰 교환 / userinfo 호출용 동기 HTTP 클라이언트.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient oauthRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
