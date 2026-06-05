package site.krip.domain.feed.service.image;

/** 단일 변형 처리 결과 — 그대로 S3 업로드 가능한 bytes + 메타. */
public record ProcessedVariant(byte[] data, String contentType, String fileExt) {
}
