package site.krip.global.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 동기 HTTP 클라이언트 빈.
 *
 * <p>요청 스레드에서 동기 호출되므로 connect/read 타임아웃을 명시 — upstream 지연 시
 * 워커 스레드가 무한 블로킹되어 풀이 마비되는 것을 막는다.
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient oauthRestClient(RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    /**
     * FastAPI AI 서비스 호출용 클라이언트. read 타임아웃은 LLM 추론 지연을 고려해 별도(길게) 설정하고,
     * 서비스 간 인증 토큰(Bearer)을 기본 헤더로 박는다 — FastAPI 의 BearerTokenMiddleware 와 짝.
     */
    @Bean
    public RestClient aiRestClient(RestClient.Builder builder,
                                   AiProperties aiProps,
                                   AuthProperties authProps) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(aiProps.connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(aiProps.readTimeoutMs()));
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .baseUrl(aiProps.serviceUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authProps.accessToken())
                .build();
    }
}
