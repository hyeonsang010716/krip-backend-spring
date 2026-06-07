package site.krip.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.TravelStyle;
import site.krip.global.common.validation.CodePointSize;

import java.util.List;

/**
 * 프로필 부분 수정 요청 — 각 필드 {@code null} = 변경 없음.
 * {@code travelStyles}: {@code null} 변경 없음 / {@code []} 전체 삭제 / {@code [..]} 전체 교체.
 *
 * <p>모든 제약은 {@code null} 을 통과시켜 부분 수정 의미를 유지하고, 값이 올 때만 검증한다.
 * 길이/범위는 DB 컬럼과 일치(초과 시 500 대신 400). {@code @NotBlank} 류는 부분 수정 의미를 깨므로 미사용.
 */
public record ProfileUpdateRequest(
        @Email @Size(max = 255) String email,
        @CodePointSize(max = 100) String userName,
        @Size(max = 20) @Pattern(regexp = "^[0-9+\\-() ]+$",
                message = "전화번호 형식이 올바르지 않습니다.") String phoneNumber,
        @Min(1) @Max(150) Integer age,
        Gender gender,
        @CodePointSize(max = 50) String nationality,
        @Size(max = 40) List<TravelStyle> travelStyles
) {
}
