package site.krip.support;

import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** JWT 계열 토큰 테스트 공용 헬퍼 — secret→HS256 키 파생, 서명 변조. (JwtProvider/ShareTokenProvider 단위 테스트 공유) */
public final class TokenTestSupport {

    private TokenTestSupport() {
    }

    /** 운영과 동일하게 secret 을 SHA-256 으로 파생해 HMAC 키를 만든다. */
    public static SecretKey deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 서명부 첫 문자를 변조한다. 마지막 문자는 base64url 미사용 비트가 있어 a↔b 치환이 같은 바이트로
     * 디코딩될 수 있어(서명 유효 유지) flaky 하므로 상위 6비트가 모두 유효한 첫 문자를 바꾼다.
     */
    public static String tamperSignature(String token) {
        int sigStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(sigStart);
        char swapped = first == 'a' ? 'b' : 'a';
        return token.substring(0, sigStart) + swapped + token.substring(sigStart + 1);
    }
}
