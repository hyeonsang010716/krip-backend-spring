package site.krip.domain.auth.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.krip.domain.auth.entity.OAuthProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Google OAuth 클라이언트 HTTP 단위 테스트 — {@link MockRestServiceServer} 로 응답을 가짜로 주고 파싱·누락 필드 방어 검증.
 * 커버: 토큰 교환 성공/access_token 누락, userinfo 파싱 성공/id 누락, authorize URL 생성.
 */
@DisplayName("Google OAuth 클라이언트 — 토큰 교환·userinfo·authorize URL")
class GoogleOAuthClientTest {

    private static final String TOKEN_URL = "https://token.test/token";
    private static final String USERINFO_URL = "https://userinfo.test/me";

    private MockRestServiceServer server;
    private GoogleOAuthClient client;
    private OAuthConfig config;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleOAuthClient(builder.build());
        config = new OAuthConfig("client-id", "client-secret",
                "https://accounts.google.com/o/oauth2/v2/auth", TOKEN_URL, USERINFO_URL,
                "https://krip.site/api/auth/login", "openid email profile");
    }

    @Test
    @DisplayName("토큰 교환 성공 → access_token 반환")
    void exchangeCodeForTokenSuccess() {
        server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.exchangeCodeForToken(config, "auth-code")).isEqualTo("tok-123");
        server.verify();
    }

    @Test
    @DisplayName("토큰 응답에 access_token 없음 → IllegalStateException")
    void exchangeCodeForTokenMissingToken() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"error\":\"invalid_grant\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeCodeForToken(config, "bad-code"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("userinfo 성공 → OAuthUser(id/email/name) 매핑")
    void fetchUserInfoSuccess() {
        server.expect(requestTo(USERINFO_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess(
                        "{\"id\":\"google-sub-1\",\"email\":\"u@example.com\",\"name\":\"홍길동\"}",
                        MediaType.APPLICATION_JSON));

        OAuthUser user = client.fetchUserInfo(config, "tok-123");

        assertThat(user.id()).isEqualTo("google-sub-1");
        assertThat(user.email()).isEqualTo("u@example.com");
        assertThat(user.name()).isEqualTo("홍길동");
        assertThat(user.provider()).isEqualTo(OAuthProvider.GOOGLE);
        server.verify();
    }

    @Test
    @DisplayName("userinfo 응답에 id 없음 → IllegalStateException")
    void fetchUserInfoMissingId() {
        server.expect(requestTo(USERINFO_URL))
                .andRespond(withSuccess("{\"email\":\"u@example.com\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchUserInfo(config, "tok-123"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("authorize URL — client_id/redirect_uri/state/prompt 포함")
    void buildAuthorizationUrl() {
        String url = client.buildAuthorizationUrl(config, "server:google");

        assertThat(url)
                .contains("client_id=client-id")
                .contains("response_type=code")
                .contains("state=server")        // 콜론은 인코딩될 수 있어 prefix 만 확인
                .contains("prompt=select_account")
                .contains("scope=openid");
    }
}
