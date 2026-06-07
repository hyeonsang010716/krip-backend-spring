package site.krip.global.common.image;

/** 다해상도 변형 1세트 — small=240×240, medium=720×720 JPEG. 피드 게시물용. */
public record ProcessedImageSet(
        ProcessedVariant original,
        ProcessedVariant small,
        ProcessedVariant medium
) {
}
