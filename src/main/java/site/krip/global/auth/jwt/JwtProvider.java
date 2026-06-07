package site.krip.global.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;
import site.krip.global.config.AuthProperties;
import site.krip.global.support.SecretKeys;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 유저 로그인 JWT 발급/검증 (HS256).
 *
 * <p>페이로드: {@code user_id}, {@code iat}, {@code exp}.
 * secret 은 {@link SecretKeys} 가 SHA-256 으로 파생하며, 약한/누락 secret 은 부팅 시 거부한다.
 */
@Component
public class JwtProvider {

    private static final String CLAIM_USER_ID = "user_id";

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtProvider(AuthProperties props) {
        this.key = SecretKeys.hmacSha256(props.jwt().secret(), "로그인 JWT");
        this.expirationSeconds = props.jwt().expirationSeconds();
    }

    /** user_id 로 로그인 JWT 발급. */
    public String issue(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_USER_ID, userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationSeconds, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();
    }

    /**
     * JWT 를 검증하고 user_id 추출.
     *
     * @throws io.jsonwebtoken.ExpiredJwtException 만료
     * @throws io.jsonwebtoken.JwtException 그 외 무효 토큰
     */
    public String parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get(CLAIM_USER_ID, String.class);
    }
}
