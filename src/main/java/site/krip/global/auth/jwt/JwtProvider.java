package site.krip.global.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import site.krip.global.config.AuthProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 유저 로그인 JWT 발급/검증 (HS256).
 *
 * <p>페이로드: {@code user_id}, {@code iat}, {@code exp}.
 * secret 이 HS256 최소 길이(256bit)에 못 미칠 수 있어 SHA-256 으로 파생해 키 길이를 보장한다.
 */
@Component
public class JwtProvider {

    private static final String CLAIM_USER_ID = "user_id";

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtProvider(AuthProperties props) {
        this.key = deriveKey(props.jwt().secret());
        this.expirationSeconds = props.jwt().expirationSeconds();
    }

    private static SecretKey deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
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
