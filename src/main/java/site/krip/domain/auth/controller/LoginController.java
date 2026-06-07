package site.krip.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
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
import site.krip.global.config.OAuthProperties;

import java.net.URI;

/** 웹 OAuth 로그인 — {@code GET /api/auth/login}, {@code /callback}. */
@RestController
@RequestMapping("/api/auth/login")
public class LoginController {

    private final OAuthConfigs oauthConfigs;
    private final OAuthCallbackService callbackService;
    private final LoginCookieFactory cookieFactory;
    private final OAuthStateService stateService;
    private final OAuthProperties oauthProperties;

    public LoginController(OAuthConfigs oauthConfigs, OAuthCallbackService callbackService,
                           LoginCookieFactory cookieFactory, OAuthStateService stateService,
                           OAuthProperties oauthProperties) {
        this.oauthConfigs = oauthConfigs;
        this.callbackService = callbackService;
        this.cookieFactory = cookieFactory;
        this.stateService = stateService;
        this.oauthProperties = oauthProperties;
    }

    /** OAuth 로그인 — state nonce 쿠키 발급 후 제공자 인증 페이지로 리다이렉트. */
    @GetMapping
    public ResponseEntity<Void> login(@RequestParam("type") OAuthProvider type,
                                      @RequestParam(value = "is_local", required = false) Boolean isLocal) {
        OAuthConfig config = oauthConfigs.webConfig(type);
        OAuthClient client = oauthConfigs.client(type);

        String routing = Boolean.TRUE.equals(isLocal) ? "local" : "server";
        OAuthStateService.Issued st = stateService.create(routing, type.getValue());

        String authorizationUrl = client.buildAuthorizationUrl(config, st.state());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authorizationUrl))
                .header(HttpHeaders.SET_COOKIE, st.cookie().toString())
                .build();
    }

    /** OAuth 콜백 — state 검증 후 코드 교환 → JWT 쿠키 발급 + FE 리다이렉트. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam("code") String code,
                                         @RequestParam("state") String state,
                                         HttpServletRequest request) {
        OAuthStateService.Parsed parsed = stateService.verify(state, request);
        OAuthProvider provider = callbackService.parseProvider(parsed.provider());
        OAuthConfig config = oauthConfigs.webConfig(provider);

        OAuthCallbackResult result = callbackService.exchangeAndRegister(provider, config, code);

        String redirectTo = "server".equals(parsed.routing())
                ? oauthProperties.frontendUrl() : oauthProperties.localFrontendUrl();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectTo)
                .queryParam("status", result.status().getValue());
        if (result.email() != null) builder.queryParam("email", result.email());
        if (result.name() != null) builder.queryParam("name", result.name());

        ResponseCookie cookie = cookieFactory.create(result.jwt());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(builder.build().encode().toUriString()))
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.SET_COOKIE, stateService.expiredCookie().toString())
                .build();
    }
}
