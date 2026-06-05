package site.krip.domain.feed.service.image;

/** 피드 이미지 1장의 다해상도 변형. small=240×240, medium=720×720 JPEG. */
public record ProcessedFeedImage(
        ProcessedVariant original,
        ProcessedVariant small,
        ProcessedVariant medium
) {
}
