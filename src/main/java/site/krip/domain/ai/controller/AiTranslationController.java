package site.krip.domain.ai.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.ai.dto.request.DetectRequest;
import site.krip.domain.ai.dto.request.TranslateRequest;
import site.krip.domain.ai.dto.response.DetectResponse;
import site.krip.domain.ai.dto.response.TranslateResponse;
import site.krip.domain.ai.service.AiTranslationService;

/** 번역/언어감지 API — 추론은 FastAPI(Papago)에 위임. */
@RestController
@RequestMapping("/api/translation")
public class AiTranslationController {

    private final AiTranslationService translationService;

    public AiTranslationController(AiTranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping("/detect")
    @ResponseStatus(HttpStatus.OK)
    public DetectResponse detect(@Valid @RequestBody DetectRequest body) {
        return translationService.detect(body);
    }

    @PostMapping("/translate")
    @ResponseStatus(HttpStatus.OK)
    public TranslateResponse translate(@Valid @RequestBody TranslateRequest body) {
        return translationService.translate(body);
    }
}
