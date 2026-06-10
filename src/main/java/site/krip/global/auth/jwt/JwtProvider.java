package site.krip.global.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;
import site.krip.global.config.AuthProperties;
import site.krip.global.support.SecretKeys;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * 유저 로그인 JWT 발급/검증 (HS256).
 *
 * <p>페이로드: {@code user_id}, {@code jti}, {@code iat}, {@code exp}.
 * jti 는 로그아웃 단건 폐기({@link TokenRevocationService})용 식별자.
 * secret 은 {@link SecretKeys} 가 SHA-256 으로 파생하며, 약한/누락 secret 은 부팅 시 거부한다.
 */
@Component
public class JwtProvider {

    private static final String CLAIM_USER_ID = "user_id";

    /** 검증된 토큰에서 추출한 폐기·인증용 클레임. */
    public record ParsedToken(String userId, String jti, Instant expiresAt) {
    }

    private final SecretKey key;
    private final long expirationSeconds;
    private final Clock clock;

    public JwtProvider(AuthProperties props, Clock clock) {
        this.key = SecretKeys.hmacSha256(props.jwt().secret(), "로그인 JWT");
        this.expirationSeconds = props.jwt().expirationSeconds();
        this.clock = clock;
    }

    /** user_id 로 로그인 JWT 발급 (jti 포함). */
    public String issue(String userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USER_ID, userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationSeconds, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();
    }

    /**
     * JWT 를 검증하고 user_id·jti·만료를 추출.
     *
     * @throws io.jsonwebtoken.ExpiredJwtException 만료
     * @throws io.jsonwebtoken.JwtException 그 외 무효 토큰
     */
    public ParsedToken parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Instant exp = claims.getExpiration() != null ? claims.getExpiration().toInstant() : null;
        return new ParsedToken(claims.get(CLAIM_USER_ID, String.class), claims.getId(), exp);
    }
}
