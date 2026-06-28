package site.krip.domain.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import site.krip.domain.ai.client.AiServiceClient;
import site.krip.domain.ai.dto.request.DetectRequest;
import site.krip.domain.ai.dto.request.TranslateRequest;
import site.krip.domain.ai.dto.response.DetectResponse;
import site.krip.domain.ai.dto.response.TranslateResponse;
import site.krip.global.common.exception.ApiException;

/**
 * 번역/언어감지 — 모든 추론은 FastAPI(Papago)에 위임하는 프록시. DB 의존 없음.
 */
@Service
@RequiredArgsConstructor
public class AiTranslationService {

    private final AiServiceClient ai;

    public DetectResponse detect(DetectRequest request) {
        return ai.postJson("/api/translation/detect", request, DetectResponse.class);
    }

    public TranslateResponse translate(TranslateRequest request) {
        if (request.source().equals(request.target())) {
            throw ApiException.badRequest("source 와 target 언어가 동일합니다.");
        }
        return ai.postJson("/api/translation/translate", request, TranslateResponse.class);
    }
}
