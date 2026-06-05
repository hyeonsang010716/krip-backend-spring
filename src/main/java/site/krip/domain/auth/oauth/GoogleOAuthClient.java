package site.krip.domain.auth.oauth;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import site.krip.domain.auth.entity.OAuthProvider;

import java.util.Map;

/** Google OAuth 클라이언트. */
@Component
public class GoogleOAuthClient extends AbstractOAuthClient {

    public GoogleOAuthClient(RestClient oauthRestClient) {
        super(oauthRestClient);
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public OAuthUser fetchUserInfo(OAuthConfig config, String accessToken) {
        Map<String, Object> data = restClient.get()
                .uri(config.userinfoUrl())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);

        if (data == null || data.get("id") == null) {
            throw new IllegalStateException("Google userinfo 응답에 id 없음");
        }
        return new OAuthUser(
                String.valueOf(data.get("id")),
                OAuthProvider.GOOGLE,
                data.get("email") == null ? null : String.valueOf(data.get("email")),
                data.get("name") == null ? null : String.valueOf(data.get("name")));
    }
}
