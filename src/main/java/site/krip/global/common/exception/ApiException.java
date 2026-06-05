package site.krip.global.common.exception;

/**
 * 도메인/애플리케이션 예외의 베이스. {@code status} 는 응답 HTTP 코드.
 * 예외 타입별 status 로 옮긴다. {@link GlobalExceptionHandler} 가 {@code {"detail": ...}}
 * 바디로 직렬화한다.
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
