package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 플랜 공유 토큰 설정.
 *
 * <p>로그인 JWT 와는 별개의 비밀키/만료 정책을 가진다.
 */
@ConfigurationProperties(prefix = "krip.share")
public record ShareProperties(
        String secret,
        int expirationDays
) {
    public long expirationSeconds() {
        return (long) expirationDays * 24 * 60 * 60;
    }
}
