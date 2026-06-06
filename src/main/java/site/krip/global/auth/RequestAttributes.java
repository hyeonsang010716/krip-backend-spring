package site.krip.global.auth;

/**
 * 필터가 request 에 심는 속성 키.
 */
public final class RequestAttributes {

    /** RequestIdFilter 가 부여하는 요청 추적 ID. */
    public static final String REQUEST_ID = "krip.request_id";

    private RequestAttributes() {
    }
}
