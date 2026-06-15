package site.krip.global.auth;

/**
 * 필터가 request 에 심는 속성 키.
 */
public final class RequestAttributes {

    /** LoginAuthFilter 가 심는 현재 토큰의 jti — 로그아웃 폐기에 사용. */
    public static final String JWT_JTI = "krip.jwt_jti";

    /** LoginAuthFilter 가 심는 현재 토큰의 만료 시각(Instant). */
    public static final String JWT_EXP = "krip.jwt_exp";

    private RequestAttributes() {
    }
}
