package site.krip.domain.tripmate.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import site.krip.domain.tripmate.document.TripmateImage;
import site.krip.domain.tripmate.repository.TripmateImageRepository;
import site.krip.domain.tripmate.repository.TripmatePostDraftRepository;
import site.krip.domain.tripmate.repository.TripmatePostImageRepository;
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ImageUploadExecutor;
import site.krip.global.common.image.ProcessedVariant;
import site.krip.global.storage.ObjectStorage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TripmateImageService} 고아 보상 단위 테스트.
 *
 * <p>업로드 저장 실패 시 S3 객체 보상 삭제(dangling S3 영구 누수 방지), 삭제는 DB-우선 후 best-effort
 * 스토리지 정리(스토리지 실패가 row 삭제를 막지 않음)를 검증한다.
 */
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
        // processAll/uploadInParallel 을 인라인 실행 — 풀 스케줄링 없이 보상 로직만 검증.
        when(executor.processAll(anyList())).thenAnswer(inv ->
                ((List<Supplier<Object>>) inv.getArgument(0)).stream().map(Supplier::get).toList());
        when(executor.uploadInParallel(anyList())).thenAnswer(inv ->
                ((List<Supplier<Object>>) inv.getArgument(0)).stream().map(Supplier::get).toList());
    }

    @Test
    @DisplayName("업로드: Mongo 저장 실패 시 방금 올린 S3 객체를 보상 삭제하고 예외 전파")
    void compensatesStorageWhenSaveFails() {
        when(imageProcessor.sanitize(any())).thenReturn(new ProcessedVariant(new byte[]{1}, "image/jpeg", "jpg"));
        when(storage.uploadPerm(any(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("https://s3/uploads/perm/u/x.jpg");
        when(imageRepository.save(any())).thenThrow(new RuntimeException("mongo down"));

        assertThatThrownBy(() -> service.uploadImages("u", List.of(new byte[]{1})))
                .isInstanceOf(RuntimeException.class);

        verify(storage).delete("https://s3/uploads/perm/u/x.jpg"); // 보상 삭제
    }

    @Test
    @DisplayName("삭제: DB(메타데이터) 삭제 후 스토리지 삭제 — DB-우선 순서")
    void deleteIsDbFirstThenStorage() {
        TripmateImage image = new TripmateImage("u", "img", "https://s3/x.jpg", Instant.now());
        when(imageRepository.findByImageId("img")).thenReturn(Optional.of(image));

        service.deleteImage("u", "img");

        InOrder ordered = inOrder(imageRepository, storage);
        ordered.verify(imageRepository).deleteByImageId("img");
        ordered.verify(storage).delete("https://s3/x.jpg");
    }

    @Test
    @DisplayName("삭제: 스토리지 삭제 실패해도 row 는 이미 삭제됐고 예외를 던지지 않는다(best-effort)")
    void deleteToleratesStorageFailure() {
        TripmateImage image = new TripmateImage("u", "img", "https://s3/x.jpg", Instant.now());
        when(imageRepository.findByImageId("img")).thenReturn(Optional.of(image));
        doThrow(new RuntimeException("s3 down")).when(storage).delete(anyString());

        assertThatCode(() -> service.deleteImage("u", "img")).doesNotThrowAnyException();

        verify(imageRepository).deleteByImageId("img");
    }
}
