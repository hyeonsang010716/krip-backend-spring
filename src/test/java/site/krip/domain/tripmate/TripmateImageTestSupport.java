package site.krip.domain.tripmate;

import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.FakeStorageConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 이미지 E2E 공통 베이스 — 인메모리 스토리지({@link FakeStorageConfig})와
 * JPEG 멀티파트 빌더·업로드/URL 추출 헬퍼를 모은다.
 */
@Import(FakeStorageConfig.class)
abstract class TripmateImageTestSupport extends TripmateTestSupport {

    protected static final String IMAGES = "/api/tripmate/images";

    /** 8x8 최소 JPEG 멀티파트("files" 파트). */
    protected MockMultipartFile jpeg(String name) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", buf);
        return new MockMultipartFile("files", name, "image/jpeg", buf.toByteArray());
    }

    /** 이미지 1건 업로드 후 image_url 반환. */
    protected String uploadImage(String userId, String name) throws Exception {
        MvcResult res = mockMvc.perform(multipart(IMAGES).file(jpeg(name))
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        return readJson(res).get("images").get(0).get("image_url").asText();
    }
}
