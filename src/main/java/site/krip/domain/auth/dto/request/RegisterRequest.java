package site.krip.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.TravelStyle;

import java.util.List;

/** 2차 회원가입 요청. JSON 은 snake_case. */
public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank String userName,
        @NotBlank String phoneNumber,
        @NotNull Integer age,
        @NotNull Gender gender,
        @NotBlank String nationality,
        List<TravelStyle> travelStyles
) {
    public RegisterRequest {
        if (travelStyles == null) {
            travelStyles = List.of();
        }
    }
}
