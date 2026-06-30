package site.krip.domain.feed.service.image;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.common.exception.ApiException;
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ProcessedVariant;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.krip.domain.feed.service.image.ImageTestSupport.indexOf;
import static site.krip.domain.feed.service.image.ImageTestSupport.realJpeg;

/**
 * ImageProcessor.sanitize 보안 검증 — "항상 재인코딩"으로 폴리글랏(이미지+트레일링 페이로드)을 무력화하는지 실 바이트로 확인.
 * 기존엔 mock·비이미지 입력으로만 닿아 재인코딩 누락 회귀를 못 잡는 false-green 이었다.
 */
@DisplayName("이미지 새니타이즈 — 폴리글랏 무력화·항상 재인코딩")
class ImageSanitizeTest {

    private static final byte[] MARKER = "__POLYGLOT_PAYLOAD_MARKER__".getBytes(StandardCharsets.US_ASCII);

    private final ImageProcessor processor = new ImageProcessor();

    @BeforeAll
    static void noDiskCache() {
        ImageIO.setUseCache(false);
    }

    @Test
    @DisplayName("폴리글랏(JPEG + 트레일링 스크립트)을 재인코딩으로 무력화 — 트레일링 페이로드가 출력에서 사라진다")
    void neutralizesPolyglot() throws Exception {
        ByteArrayOutputStream poly = new ByteArrayOutputStream();
        poly.write(realJpeg());
        poly.write("<?php system($_GET['c']); ?>".getBytes(StandardCharsets.US_ASCII));
        poly.write(MARKER);
        byte[] polyglot = poly.toByteArray();
        assertThat(indexOf(polyglot, MARKER)).as("입력엔 마커가 있어야").isGreaterThan(0);

        ProcessedVariant out = processor.sanitize(polyglot);

        // 출력은 순수 픽셀로 재인코딩된 새 이미지 — 트레일링 페이로드가 남으면 안 된다.
        assertThat(indexOf(out.data(), MARKER))
                .as("재인코딩된 출력에 트레일링 페이로드가 남으면 안 됨").isEqualTo(-1);
        assertThat(ImageIO.read(new ByteArrayInputStream(out.data())))
                .as("출력은 유효한 디코드 가능 이미지여야").isNotNull();
        assertThat(out.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("정상 입력도 항상 재인코딩 — passthrough 가 아니라 새 bytes 를 만든다(메타데이터 제거)")
    void alwaysReencodes() throws Exception {
        byte[] jpeg = realJpeg();

        ProcessedVariant out = processor.sanitize(jpeg);

        assertThat(out.data()).isNotEmpty();
        // 원본을 그대로 통과시키면(재인코딩 skip 회귀) EXIF/폴리글랏 제거가 안 된다.
        assertThat(out.data()).isNotEqualTo(jpeg);
        assertThat(ImageIO.read(new ByteArrayInputStream(out.data()))).isNotNull();
    }

    @Test
    @DisplayName("이미지가 아닌 입력은 포맷 화이트리스트로 거부한다")
    void rejectsNonImage() {
        assertThatThrownBy(() -> processor.sanitize(new byte[]{1, 2, 3, 4, 5}))
                .isInstanceOf(ApiException.class);
    }
}
