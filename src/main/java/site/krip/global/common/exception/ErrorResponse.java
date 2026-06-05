package site.krip.global.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 탈퇴 유예(419) 응답은 {@code status} 필드도 함께 내려준다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String detail, String status) {

    public static ErrorResponse of(String detail) {
        return new ErrorResponse(detail, null);
    }

    public static ErrorResponse of(String detail, String status) {
        return new ErrorResponse(detail, status);
    }
}
