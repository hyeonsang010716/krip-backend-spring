package site.krip.domain.feed.service.image;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.common.exception.ApiException;
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ProcessedImageSet;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImageProcessor} 순수 Java 이미지 처리 단위 테스트 — S3/Spring 불필요.
 *
 * <p>java.awt/ImageIO 로 메모리 내 작은 이미지를 만들어 3종 변형(원본/small 240/medium 720)과
 * 1:1 center crop, 포맷 화이트리스트, 50MP cap, APNG/animated-WEBP 거절을 검증한다.
 *
 * <p>한계: TwelveMonkeys WEBP 플러그인은 디코드 전용이라 BufferedImage 로부터 실제 정지 WEBP
 * 바이트를 ImageIO 로 인코딩할 수 없다. 따라서 "정상 WEBP 처리" 케이스는 생략하고, animated WEBP
 * 는 컨테이너 헤더(VP8X ANIM 플래그)를 손으로 만들어 거절만 검증한다.
 */
class FeedImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    @BeforeAll
    static void allowLargeImages() {
        // IHDR 만 큰 PNG 를 probe 하는 케이스를 위해 ImageIO 캐시 비활성화(디스크 IO 회피).
        ImageIO.setUseCache(false);
    }

    // ---- helpers -------------------------------------------------------------

    private static byte[] png(int w, int h) throws Exception {
        return encode(makeImage(w, h, BufferedImage.TYPE_INT_RGB), "png");
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        return encode(makeImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    private static byte[] pngWithAlpha(int w, int h) throws Exception {
        return encode(makeImage(w, h, BufferedImage.TYPE_INT_ARGB), "png");
    }

    private static BufferedImage makeImage(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        // 비단색(대각 그라데이션 느낌) — JPEG 인코딩이 균일색을 거부하지 않도록.
        for (int y = 0; y < h; y += 8) {
            g.setColor(new Color((y * 7) % 255, (y * 3) % 255, (y * 5) % 255));
            g.fillRect(0, y, w, 8);
        }
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String fmt) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, fmt, buf)).as("ImageIO should encode " + fmt).isTrue();
        return buf.toByteArray();
    }

    private static int[] dims(byte[] jpegBytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        assertThat(img).isNotNull();
        return new int[]{img.getWidth(), img.getHeight()};
    }

    // ---- valid image -> 3 variants ------------------------------------------

    @Test
    @DisplayName("정상 PNG -> 원본 + small(240) + medium(720) 3종 변형을 생성한다")
    void validPngProducesThreeVariants() throws Exception {
        ProcessedImageSet result = processor.process(png(1000, 800));

        assertThat(result.original()).isNotNull();
        assertThat(result.small()).isNotNull();
        assertThat(result.medium()).isNotNull();

        // small/medium 은 항상 JPEG.
        assertThat(result.small().contentType()).isEqualTo("image/jpeg");
        assertThat(result.small().fileExt()).isEqualTo("jpg");
        assertThat(result.medium().contentType()).isEqualTo("image/jpeg");

        // small = 240x240, medium = 720x720 (1:1 center crop).
        assertThat(dims(result.small().data())).containsExactly(240, 240);
        assertThat(dims(result.medium().data())).containsExactly(720, 720);
    }

    @Test
    @DisplayName("정상 JPEG -> 3종 변형, small/medium 정사각형")
    void validJpegProducesThreeVariants() throws Exception {
        ProcessedImageSet result = processor.process(jpeg(1200, 900));

        assertThat(dims(result.small().data())).containsExactly(240, 240);
        assertThat(dims(result.medium().data())).containsExactly(720, 720);
        assertThat(result.original().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("작은 JPEG(2048 이하, 미회전)의 원본은 입력 bytes 를 그대로 보존한다")
    void smallJpegOriginalPreservesBytes() throws Exception {
        byte[] src = jpeg(800, 600);
        ProcessedImageSet result = processor.process(src);
        // 한 변 <= 2048 이고 EXIF 회전 없음 -> 원본 raw bytes 보존.
        assertThat(result.original().data()).isSameAs(src);
        assertThat(result.original().contentType()).isEqualTo("image/jpeg");
        assertThat(result.original().fileExt()).isEqualTo("jpg");
    }

    @Test
    @DisplayName("작은 PNG(미회전)의 원본은 image/png 으로 raw bytes 보존")
    void smallPngOriginalPreservesBytes() throws Exception {
        byte[] src = png(500, 500);
        ProcessedImageSet result = processor.process(src);
        assertThat(result.original().data()).isSameAs(src);
        assertThat(result.original().contentType()).isEqualTo("image/png");
        assertThat(result.original().fileExt()).isEqualTo("png");
    }

    @Test
    @DisplayName("2048 초과 원본은 한 변 <= 2048 로 축소된다")
    void oversizedOriginalIsShrunkTo2048() throws Exception {
        // 3000x1500 -> longest side 2048.
        ProcessedImageSet result = processor.process(jpeg(3000, 1500));
        int[] d = dims(result.original().data());
        assertThat(Math.max(d[0], d[1])).isLessThanOrEqualTo(2048);
        // 종횡비 유지 -> 2048 x 1024 근처.
        assertThat(d[0]).isEqualTo(2048);
    }

    // ---- center crop ---------------------------------------------------------

    @Test
    @DisplayName("가로로 긴 이미지도 small/medium 은 정사각(center crop)으로 만들어진다")
    void wideImageCenterCroppedToSquare() throws Exception {
        ProcessedImageSet result = processor.process(png(1600, 400));
        assertThat(dims(result.small().data())).containsExactly(240, 240);
        assertThat(dims(result.medium().data())).containsExactly(720, 720);
    }

    @Test
    @DisplayName("세로로 긴 이미지도 정사각 변형이 만들어진다")
    void tallImageCenterCroppedToSquare() throws Exception {
        ProcessedImageSet result = processor.process(png(400, 1600));
        assertThat(dims(result.small().data())).containsExactly(240, 240);
        assertThat(dims(result.medium().data())).containsExactly(720, 720);
    }

    @Test
    @DisplayName("medium 보다 작은 원본도 변형은 240/720 으로 업스케일된다")
    void smallSourceUpscaledForVariants() throws Exception {
        // 300x300 -> medium 720 까지 업스케일.
        ProcessedImageSet result = processor.process(png(300, 300));
        assertThat(dims(result.small().data())).containsExactly(240, 240);
        assertThat(dims(result.medium().data())).containsExactly(720, 720);
    }

    @Test
    @DisplayName("알파 PNG 도 정상 처리되어 정사각 JPEG 변형을 만든다")
    void alphaPngProcessed() throws Exception {
        ProcessedImageSet result = processor.process(pngWithAlpha(800, 800));
        assertThat(dims(result.small().data())).containsExactly(240, 240);
        // 알파 -> JPEG 변형은 흰 배경으로 flatten (예외 없이 처리되면 충분).
        assertThat(result.small().contentType()).isEqualTo("image/jpeg");
    }

    // ---- rejection cases -----------------------------------------------------

    @Test
    @DisplayName("이미지가 아닌 임의 bytes 는 400 ApiException 으로 거절")
    void garbageBytesRejected() {
        byte[] garbage = "this is definitely not an image".getBytes();
        assertThatThrownBy(() -> processor.process(garbage))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("화이트리스트 외 포맷(GIF)은 거절된다")
    void gifFormatRejected() throws Exception {
        byte[] gif = encode(makeImage(100, 100, BufferedImage.TYPE_INT_RGB), "gif");
        assertThatThrownBy(() -> processor.process(gif))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("지원하지 않는");
    }

    @Test
    @DisplayName("BMP 포맷도 화이트리스트 밖이므로 거절")
    void bmpFormatRejected() throws Exception {
        byte[] bmp = encode(makeImage(100, 100, BufferedImage.TYPE_INT_RGB), "bmp");
        assertThatThrownBy(() -> processor.process(bmp))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("선언 해상도가 50MP 를 넘는 PNG 는 디코드 전에 거절된다(decompression bomb 방어)")
    void oversizedPixelCountRejected() throws Exception {
        // 실제 거대 이미지를 만들지 않고, 작은 PNG 의 IHDR width/height 만 8000x8000(64MP)로 패치.
        byte[] bomb = pngWithPatchedDimensions(8000, 8000);
        assertThatThrownBy(() -> processor.process(bomb))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("해상도");
    }

    @Test
    @DisplayName("APNG(acTL 청크)는 애니메이션으로 거절된다")
    void apngRejected() throws Exception {
        byte[] apng = makeApng(200, 200);
        assertThatThrownBy(() -> processor.process(apng))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("애니메이션");
    }

    @Test
    @DisplayName("animated WEBP(VP8X ANIM 플래그) 헤더는 거절된다")
    void animatedWebpRejected() {
        byte[] webp = makeAnimatedWebpHeader();
        // 헤더만 craft 했으므로 reader 가 정상 디코드하지 못할 수 있다. 어느 경로든 400 거절이면 충분.
        assertThatThrownBy(() -> processor.process(webp))
                .isInstanceOf(ApiException.class);
    }

    // ---- PNG byte crafting helpers ------------------------------------------

    /** 작은 PNG 를 만든 뒤 IHDR 의 width/height 4바이트씩을 큰 값으로 덮어쓴다(+CRC 재계산). */
    private static byte[] pngWithPatchedDimensions(int newW, int newH) throws Exception {
        byte[] png = png(64, 64);
        // PNG sig(8) + IHDR length(4) + "IHDR"(4) -> width at offset 16, height at 20.
        // IHDR data = 13 bytes; CRC over type+data starts at 12+? compute: CRC covers "IHDR"+13 data bytes.
        int ihdrTypeOff = 12;       // "IHDR"
        int widthOff = 16;          // first data byte
        int heightOff = 20;
        writeInt(png, widthOff, newW);
        writeInt(png, heightOff, newH);
        // CRC: over type(4) + data(13) = bytes [12, 12+17) = [12, 29). CRC stored at 29.
        CRC32 crc = new CRC32();
        crc.update(png, ihdrTypeOff, 4 + 13);
        writeInt(png, ihdrTypeOff + 4 + 13, (int) crc.getValue());
        return png;
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** 정상 PNG 에 IHDR 직후 acTL 청크를 삽입해 APNG 로 만든다(첫 프레임 디코드는 정상). */
    private static byte[] makeApng(int w, int h) throws Exception {
        byte[] png = png(w, h);
        // IHDR chunk = 8(sig) + 4(len) + 4(type) + 13(data) + 4(crc) = 33 bytes. acTL 삽입 위치 = 33.
        int insertAt = 8 + 4 + 4 + 13 + 4;

        // acTL chunk: length=8, type="acTL", data = num_frames(2) + num_plays(0).
        ByteArrayOutputStream actl = new ByteArrayOutputStream();
        byte[] lenBytes = {0, 0, 0, 8};
        actl.write(lenBytes);
        byte[] type = {'a', 'c', 'T', 'L'};
        byte[] data = {0, 0, 0, 2, 0, 0, 0, 0};
        actl.write(type);
        actl.write(data);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        long c = crc.getValue();
        actl.write(new byte[]{(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
        byte[] actlChunk = actl.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, insertAt);
        out.write(actlChunk);
        out.write(png, insertAt, png.length - insertAt);
        return out.toByteArray();
    }

    /** RIFF/WEBP/VP8X 컨테이너 헤더 + ANIM 플래그(0x02)를 손으로 구성. */
    private static byte[] makeAnimatedWebpHeader() {
        byte[] b = new byte[64];
        b[0] = 'R'; b[1] = 'I'; b[2] = 'F'; b[3] = 'F';
        // file size (little-endian), placeholder
        b[4] = 56; b[5] = 0; b[6] = 0; b[7] = 0;
        b[8] = 'W'; b[9] = 'E'; b[10] = 'B'; b[11] = 'P';
        b[12] = 'V'; b[13] = 'P'; b[14] = '8'; b[15] = 'X';
        // VP8X chunk size = 10 (little-endian)
        b[16] = 10; b[17] = 0; b[18] = 0; b[19] = 0;
        // flags byte: ANIM bit (0x02) set
        b[20] = 0x02;
        return b;
    }
}
