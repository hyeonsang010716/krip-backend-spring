package site.krip.domain.auth.oauth;

import org.springframework.stereotype.Component;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.global.common.exception.ApiException;
import site.krip.global.config.OAuthProperties;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 제공자별 OAuth 설정/클라이언트 레지스트리. 웹과 앱은 redirect_uri 만 다르며, 미지원 제공자는 400.
 */
@Component
public class OAuthConfigs {

    private static final String GOOGLE_AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String GOOGLE_SCOPE = "openid email profile";

    private final Map<OAuthProvider, OAuthConfig> webConfigs = new EnumMap<>(OAuthProvider.class);
    private final Map<OAuthProvider, OAuthConfig> appConfigs = new EnumMap<>(OAuthProvider.class);
    private final Map<OAuthProvider, OAuthClient> clients = new EnumMap<>(OAuthProvider.class);

    public OAuthConfigs(OAuthProperties props, List<OAuthClient> clientBeans) {
        OAuthProperties.Google g = props.google();
        webConfigs.put(OAuthProvider.GOOGLE, new OAuthConfig(
                g.clientId(), g.clientSecret(), GOOGLE_AUTHORIZE, GOOGLE_TOKEN, GOOGLE_USERINFO,
                props.webRedirectUri(), GOOGLE_SCOPE));
        appConfigs.put(OAuthProvider.GOOGLE, new OAuthConfig(
                g.clientId(), g.clientSecret(), GOOGLE_AUTHORIZE, GOOGLE_TOKEN, GOOGLE_USERINFO,
                props.appRedirectUri(), GOOGLE_SCOPE));

        for (OAuthClient client : clientBeans) {
            clients.put(client.provider(), client);
        }
    }

    public OAuthConfig webConfig(OAuthProvider provider) {
        return require(webConfigs.get(provider), provider);
    }

    public OAuthConfig appConfig(OAuthProvider provider) {
        return require(appConfigs.get(provider), provider);
    }

    public OAuthClient client(OAuthProvider provider) {
        OAuthClient client = clients.get(provider);
        if (client == null) {
            throw ApiException.badRequest("지원하지 않는 OAuth 제공자: " + provider);
        }
        return client;
    }

    private OAuthConfig require(OAuthConfig config, OAuthProvider provider) {
        if (config == null) {
            throw ApiException.badRequest("지원하지 않는 OAuth 제공자: " + provider);
        }
        return config;
    }
}
