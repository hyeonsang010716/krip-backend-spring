package site.krip.domain.auth.service;

import org.springframework.stereotype.Service;
import site.krip.domain.auth.dto.OAuthCallbackResult;
import site.krip.domain.auth.dto.SignupResult;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.oauth.OAuthClient;
import site.krip.domain.auth.oauth.OAuthConfig;
import site.krip.domain.auth.oauth.OAuthConfigs;
import site.krip.domain.auth.oauth.OAuthUser;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.common.exception.ApiException;

/**
 * OAuth 콜백 오케스트레이션 (web/app 공통) — 코드 교환 → 사용자 조회 → 가입 판정 → JWT 발급.
 * 컨트롤러는 state 파싱·config 선택·리다이렉트 구성만 담당한다.
 */
@Service
public class OAuthCallbackService {

    private final OAuthConfigs oauthConfigs;
    private final SignupService signupService;
    private final JwtProvider jwtProvider;

    public OAuthCallbackService(OAuthConfigs oauthConfigs, SignupService signupService,
                                JwtProvider jwtProvider) {
        this.oauthConfigs = oauthConfigs;
        this.signupService = signupService;
        this.jwtProvider = jwtProvider;
    }

    /** state 의 provider value(google 등) → {@link OAuthProvider}. */
    public OAuthProvider parseProvider(String providerValue) {
        try {
            return OAuthProvider.fromValue(providerValue);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("지원하지 않는 OAuth 제공자");
        }
    }

    public OAuthCallbackResult exchangeAndRegister(OAuthProvider provider, OAuthConfig config, String code) {
        OAuthClient client = oauthConfigs.client(provider);
        String accessToken = client.exchangeCodeForToken(config, code);
        OAuthUser userInfo = client.fetchUserInfo(config, accessToken);

        SignupResult result = signupService.checkAndRegister(provider, userInfo.id());
        // WITHDRAWAL_PENDING 도 JWT 발급 — 보호 경로는 RegisterCheckFilter 가 차단.
        String jwt = jwtProvider.issue(result.userId());
        return new OAuthCallbackResult(result.status(), userInfo.email(), userInfo.name(), jwt);
    }
}
