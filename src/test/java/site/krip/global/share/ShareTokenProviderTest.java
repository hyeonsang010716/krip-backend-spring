package site.krip.global.share;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.config.ShareProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShareTokenProvider} 순수 단위 테스트 — encode/decode 라운드트립 및 무효 토큰 거절.
 * Spring 컨텍스트 없이 {@link ShareProperties} 레코드를 직접 생성해 주입한다.
 */
class ShareTokenProviderTest {

    private static final String SECRET = "test-share-secret-for-unit-tests";

    private static ShareTokenProvider provider(int expirationDays) {
        // ShareProperties(secret, expirationDays)
        return new ShareTokenProvider(new ShareProperties(SECRET, expirationDays));
    }

    private static SecretKey deriveKey(String secret) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        return Keys.hmacShaKeyFor(digest);
    }

    @Test
    @DisplayName("encode 한 토큰을 decode 하면 동일한 planId 가 돌아온다")
    void encodeDecodeRoundTrip() {
        ShareTokenProvider provider = provider(7);
        String planId = "TP_1717000000_deadbeef";

        ShareTokenProvider.Issued issued = provider.encode(planId);

        assertThat(issued.token()).isNotBlank();
        assertThat(provider.decode(issued.token())).isEqualTo(planId);
    }

    @Test
    @DisplayName("Issued.expiresAt 은 발급 시각 + expirationSeconds 근처이다")
    void issuedExpiresAtMatchesExpirationWindow() {
        int days = 7;
        ShareTokenProvider provider = provider(days);
        Instant before = Instant.now();

        ShareTokenProvider.Issued issued = provider.encode("TP_x");

        long expectedSeconds = (long) days * 24 * 60 * 60;
        // 발급 직후 now+expectedSeconds 와 1초 오차 내.
        assertThat(issued.expiresAt())
                .isBetween(before.plusSeconds(expectedSeconds - 2),
                        Instant.now().plusSeconds(expectedSeconds + 2));
    }

    @Test
    @DisplayName("형식이 깨진(garbage) 토큰은 ShareTokenException 을 던진다")
    void garbageTokenThrows() {
        ShareTokenProvider provider = provider(7);
        assertThatThrownBy(() -> provider.decode("not-a-jwt"))
                .isInstanceOf(ShareTokenException.class)
                .hasMessageContaining("유효하지 않은");
    }

    @Test
    @DisplayName("빈 토큰은 ShareTokenException 을 던진다")
    void emptyTokenThrows() {
        ShareTokenProvider provider = provider(7);
        assertThatThrownBy(() -> provider.decode(""))
                .isInstanceOf(ShareTokenException.class);
    }

    @Test
    @DisplayName("서명을 변조한 토큰은 ShareTokenException 을 던진다")
    void tamperedSignatureThrows() {
        ShareTokenProvider provider = provider(7);
        String token = provider.encode("TP_abc").token();

        // 서명부 첫 문자(상위 6비트 모두 유효)를 변조한다. 마지막 문자는 base64url 미사용 비트가
        // 있어 a↔b 치환이 같은 바이트로 디코딩될 수 있어(서명 유효 유지) flaky 하므로 첫 문자를 바꾼다.
        int sigStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(sigStart);
        char swapped = first == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, sigStart) + swapped + token.substring(sigStart + 1);

        assertThatThrownBy(() -> provider.decode(tampered))
                .isInstanceOf(ShareTokenException.class);
    }

    @Test
    @DisplayName("다른 secret 으로 서명한 토큰은 ShareTokenException 을 던진다")
    void wrongKeyThrows() throws Exception {
        ShareTokenProvider provider = provider(7);
        SecretKey otherKey = deriveKey("a-totally-different-secret-value");
        String foreign = Jwts.builder()
                .claim("plan_id", "TP_x")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> provider.decode(foreign))
                .isInstanceOf(ShareTokenException.class);
    }

    @Test
    @DisplayName("만료된 토큰은 ShareTokenException(만료 메시지) 을 던진다")
    void expiredTokenThrows() throws Exception {
        ShareTokenProvider provider = provider(7);
        SecretKey key = deriveKey(SECRET);
        Instant past = Instant.now().minusSeconds(3600);
        String expired = Jwts.builder()
                .claim("plan_id", "TP_x")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> provider.decode(expired))
                .isInstanceOf(ShareTokenException.class)
                .hasMessageContaining("만료");
    }

    @Test
    @DisplayName("plan_id 클레임이 없는 유효 서명 토큰은 ShareTokenException 을 던진다")
    void missingPlanIdClaimThrows() throws Exception {
        ShareTokenProvider provider = provider(7);
        SecretKey key = deriveKey(SECRET);
        String noPlan = Jwts.builder()
                .claim("other", "value")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> provider.decode(noPlan))
                .isInstanceOf(ShareTokenException.class);
    }
}
