package site.krip.domain.ai.dto.response;

import java.util.List;

/** 메뉴 OCR 단건 결과 — FastAPI 응답과 동일 형태. */
public record OcrResponse(List<MenuItem> menus) {

    /** 추출된 메뉴 1건. {@code price} 는 통화 기호 제거된 정수(KRW). */
    public record MenuItem(
            String originalName,
            String englishName,
            String description,
            int price
    ) {
    }
}
