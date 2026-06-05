package site.krip.global.share;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import site.krip.global.config.ShareProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 플랜 공유 토큰(JWT) 발급/검증 (HS256).
 *
 * <p>secret 을 SHA-256 으로 파생해 키 길이를 보장한다(로그인 JWT 와 동일 방식).
 */
@Component
public class ShareTokenProvider {

    private static final String CLAIM_PLAN_ID = "plan_id";

    private final SecretKey key;
    private final long expirationSeconds;

    public ShareTokenProvider(ShareProperties props) {
        this.key = deriveKey(props.secret());
        this.expirationSeconds = props.expirationSeconds();
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

    /**
     * plan_id 로 공유 토큰 발급.
     *
     * @return 토큰 문자열과 만료 시각(UTC).
     */
    public Issued encode(String planId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expirationSeconds, ChronoUnit.SECONDS);
        String token = Jwts.builder()
                .claim(CLAIM_PLAN_ID, planId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new Issued(token, expiresAt);
    }

    /**
     * 공유 토큰 디코드 → plan_id 반환.
     *
     * @throws ShareTokenException 만료/서명 불일치/페이로드 손상 등 모든 무효 케이스.
     */
    public String decode(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new ShareTokenException("공유 토큰이 만료되었습니다.");
        } catch (JwtException | IllegalArgumentException e) {
            throw new ShareTokenException("유효하지 않은 공유 토큰입니다.");
        }

        String planId = claims.get(CLAIM_PLAN_ID, String.class);
        if (planId == null || planId.isEmpty()) {
            throw new ShareTokenException("유효하지 않은 공유 토큰입니다.");
        }
        return planId;
    }

    /** 토큰 발급 결과 (토큰 + 만료 시각). */
    public record Issued(String token, Instant expiresAt) {
    }
}
