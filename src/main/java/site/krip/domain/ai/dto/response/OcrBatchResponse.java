package site.krip.domain.ai.dto.response;

import java.util.List;

/** 메뉴 OCR 다건 결과 — 이미지별 {@link OcrResponse} 목록. */
public record OcrBatchResponse(List<OcrResponse> results) {
}
