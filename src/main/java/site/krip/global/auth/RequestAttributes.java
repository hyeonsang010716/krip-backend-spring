package site.krip.global.auth;

/**
 * 필터가 request 에 심는 속성 키.
 */
public final class RequestAttributes {

    /** LoginAuthFilter 가 JWT 에서 추출해 저장하는 user_id. */
    public static final String USER_ID = "krip.user_id";

    /** RequestIdFilter 가 부여하는 요청 추적 ID. */
    public static final String REQUEST_ID = "krip.request_id";

    private RequestAttributes() {
    }
}
