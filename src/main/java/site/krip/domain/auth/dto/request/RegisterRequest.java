package site.krip.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.TravelStyle;
import site.krip.global.common.validation.CodePointSize;

import java.util.List;

/** 2차 회원가입 요청. JSON 은 snake_case. 길이/범위 제약은 DB 컬럼과 일치(초과 시 500 대신 400). */
public record RegisterRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank @CodePointSize(max = 100) String userName,
        @NotBlank @Size(max = 20) @Pattern(regexp = "^[0-9+\\-() ]+$",
                message = "전화번호 형식이 올바르지 않습니다.") String phoneNumber,
        @NotNull @Min(1) @Max(150) Integer age,
        @NotNull Gender gender,
        @NotBlank @CodePointSize(max = 50) String nationality,
        @Size(max = 40) List<TravelStyle> travelStyles // TravelStyle 전체 개수 상한
) {
    public RegisterRequest {
        if (travelStyles == null) {
            travelStyles = List.of();
        }
    }
}
