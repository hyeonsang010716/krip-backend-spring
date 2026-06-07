package site.krip.global.auth.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.config.AuthProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtProvider} 순수 단위 테스트 — issue/parseUserId 라운드트립 및 무효/만료 토큰.
 * {@link AuthProperties} 레코드(중첩 Jwt 포함)를 직접 생성해 주입한다.
 */
class JwtProviderTest {

    private static final String SECRET = "test-login-jwt-secret-value-1234567890";

    private static JwtProvider provider(int expirationDays) {
        // AuthProperties(accessToken, Jwt(secret, expirationDays, cookieName), registeredCacheTtlSeconds)
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, expirationDays, "access_token");
        AuthProperties props = new AuthProperties("dev-access-token", jwt, 300L);
        return new JwtProvider(props);
    }

    private static SecretKey deriveKey(String secret) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        return Keys.hmacShaKeyFor(digest);
    }

    @Test
    @DisplayName("issue 한 토큰을 parseUserId 하면 동일한 userId 가 돌아온다")
    void issueParseRoundTrip() {
        JwtProvider provider = provider(30);
        String userId = "USER_1717000000_cafebabe";

        String token = provider.issue(userId);

        assertThat(token).isNotBlank();
        assertThat(provider.parseUserId(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("garbage 토큰은 JwtException 을 던진다")
    void garbageTokenThrows() {
        JwtProvider provider = provider(30);
        assertThatThrownBy(() -> provider.parseUserId("garbage.token.value"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("서명을 변조한 토큰은 JwtException 을 던진다")
    void tamperedSignatureThrows() {
        JwtProvider provider = provider(30);
        String token = provider.issue("USER_x");
        // 서명부 첫 문자(상위 6비트 모두 유효)를 변조한다. 마지막 문자는 base64url 미사용 비트가
        // 있어 a↔b 치환이 같은 바이트로 디코딩될 수 있어(서명 유효 유지) flaky 하므로 첫 문자를 바꾼다.
        int sigStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(sigStart);
        char swapped = first == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, sigStart) + swapped + token.substring(sigStart + 1);

        assertThatThrownBy(() -> provider.parseUserId(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 secret 으로 서명한 토큰은 JwtException 을 던진다")
    void wrongKeyThrows() throws Exception {
        JwtProvider provider = provider(30);
        SecretKey otherKey = deriveKey("completely-different-login-secret");
        String foreign = Jwts.builder()
                .claim("user_id", "USER_x")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> provider.parseUserId(foreign))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("만료된 토큰은 ExpiredJwtException 을 던진다")
    void expiredTokenThrows() throws Exception {
        JwtProvider provider = provider(30);
        SecretKey key = deriveKey(SECRET);
        Instant past = Instant.now().minusSeconds(3600);
        String expired = Jwts.builder()
                .claim("user_id", "USER_x")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> provider.parseUserId(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("AuthProperties.Jwt.expirationSeconds 는 days * 86400 이다")
    void expirationSecondsComputation() {
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, 30, "access_token");
        assertThat(jwt.expirationSeconds()).isEqualTo(30L * 24 * 60 * 60);
    }
}
