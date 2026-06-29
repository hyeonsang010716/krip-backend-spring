package site.krip.domain.feed.service.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 이미지 처리 테스트 공통 헬퍼 — 실 이미지 생성/인코딩과 바이트 검색을 공유한다. */
final class ImageTestSupport {

    private ImageTestSupport() {
    }

    /** 비단색(대각 그라데이션) 이미지 — JPEG 인코딩이 균일색을 거부하지 않도록. */
    static BufferedImage makeImage(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < h; y += 8) {
            g.setColor(new Color((y * 7) % 255, (y * 3) % 255, (y * 5) % 255));
            g.fillRect(0, y, w, 8);
        }
        g.dispose();
        return img;
    }

    static byte[] encode(BufferedImage img, String fmt) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, fmt, buf)).as("ImageIO should encode " + fmt).isTrue();
        return buf.toByteArray();
    }

    /** 64x64 실제 JPEG. */
    static byte[] realJpeg() throws Exception {
        return encode(makeImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    /** needle 의 첫 등장 인덱스, 없으면 -1. */
    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
