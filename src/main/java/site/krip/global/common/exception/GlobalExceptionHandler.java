package site.krip.global.common.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
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

import java.sql.SQLException;

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
        // 5xx ApiException(예: ChatUpstreamException)은 실서버 진단 위해 로깅 — 4xx 는 정상 흐름이라 미로깅.
        if (e.getStatus() >= 500) {
            log.error("ApiException {} 처리", e.getStatus(), e);
        }
        // 419(탈퇴 유예)는 status 필드도 함께 내려준다.
        String statusField = e.getStatus() == ApiException.WITHDRAWAL_PENDING_STATUS
                ? ApiException.WITHDRAWAL_PENDING_FIELD : null;
        return ResponseEntity.status(e.getStatus())
                .body(ErrorResponse.of(e.getMessage(), statusField));
    }

    /** {@code @Validated} 파라미터(@RequestParam/@PathVariable) 제약 위반 → 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("요청 파라미터가 올바르지 않습니다.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(detail));
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
     * 우리가 던진 검증용 메시지(예: 미지원 OAuth 제공자)만 노출하고, 숫자/타입 변환 내부 메시지는 감춘다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String safe = safeCauseMessage(e.getMostSpecificCause());
        if (safe == null) {
            log.warn("파라미터 변환 실패 (param={})", e.getName(), e);
        }
        String detail = safe != null ? safe : "잘못된 요청 파라미터입니다: " + e.getName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(detail));
    }

    /**
     * 요청 본문 역직렬화 실패 → 400.
     * {@code @JsonCreator} 등이 던진 검증용 메시지만 노출하고, JSON 문법 오류·파서 내부 메시지는 감춘다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        String safe = safeCauseMessage(e.getMostSpecificCause());
        if (safe == null) {
            log.warn("요청 본문 해석 실패", e);
        }
        String detail = safe != null ? safe : "요청 본문을 해석할 수 없습니다.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(detail));
    }

    /**
     * 노출 가능한 원인 메시지만 반환 — 우리가 검증용으로 던진 {@link IllegalArgumentException} 만 허용.
     * {@link NumberFormatException}(입력값 노출)·기타 파서/변환 내부 예외는 감추려고 null 을 반환한다.
     */
    private static @Nullable String safeCauseMessage(Throwable root) {
        if (root instanceof IllegalArgumentException && !(root instanceof NumberFormatException)
                && root.getMessage() != null) {
            return root.getMessage();
        }
        return null;
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

    /**
     * DB 무결성 위반 — UNIQUE 위반(동시성 race)만 409, 그 외(NOT NULL/FK/CHECK 등 서버측 결함)는 500.
     * 도메인 코드가 사전 검사로 막지 못한 경쟁 상태를 표준 409 로 변환하되, 진짜 결함은 500 으로 남겨 관측성을 보존한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        if (isUniqueViolation(e)) {
            log.warn("UNIQUE 제약 위반 → 409: {}", e.getMostSpecificCause().toString());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("이미 존재하거나 동시에 처리 중인 요청입니다."));
        }
        log.error("데이터 무결성 위반", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("서버 내부 오류가 발생했습니다."));
    }

    /** 낙관적 락(@Version) 충돌 → 409. 같은 행을 동시에 수정해 lost-update 가 차단된 경우. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌 → 409: {}", e.toString());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("다른 변경과 충돌했습니다. 다시 시도해주세요."));
    }

    /** PostgreSQL UNIQUE 위반(SQLState 23505) 또는 {@link DuplicateKeyException} 여부 — 원인 체인을 따라 확인. */
    private static boolean isUniqueViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            Throwable cause = t.getCause();
            t = (cause == t) ? null : cause;
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("서버 내부 오류가 발생했습니다."));
    }
}
