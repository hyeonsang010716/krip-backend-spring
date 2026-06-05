package site.krip.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.TravelStyle;

import java.util.List;

/**
 * 프로필 부분 수정 요청 — 각 필드 {@code null} = 변경 없음.
 * {@code travelStyles}: {@code null} 변경 없음 / {@code []} 전체 삭제 / {@code [..]} 전체 교체.
 */
public record ProfileUpdateRequest(
        // null = 변경 없음. @Email 은 null 을 통과시켜 값이 올 때만 형식 검증. @NotBlank 류는 부분 수정 의미를 깨므로 미사용.
        @Email String email,
        String userName,
        String phoneNumber,
        Integer age,
        Gender gender,
        String nationality,
        List<TravelStyle> travelStyles
) {
}
