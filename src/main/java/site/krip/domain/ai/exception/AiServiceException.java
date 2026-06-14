package site.krip.domain.ai.exception;

import site.krip.global.common.exception.ApiException;

/**
 * AI 서비스(FastAPI) 호출 실패. {@code status} 는 클라이언트로 내려줄 HTTP 코드 —
 * upstream 상태를 사용자 관점으로 매핑한 값이다(예: connect 실패 → 502, 쿼터 초과 → 429).
 * {@link site.krip.global.common.exception.GlobalExceptionHandler} 가 {@code {"detail": ...}} 로 직렬화한다.
 */
public class AiServiceException extends ApiException {

    public AiServiceException(int status, String message) {
        super(status, message);
    }
}
