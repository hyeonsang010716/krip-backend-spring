package site.krip.domain.feed.service.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ProcessedImageSet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageProcessor} 의 WEBP 알파 판정 / EXIF 회전 경로 단위 테스트.
 * ImageIO 로는 WEBP 인코딩·EXIF 기록이 불가해 Pillow 로 만든 픽스처(src/test/resources/images)를 쓴다.
 */
@DisplayName("피드 이미지 엣지 — WEBP 재인코딩·EXIF 회전")
class FeedImageEdgeCaseTest {

    private final ImageProcessor processor = new ImageProcessor();

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = FeedImageEdgeCaseTest.class.getResourceAsStream("/images/" + name)) {
            assertThat(in).as("픽스처 누락: " + name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("큰 불투명 WEBP — 재인코딩 시 JPEG (투명 픽셀 없음)")
    void opaqueWebpReencodedAsJpeg() throws Exception {
        ProcessedImageSet result = processor.process(fixture("opaque-large.webp"));

        assertThat(result.original().contentType()).isEqualTo("image/jpeg");
        assertThat(result.original().fileExt()).isEqualTo("jpg");
    }

    @Test
    @DisplayName("큰 투명 WEBP — 재인코딩 시 PNG (알파 보존)")
    void transparentWebpReencodedAsPng() throws Exception {
        ProcessedImageSet result = processor.process(fixture("alpha-large.webp"));

        assertThat(result.original().contentType()).isEqualTo("image/png");
        assertThat(result.original().fileExt()).isEqualTo("png");
    }

    @Test
    @DisplayName("EXIF Orientation=6 — 회전 적용으로 원본 치수가 swap (120x80 → 80x120)")
    void exifOrientationApplied() throws Exception {
        ProcessedImageSet result = processor.process(fixture("exif-orient6.jpg"));

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.original().data()));
        assertThat(out).isNotNull();
        // 저장 120x80(landscape) + orientation 6 → 표시 기준 80x120(portrait)로 회전 적용.
        assertThat(out.getWidth()).isEqualTo(80);
        assertThat(out.getHeight()).isEqualTo(120);
    }
}
