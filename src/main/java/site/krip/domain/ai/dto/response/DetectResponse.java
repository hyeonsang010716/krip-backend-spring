package site.krip.domain.ai.dto.response;

/** 언어 감지 결과 — {@code lang_code}: 감지된 언어 코드(ko | en). */
public record DetectResponse(String langCode) {
}
