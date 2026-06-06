package site.krip.domain.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import site.krip.domain.auth.dto.SignupResult;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.oauth.OAuthClient;
import site.krip.domain.auth.oauth.OAuthConfig;
import site.krip.domain.auth.oauth.OAuthConfigs;
import site.krip.domain.auth.oauth.OAuthUser;
import site.krip.domain.auth.service.SignupService;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.common.exception.ApiException;
import site.krip.global.config.OAuthProperties;

import java.net.URI;

/**
 * 네이티브 앱 OAuth 로그인. 쿠키 대신 딥링크의 {@code utk} 쿼리 파라미터로 JWT 를 전달하며,
 * 앱은 이를 {@code X-Auth-Token} 헤더로 보낸다.
 */
@RestController
@RequestMapping("/api/auth/login/app")
public class AppLoginController {

    private final OAuthConfigs oauthConfigs;
    private final SignupService signupService;
    private final JwtProvider jwtProvider;
    private final OAuthProperties oauthProperties;

    public AppLoginController(OAuthConfigs oauthConfigs, SignupService signupService,
                              JwtProvider jwtProvider, OAuthProperties oauthProperties) {
        this.oauthConfigs = oauthConfigs;
        this.signupService = signupService;
        this.jwtProvider = jwtProvider;
        this.oauthProperties = oauthProperties;
    }

    @GetMapping
    public ResponseEntity<Void> appLogin(@RequestParam("type") OAuthProvider type) {
        OAuthConfig config = oauthConfigs.appConfig(type);
        OAuthClient client = oauthConfigs.client(type);

        String state = "app:" + type.getValue();
        String authorizationUrl = client.buildAuthorizationUrl(config, state);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizationUrl)).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> appCallback(@RequestParam("code") String code,
                                            @RequestParam("state") String state) {
        int idx = state.lastIndexOf(':');
        if (idx < 0 || !"app".equals(state.substring(0, idx))) {
            throw ApiException.badRequest("잘못된 state 값");
        }
        String providerValue = state.substring(idx + 1);

        OAuthProvider provider;
        try {
            provider = OAuthProvider.fromValue(providerValue);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("지원하지 않는 OAuth 제공자");
        }
        OAuthConfig config = oauthConfigs.appConfig(provider);
        OAuthClient client = oauthConfigs.client(provider);

        String accessToken = client.exchangeCodeForToken(config, code);
        OAuthUser userInfo = client.fetchUserInfo(config, accessToken);

        SignupResult result = signupService.checkAndRegister(provider, userInfo.id());

        String jwt = jwtProvider.issue(result.userId());

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(oauthProperties.appDeepLink())
                .queryParam("status", result.status().getValue())
                .queryParam("utk", jwt);
        if (userInfo.email() != null) builder.queryParam("email", userInfo.email());
        if (userInfo.name() != null) builder.queryParam("name", userInfo.name());

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(builder.build().encode().toUriString()))
                .build();
    }
}
