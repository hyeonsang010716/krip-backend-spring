package site.krip.domain.tripmate.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.domain.tripmate.repository.TripmateImageRepository;
import site.krip.domain.tripmate.repository.TripmatePostDraftRepository;
import site.krip.domain.tripmate.repository.TripmatePostImageRepository;
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ImageUploadExecutor;
import site.krip.global.common.image.ProcessedVariant;
import site.krip.global.storage.ObjectStorage;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TripmateImageService} 고아 보상 단위 테스트 — 업로드 저장 실패 시 S3 보상 삭제(누수 방지),
 * 삭제는 DB-우선 후 best-effort 스토리지 정리를 검증한다.
 */
@DisplayName("트립메이트 이미지 서비스 — Mongo 실패 시 S3 보상 삭제")
class TripmateImageServiceTest {

    private final TripmateImageRepository imageRepository = mock(TripmateImageRepository.class);
    private final TripmatePostImageRepository postImageRepository = mock(TripmatePostImageRepository.class);
    private final TripmatePostDraftRepository draftRepository = mock(TripmatePostDraftRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final ImageProcessor imageProcessor = mock(ImageProcessor.class);
    private final ImageUploadExecutor executor = mock(ImageUploadExecutor.class);

    private final TripmateImageService service = new TripmateImageService(
            imageRepository, postImageRepository, draftRepository, storage, imageProcessor, executor);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void runPoolTasksInline() {
        // process/processAll/uploadInParallel 을 인라인 실행 — 풀 스케줄링 없이 보상 로직만 검증.
        when(executor.process(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(executor.processAll(anyList())).thenAnswer(inv ->
                ((List<Supplier<Object>>) inv.getArgument(0)).stream().map(Supplier::get).toList());
        when(executor.uploadInParallel(anyList())).thenAnswer(inv ->
                ((List<Supplier<Object>>) inv.getArgument(0)).stream().map(Supplier::get).toList());
    }

    @Test
    @DisplayName("업로드: Mongo 저장 실패 시 방금 올린 S3 객체를 보상 삭제하고 예외 전파")
    void compensatesStorageWhenSaveFails() {
        // given
        when(imageProcessor.sanitize(any())).thenReturn(new ProcessedVariant(new byte[]{1}, "image/jpeg", "jpg"));
        when(storage.uploadPerm(any(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("https://s3/uploads/perm/u/x.jpg");
        when(imageRepository.save(any())).thenThrow(new RuntimeException("mongo down"));

        // when & then
        assertThatThrownBy(() -> service.uploadImages("u", List.of(new byte[]{1})))
                .isInstanceOf(RuntimeException.class);

        verify(storage).delete("https://s3/uploads/perm/u/x.jpg"); // 보상 삭제
    }
}
