package site.krip.domain.auth.oauth;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import site.krip.domain.auth.entity.OAuthProvider;

import java.util.Map;

/**
 * OAuth Authorization Code 플로우 공통 구현 (인증 URL 생성 + 토큰 교환).
 * 제공자별 userinfo 파싱은 하위 클래스가 담당.
 */
public abstract class AbstractOAuthClient implements OAuthClient {

    protected final RestClient restClient;

    protected AbstractOAuthClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String buildAuthorizationUrl(OAuthConfig config, String state) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.authorizeUrl())
                .queryParam("client_id", config.clientId())
                .queryParam("redirect_uri", config.callbackRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state);
        if (config.scope() != null && !config.scope().isBlank()) {
            builder.queryParam("scope", config.scope());
        }
        // 로그인 캐시 방지.
        if (provider() == OAuthProvider.GOOGLE) {
            builder.queryParam("prompt", "select_account");
        }
        return builder.build().encode().toUriString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String exchangeCodeForToken(OAuthConfig config, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("redirect_uri", config.callbackRedirectUri());
        form.add("code", code);

        Map<String, Object> body = restClient.post()
                .uri(config.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(Map.class);

        if (body == null || body.get("access_token") == null) {
            throw new IllegalStateException("OAuth access_token 응답 없음");
        }
        return String.valueOf(body.get("access_token"));
    }
}
