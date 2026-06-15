package site.krip.domain.ai.service;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import site.krip.domain.ai.client.AiServiceClient;
import site.krip.domain.ai.dto.response.OcrBatchResponse;
import site.krip.domain.ai.dto.response.OcrResponse;
import site.krip.global.common.exception.ApiException;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 메뉴 OCR — 이미지 추론은 FastAPI(Gemini)에 위임하는 프록시. DB 의존 없음.
 *
 * <p>파일 형식/크기/개수 1차 검증은 여기서 하고(잘못된 업로드를 AI 호출 전에 차단), 통과분만 multipart 로 전달한다.
 */
@Service
public class AiOcrService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/bmp", "image/webp", "image/tiff");
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_FILE_COUNT = 5;

    private final AiServiceClient ai;

    public AiOcrService(AiServiceClient ai) {
        this.ai = ai;
    }

    public OcrResponse ocrSingle(MultipartFile file) {
        validate(file);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        addFilePart(builder, "file", file);
        return ai.postMultipart("/api/menu-ai/ocr", builder.build(), OcrResponse.class);
    }

    public OcrBatchResponse ocrBatch(List<MultipartFile> files) {
        if (files.isEmpty()) {
            throw ApiException.badRequest("이미지를 최소 1개 업로드해야 합니다.");
        }
        if (files.size() > MAX_FILE_COUNT) {
            throw ApiException.badRequest("이미지는 최대 " + MAX_FILE_COUNT + "개까지 업로드할 수 있습니다.");
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        for (MultipartFile file : files) {
            validate(file);
            addFilePart(builder, "files", file);
        }
        return ai.postMultipart("/api/menu-ai/ocr/batch", builder.build(), OcrBatchResponse.class);
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("빈 파일은 업로드할 수 없습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw ApiException.badRequest(
                    "허용되지 않는 파일 형식입니다 (jpeg, png, gif, bmp, webp, tiff만 가능).");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw ApiException.badRequest("파일 크기가 10MB를 초과합니다.");
        }
    }

    /** MultipartFile 을 FastAPI 가 UploadFile 로 받도록 filename + content-type 을 보존해 part 로 추가. */
    private void addFilePart(MultipartBodyBuilder builder, String name, MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("파일을 읽을 수 없습니다.");
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : name;
        builder.part(name, new ByteArrayResource(bytes))
                .filename(filename)
                .contentType(MediaType.parseMediaType(file.getContentType()));
    }
}
