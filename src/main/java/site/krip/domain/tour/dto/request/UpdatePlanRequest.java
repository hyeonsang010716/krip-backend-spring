package site.krip.domain.tour.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * 플랜 메타 수정 요청 — 현재 title 만 지원.
 *
 * <p>{@code title} 키는 body 에 반드시 있어야 한다({@code required=true}). 값이 null 이면 제목 제거.
 * 공백 정규화/거부는 서비스에서 처리. 키 누락 시 Jackson 역직렬화 단계에서 거부 →
 * {@code HttpMessageNotReadableException} → 400.
 */
public record UpdatePlanRequest(
        @JsonProperty(value = "title", required = true)
        @Size(max = 100) String title
) {
}
