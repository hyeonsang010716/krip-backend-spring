package site.krip.global.common.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.krip.global.common.exception.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 이미지 전처리 — 도메인 공용.
 *
 * <p>{@link #process}: 1:1 center crop → small(240)/medium(720) JPEG q80, original 은 한 변 ≤ 2048 +
 * EXIF 회전 없으면 raw bytes 보존(피드 게시물용). {@link #sanitize}: 변형 없이 단일 이미지를 항상 재인코딩해
 * EXIF/메타데이터 제거 + 폴리글랏 무력화(단건 업로드용). 공통 방어선: 포맷 화이트리스트(JPEG/PNG/WEBP),
 * 애니메이션 거절, 디코드 픽셀 cap(50MP). 모든 실패는 400(ApiException).
 */
@Component
public class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);

    private static final int THUMBNAIL_SMALL = 240;
    private static final int THUMBNAIL_MEDIUM = 720;
    private static final int ORIGINAL_MAX = 2048;
    private static final double JPEG_QUALITY = 0.8;
    private static final long MAX_DECODE_PIXELS = 50_000_000L;

    private static final Set<String> ALLOWED_FORMATS = Set.of("JPEG", "PNG", "WEBP");
    private static final Map<String, String[]> FORMAT_META = Map.of(
            "JPEG", new String[]{"image/jpeg", "jpg"},
            "PNG", new String[]{"image/png", "png"},
            "WEBP", new String[]{"image/webp", "webp"});

    /** 원본 bytes → (원본 + small + medium) 3종. 디코딩 1회 공유. */
    public ProcessedImageSet process(byte[] src) {
        Decoded decoded = decode(src);
        BufferedImage img = decoded.image;
        String format = decoded.format;
        return new ProcessedImageSet(
                shrinkOriginal(img, src, format, decoded.wasRotated),
                cropSquareAndResize(img, THUMBNAIL_SMALL),
                cropSquareAndResize(img, THUMBNAIL_MEDIUM));
    }

    /** 단일 정제 이미지 — 항상 재인코딩하여 EXIF/메타데이터 제거 + 폴리글랏 무력화. content-type 은 감지된 포맷에서 도출. */
    public ProcessedVariant sanitize(byte[] src) {
        Decoded decoded = decode(src);
        return reencodeShrunk(decoded.image, decoded.format);
    }

    private Decoded decode(byte[] src) {
        // 1) 헤더 probe — 포맷 화이트리스트 + 픽셀 cap(디컴프레션 봄 방어) + 애니메이션 거절.
        //    ignoreMetadata=true 로 메타데이터 파싱을 건너뛴다(일부 PNG 의 metadata read 오류 회피).
        String format;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(src))) {
            if (iis == null) {
                throw ApiException.badRequest("이미지를 처리할 수 없습니다.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw ApiException.badRequest("지원하지 않는 이미지 포맷입니다.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                format = reader.getFormatName().toUpperCase();
                if (!ALLOWED_FORMATS.contains(format)) {
                    throw ApiException.badRequest("지원하지 않는 이미지 포맷입니다.");
                }
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_DECODE_PIXELS) {
                    throw ApiException.badRequest("이미지 해상도가 너무 큽니다.");
                }
                // 애니메이션 거절 — 헤더 컨테이너 레벨로 감지.
                // ImageIO {@code getNumImages} 는 TwelveMonkeys WEBP/APNG 에서 단일 프레임으로 오인하므로
                // 신뢰하지 않고, WEBP 의 VP8X ANIM 플래그 / PNG 의 acTL 청크를 직접 확인한다.
                if (isAnimated(src, format)) {
                    throw ApiException.badRequest("애니메이션 이미지는 업로드할 수 없습니다.");
                }
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("이미지 헤더 probe 실패: {}", e.toString());
            throw ApiException.badRequest("이미지를 처리할 수 없습니다.");
        }

        // 2) 디코딩 — ImageIO.read 는 메타데이터를 무시하고 첫 프레임을 안전하게 디코딩.
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(src));
        } catch (Exception e) {
            log.warn("이미지 디코딩 실패: {}", e.toString());
            throw ApiException.badRequest("이미지를 처리할 수 없습니다.");
        }
        if (image == null) {
            throw ApiException.badRequest("이미지를 처리할 수 없습니다.");
        }

        int orientation = readExifOrientation(src);
        boolean wasRotated = orientation != 1;
        if (wasRotated) {
            image = applyExifOrientation(image, orientation);
        }
        return new Decoded(image, format, wasRotated);
    }

    private ProcessedVariant cropSquareAndResize(BufferedImage src, int target) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        BufferedImage square = flattenToRgb(src.getSubimage(x, y, side, side));
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thumbnails.of(square).size(target, target)
                    .outputFormat("jpg").outputQuality(JPEG_QUALITY).toOutputStream(buf);
            return new ProcessedVariant(buf.toByteArray(), "image/jpeg", "jpg");
        } catch (Exception e) {
            throw ApiException.badRequest("이미지를 처리할 수 없습니다.");
        }
    }

    private ProcessedVariant shrinkOriginal(BufferedImage img, byte[] src, String format, boolean wasRotated) {
        boolean needsShrink = Math.max(img.getWidth(), img.getHeight()) > ORIGINAL_MAX;
        if (!needsShrink && !wasRotated) {
            String[] meta = FORMAT_META.get(format);
            return new ProcessedVariant(src, meta[0], meta[1]);
        }
        return reencodeShrunk(img, format);
    }

    /** 한 변 ≤ 2048 로 축소 후 포맷별 재인코딩 — 항상 새 bytes 를 만들어 EXIF/메타데이터를 제거한다. */
    private ProcessedVariant reencodeShrunk(BufferedImage img, String format) {
        boolean needsShrink = Math.max(img.getWidth(), img.getHeight()) > ORIGINAL_MAX;
        try {
            BufferedImage work = img;
            if (needsShrink) {
                work = Thumbnails.of(img).size(ORIGINAL_MAX, ORIGINAL_MAX).asBufferedImage();
            }
            if ("JPEG".equals(format)) {
                return encodeJpeg(flattenToRgb(work));
            }
            if ("WEBP".equals(format)) {
                // TwelveMonkeys 는 불투명 WEBP 도 ARGB 래스터로 디코딩해 getColorModel().hasAlpha() 가
                // true 를 반환하므로(색상모델은 알파 "지원 여부"만 알려줌) 부정확하다. 원본 디코딩 이미지의
                // 실제 픽셀 투명도를 검사해, 불투명 WEBP 가 PNG 로 저장되며 용량이 폭증하는 것을 막는다.
                return hasTransparentPixels(img) ? encodePng(work) : encodeJpeg(flattenToRgb(work));
            }
            return encodePng(work);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("이미지를 처리할 수 없습니다.");
        }
    }

    private ProcessedVariant encodeJpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thumbnails.of(img).scale(1.0).outputFormat("jpg").outputQuality(JPEG_QUALITY).toOutputStream(buf);
        return new ProcessedVariant(buf.toByteArray(), "image/jpeg", "jpg");
    }

    private ProcessedVariant encodePng(BufferedImage img) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "png", buf);
        return new ProcessedVariant(buf.toByteArray(), "image/png", "png");
    }

    /** JPEG 인코딩 전 alpha 제거 — RGBA → RGB 흰배경. alpha 없으면 그대로. */
    private static BufferedImage flattenToRgb(BufferedImage img) {
        if (!img.getColorModel().hasAlpha() && img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /**
     * 실제 투명 픽셀(alpha != 255) 존재 여부.
     *
     * <p>{@link java.awt.image.ColorModel#hasAlpha()} 는 "알파 채널 지원 여부"만 알려주므로, 디코더가
     * 불투명 이미지를 ARGB 로 디코딩하면 true 가 되어 부정확하다. 픽셀을 직접 스캔해 하나라도 비-불투명
     * 픽셀이 있으면 투명 이미지로 간주한다(행 단위 bulk getRGB + 조기 종료로 비용 제한).
     */
    private static boolean hasTransparentPixels(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int argb : row) {
                if ((argb & 0xFF000000) != 0xFF000000) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 애니메이션 여부를 컨테이너 헤더로 판정.
     *
     * <p>WEBP: {@code RIFF....WEBP} 의 {@code VP8X} 확장 청크 플래그 바이트(offset 20)의 ANIM 비트(0x02).
     * PNG: {@code IDAT} 이전에 {@code acTL}(APNG animation control) 청크가 있으면 애니메이션.
     * JPEG 및 그 외는 단일 프레임으로 간주.
     */
    private static boolean isAnimated(byte[] src, String format) {
        if ("WEBP".equals(format)) {
            return isAnimatedWebp(src);
        }
        if ("PNG".equals(format)) {
            return isApng(src);
        }
        return false;
    }

    private static boolean isAnimatedWebp(byte[] b) {
        // RIFF????WEBP + VP8X 확장 헤더 + 플래그 바이트(20)의 0x02(ANIM) 비트.
        if (b.length < 21) {
            return false;
        }
        if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'E' || b[10] != 'B' || b[11] != 'P') {
            return false;
        }
        if (b[12] != 'V' || b[13] != 'P' || b[14] != '8' || b[15] != 'X') {
            return false; // VP8/VP8L 단순 정지 WEBP — 애니메이션 불가
        }
        return (b[20] & 0x02) != 0;
    }

    private static boolean isApng(byte[] b) {
        // PNG 시그니처(8) 이후 청크를 순회하며 IDAT 전에 acTL 이 나오면 APNG.
        if (b.length < 8) {
            return false;
        }
        int pos = 8;
        while (pos + 8 <= b.length) {
            long len = ((b[pos] & 0xFFL) << 24) | ((b[pos + 1] & 0xFFL) << 16)
                    | ((b[pos + 2] & 0xFFL) << 8) | (b[pos + 3] & 0xFFL);
            char c1 = (char) (b[pos + 4] & 0xFF), c2 = (char) (b[pos + 5] & 0xFF),
                    c3 = (char) (b[pos + 6] & 0xFF), c4 = (char) (b[pos + 7] & 0xFF);
            String type = "" + c1 + c2 + c3 + c4;
            if ("acTL".equals(type)) {
                return true;
            }
            if ("IDAT".equals(type) || "IEND".equals(type)) {
                return false;
            }
            if (len < 0 || len > b.length) {
                return false;
            }
            pos += 12 + (int) len; // length(4) + type(4) + data(len) + crc(4)
        }
        return false;
    }

    private static int readExifOrientation(byte[] src) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(src));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            // EXIF 부재/파싱 실패 → 회전 없음으로 간주
        }
        return 1;
    }

    /** EXIF Orientation(2~8) 을 픽셀에 적용. */
    private static BufferedImage applyExifOrientation(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        boolean swapDims = false;
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); swapDims = true; }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); swapDims = true; }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(-Math.PI / 2); swapDims = true; }
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); swapDims = true; }
            default -> { return img; }
        }
        int nw = swapDims ? h : w;
        int nh = swapDims ? w : h;
        BufferedImage dst = new BufferedImage(nw, nh,
                img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(img, new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR), 0, 0);
        g.dispose();
        return dst;
    }

    private record Decoded(BufferedImage image, String format, boolean wasRotated) {
    }
}
