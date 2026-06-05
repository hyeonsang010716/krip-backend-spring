package site.krip.global.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 → {@code {"detail": ...}} 응답 매핑.
 *
 * <p>{@link ApiException} 는 자신의 status 를 그대로 사용 (419 같은 비표준 코드 포함).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        // 419(탈퇴 유예)는 status 필드도 함께 내려준다.
        String statusField = e.getStatus() == 419 ? "withdrawal_pending" : null;
        return ResponseEntity.status(e.getStatus())
                .body(ErrorResponse.of(e.getMessage(), statusField));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("요청 본문이 올바르지 않습니다.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(detail));
    }

    /**
     * 쿼리/경로 파라미터 타입 변환 실패 → 400.
     * 예: {@code type=kakao} (미지원 OAuth 제공자) — 컨버터가 던진 원인 메시지를 detail 로.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Throwable root = e.getMostSpecificCause();
        String detail = (root != null && root.getMessage() != null)
                ? root.getMessage()
                : "잘못된 요청 파라미터입니다: " + e.getName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(detail));
    }

    /**
     * 요청 본문 역직렬화 실패 → 400.
     * {@code @JsonCreator} 가 던진 {@link IllegalArgumentException} 메시지는 detail 로 노출하고,
     * 그 외(JSON 문법 오류 등)는 내부 메시지를 감추고 일반 문구로 응답한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        Throwable root = e.getMostSpecificCause();
        String detail = (root instanceof IllegalArgumentException && root.getMessage() != null)
                ? root.getMessage()
                : "요청 본문을 해석할 수 없습니다.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(detail));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("필수 파라미터가 누락되었습니다: " + e.getParameterName()));
    }

    /** 필수 multipart 파트(파일 등) 누락 → 400. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("필수 파트가 누락되었습니다: " + e.getRequestPartName()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("파일 크기가 허용 한도를 초과합니다."));
    }

    /** 매칭되는 라우트/정적 리소스 없음 → 404. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("요청한 리소스를 찾을 수 없습니다."));
    }

    /** 허용되지 않는 HTTP 메서드 → 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("허용되지 않는 메서드입니다: " + e.getMethod()));
    }

    /** 지원하지 않는 요청 Content-Type → 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of("지원하지 않는 미디어 타입입니다: " + e.getContentType()));
    }

    /** 응답 가능한 표현이 Accept 와 불일치 → 406. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .body(ErrorResponse.of("요청한 표현 형식을 제공할 수 없습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("서버 내부 오류가 발생했습니다."));
    }
}
