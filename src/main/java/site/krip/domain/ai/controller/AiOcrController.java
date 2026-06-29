package site.krip.domain.ai.controller;

import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import site.krip.domain.ai.dto.response.OcrBatchResponse;
import site.krip.domain.ai.dto.response.OcrResponse;
import site.krip.domain.ai.service.AiOcrService;

import java.util.List;

/** 메뉴 OCR API — 추론은 FastAPI(Gemini)에 위임. */
@RestController
@RequestMapping("/api/menu-ai/ocr")
@RequiredArgsConstructor
@Validated
public class AiOcrController {

    private final AiOcrService ocrService;

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public OcrResponse ocr(@RequestParam("file") MultipartFile file) {
        return ocrService.ocrSingle(file);
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.OK)
    public OcrBatchResponse ocrBatch(
            @RequestParam("files")
            @Size(max = 10, message = "한 번에 최대 10개까지 업로드할 수 있습니다.") List<MultipartFile> files) {
        return ocrService.ocrBatch(files);
    }
}
