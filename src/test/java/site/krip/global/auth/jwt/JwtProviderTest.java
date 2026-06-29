package site.krip.global.auth.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.config.AuthProperties;
import site.krip.support.TokenTestSupport;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtProvider} 순수 단위 테스트 — issue/parse 라운드트립 및 무효/만료 토큰.
 * {@link AuthProperties} 레코드(중첩 Jwt 포함)를 직접 생성해 주입한다.
 */
class JwtProviderTest {

    private static final String SECRET = "test-login-jwt-secret-value-1234567890";

    private static JwtProvider provider(int expirationDays) {
        // AuthProperties(accessToken, Jwt(secret, expirationDays, cookieName), registeredCacheTtlSeconds)
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, expirationDays, "access_token");
        AuthProperties props = new AuthProperties("dev-access-token", jwt, 300L);
        return new JwtProvider(props, java.time.Clock.systemUTC());
    }

    @Test
    @DisplayName("issue 한 토큰을 parse 하면 동일한 userId 가 돌아온다")
    void issueParseRoundTrip() {
        JwtProvider provider = provider(30);
        String userId = "USER_1717000000_cafebabe";

        String token = provider.issue(userId);

        assertThat(token).isNotBlank();
        assertThat(provider.parse(token).userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("garbage 토큰은 JwtException 을 던진다")
    void garbageTokenThrows() {
        JwtProvider provider = provider(30);
        assertThatThrownBy(() -> provider.parse("garbage.token.value"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("서명을 변조한 토큰은 JwtException 을 던진다")
    void tamperedSignatureThrows() {
        JwtProvider provider = provider(30);
        String tampered = TokenTestSupport.tamperSignature(provider.issue("USER_x"));

        assertThatThrownBy(() -> provider.parse(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 secret 으로 서명한 토큰은 JwtException 을 던진다")
    void wrongKeyThrows() {
        JwtProvider provider = provider(30);
        SecretKey otherKey = TokenTestSupport.deriveKey("completely-different-login-secret");
        String foreign = Jwts.builder()
                .claim("user_id", "USER_x")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> provider.parse(foreign))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("만료된 토큰은 ExpiredJwtException 을 던진다")
    void expiredTokenThrows() {
        JwtProvider provider = provider(30);
        SecretKey key = TokenTestSupport.deriveKey(SECRET);
        Instant past = Instant.now().minusSeconds(3600);
        String expired = Jwts.builder()
                .claim("user_id", "USER_x")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> provider.parse(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("AuthProperties.Jwt.expirationSeconds 는 days * 86400 이다")
    void expirationSecondsComputation() {
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, 30, "access_token");
        assertThat(jwt.expirationSeconds()).isEqualTo(30L * 24 * 60 * 60);
    }
}
