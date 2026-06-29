package site.krip.domain.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import site.krip.domain.ai.client.AiServiceClient;
import site.krip.global.common.exception.ApiException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AiOcrService#ocrBatch} 개수 가드 — AI 호출 전에 빈/초과 입력을 400 으로 차단(헛 호출 방지).
 */
class AiOcrServiceTest {

    private static final int BAD_REQUEST = 400;

    private AiServiceClient ai;
    private AiOcrService service;

    @BeforeEach
    void setUp() {
        ai = mock(AiServiceClient.class);
        service = new AiOcrService(ai);
    }

    @Test
    @DisplayName("batch 빈 리스트 → 400, AI 미호출")
    void emptyBatchRejected() {
        assertThatThrownBy(() -> service.ocrBatch(List.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(BAD_REQUEST));
        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("batch 5개 초과 → 400, AI 미호출")
    void tooManyFilesRejected() {
        List<MultipartFile> files = Collections.nCopies(6, mock(MultipartFile.class));
        assertThatThrownBy(() -> service.ocrBatch(files))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(BAD_REQUEST));
        verifyNoInteractions(ai);
    }
}
