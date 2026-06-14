package site.krip.domain.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 언어 감지 요청 — FastAPI 본문과 동일 형태. */
public record DetectRequest(
        @NotBlank(message = "text 는 필수입니다.")
        @Size(max = 5000, message = "text 는 5000자를 초과할 수 없습니다.")
        String text
) {
}
