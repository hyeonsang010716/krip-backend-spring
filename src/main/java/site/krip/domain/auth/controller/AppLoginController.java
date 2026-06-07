package site.krip.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import site.krip.domain.auth.dto.OAuthCallbackResult;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.oauth.OAuthClient;
import site.krip.domain.auth.oauth.OAuthConfig;
import site.krip.domain.auth.oauth.OAuthConfigs;
import site.krip.domain.auth.oauth.OAuthStateService;
import site.krip.domain.auth.service.OAuthCallbackService;
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
    private final OAuthCallbackService callbackService;
    private final OAuthStateService stateService;
    private final OAuthProperties oauthProperties;

    public AppLoginController(OAuthConfigs oauthConfigs, OAuthCallbackService callbackService,
                             OAuthStateService stateService, OAuthProperties oauthProperties) {
        this.oauthConfigs = oauthConfigs;
        this.callbackService = callbackService;
        this.stateService = stateService;
        this.oauthProperties = oauthProperties;
    }

    @GetMapping
    public ResponseEntity<Void> appLogin(@RequestParam("type") OAuthProvider type) {
        OAuthConfig config = oauthConfigs.appConfig(type);
        OAuthClient client = oauthConfigs.client(type);

        OAuthStateService.Issued st = stateService.create("app", type.getValue());
        String authorizationUrl = client.buildAuthorizationUrl(config, st.state());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authorizationUrl))
                .header(HttpHeaders.SET_COOKIE, st.cookie().toString())
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> appCallback(@RequestParam("code") String code,
                                            @RequestParam("state") String state,
                                            HttpServletRequest request) {
        OAuthStateService.Parsed parsed = stateService.verify(state, request);
        if (!"app".equals(parsed.routing())) {
            throw ApiException.badRequest("잘못된 state 값");
        }
        OAuthProvider provider = callbackService.parseProvider(parsed.provider());
        OAuthConfig config = oauthConfigs.appConfig(provider);

        OAuthCallbackResult result = callbackService.exchangeAndRegister(provider, config, code);

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(oauthProperties.appDeepLink())
                .queryParam("status", result.status().getValue())
                .queryParam("utk", result.jwt());
        if (result.email() != null) builder.queryParam("email", result.email());
        if (result.name() != null) builder.queryParam("name", result.name());

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(builder.build().encode().toUriString()))
                .header(HttpHeaders.SET_COOKIE, stateService.expiredCookie().toString())
                .build();
    }
}
