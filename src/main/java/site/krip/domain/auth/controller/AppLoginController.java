package site.krip.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

/**
 * 네이티브 앱 OAuth 로그인. 쿠키 대신 딥링크의 {@code utk} 쿼리 파라미터로 JWT 를 전달하며,
 * 앱은 이를 {@code X-Auth-Token} 헤더로 보낸다.
 */
@RestController
@RequestMapping("/api/auth/login/app")
public class AppLoginController {

    private static final Logger log = LoggerFactory.getLogger(AppLoginController.class);

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

    /**
     * 앱 OAuth 콜백 — 실패도 JSON 이 아니라 딥링크로 {@code ?status=} 리다이렉트한다:
     * {@code state_invalid}(state/CSRF/만료·routing 불일치), {@code provider_error}(Google 교환·조회 실패),
     * {@code error}(내부). 목적지는 항상 고정 딥링크라 routing 미상이어도 안전하다.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> appCallback(@RequestParam("code") String code,
                                            @RequestParam("state") String state,
                                            HttpServletRequest request) {
        String base = oauthProperties.appDeepLink();

        OAuthStateService.Parsed parsed;
        try {
            parsed = stateService.verify(state, request);
        } catch (Exception e) {
            log.warn("앱 OAuth 콜백 state 검증 실패: {}", e.getMessage());
            return errorRedirect(base, "state_invalid");
        }
        if (!"app".equals(parsed.routing())) {
            log.warn("앱 OAuth 콜백 routing 불일치 (routing={})", parsed.routing());
            return errorRedirect(base, "state_invalid");
        }

        OAuthProvider provider;
        try {
            provider = callbackService.parseProvider(parsed.provider());
        } catch (Exception e) {
            log.warn("앱 OAuth 콜백 provider 파싱 실패 (provider={})", parsed.provider());
            return errorRedirect(base, "state_invalid");
        }

        OAuthCallbackResult result;
        try {
            OAuthConfig config = oauthConfigs.appConfig(provider);
            result = callbackService.exchangeAndRegister(provider, config, code);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("앱 OAuth 코드 교환/유저조회 실패 (provider={}): {}", provider, e.toString());
            return errorRedirect(base, "provider_error");
        } catch (Exception e) {
            log.error("앱 OAuth 콜백 내부 처리 실패 (provider={})", provider, e);
            return errorRedirect(base, "error");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base)
                .queryParam("status", result.status().getValue())
                .queryParam("utk", result.jwt());
        if (result.email() != null) builder.queryParam("email", result.email());
        if (result.name() != null) builder.queryParam("name", result.name());

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(builder.build().encode().toUriString()))
                .header(HttpHeaders.SET_COOKIE, stateService.expiredCookie().toString())
                .build();
    }

    /** 콜백 실패 → 딥링크로 status 코드와 함께 302(+state 쿠키 정리). 내부정보는 싣지 않음. */
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
