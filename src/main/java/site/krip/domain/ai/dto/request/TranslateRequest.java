package site.krip.domain.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 번역 요청 — {@code source}/{@code target} 은 ko|en. */
public record TranslateRequest(
        @NotBlank(message = "text 는 필수입니다.")
        @Size(max = 5000, message = "text 는 5000자를 초과할 수 없습니다.")
        String text,

        @NotBlank(message = "source 는 필수입니다.")
        @Pattern(regexp = "ko|en", message = "source 는 ko 또는 en 이어야 합니다.")
        String source,

        @NotBlank(message = "target 은 필수입니다.")
        @Pattern(regexp = "ko|en", message = "target 은 ko 또는 en 이어야 합니다.")
        String target
) {
}
