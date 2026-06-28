package site.krip.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
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
@Slf4j
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

    /**
     * OAuth 콜백 — state 검증 → 코드 교환 → JWT 쿠키 + FE 리다이렉트.
     *
     * <p>호출자는 Google 리다이렉트 중인 브라우저라, 실패도 JSON 이 아니라 FE 로 {@code ?status=} 리다이렉트한다:
     * {@code state_invalid}(state/CSRF/만료·더블서브밋), {@code provider_error}(Google 교환·조회 실패),
     * {@code error}(내부). state 검증 실패 시엔 routing 미상이라 기본 frontend 로 폴백한다.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam("code") String code,
                                         @RequestParam("state") String state,
                                         HttpServletRequest request) {
        OAuthStateService.Parsed parsed;
        try {
            parsed = stateService.verify(state, request);
        } catch (Exception e) {
            log.warn("OAuth 콜백 state 검증 실패: {}", e.getMessage());
            return errorRedirect(oauthProperties.frontendUrl(), "state_invalid");
        }
        String redirectBase = "server".equals(parsed.routing())
                ? oauthProperties.frontendUrl() : oauthProperties.localFrontendUrl();

        OAuthProvider provider;
        try {
            provider = callbackService.parseProvider(parsed.provider());
        } catch (Exception e) {
            log.warn("OAuth 콜백 provider 파싱 실패 (provider={})", parsed.provider());
            return errorRedirect(redirectBase, "state_invalid");
        }

        OAuthCallbackResult result;
        try {
            OAuthConfig config = oauthConfigs.webConfig(provider);
            result = callbackService.exchangeAndRegister(provider, config, code);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("OAuth 코드 교환/유저조회 실패 (provider={}): {}", provider, e.toString());
            return errorRedirect(redirectBase, "provider_error");
        } catch (Exception e) {
            log.error("OAuth 콜백 내부 처리 실패 (provider={})", provider, e);
            return errorRedirect(redirectBase, "error");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectBase)
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

    /** 콜백 실패 → 설정된 FE 로 status 코드와 함께 302(+state 쿠키 정리). 내부정보는 싣지 않음. */
    private ResponseEntity<Void> errorRedirect(String base, String status) {
        URI location = UriComponentsBuilder.fromUriString(base)
                .queryParam("status", status)
                .build().encode().toUri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(location)
                .header(HttpHeaders.SET_COOKIE, stateService.expiredCookie().toString())
                .build();
    }
}
