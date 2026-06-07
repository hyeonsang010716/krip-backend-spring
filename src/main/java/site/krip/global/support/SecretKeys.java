package site.krip.global.support;

import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC 서명 키 파생 — secret 을 SHA-256 으로 파생해 HS256 키 길이(256bit)를 보장한다.
 *
 * <p>SHA-256 은 입력 길이와 무관하게 32바이트를 만들어 약한/누락 secret 을 가릴 수 있으므로,
 * 파생 전에 비어있음/최소 길이를 강제 검증해 부팅 시점에 fail-fast 한다.
 */
public final class SecretKeys {

    /** HS256 secret 최소 길이(문자). 미만이면 부팅 실패. */
    public static final int MIN_SECRET_LENGTH = 32;

    private SecretKeys() {
    }

    public static SecretKey hmacSha256(String secret, String label) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(label + " secret 이 설정되지 않았습니다.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(label + " secret 은 최소 " + MIN_SECRET_LENGTH
                    + "자 이상이어야 합니다 (현재 " + secret.length() + "자).");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
