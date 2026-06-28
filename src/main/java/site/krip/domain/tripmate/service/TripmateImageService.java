package site.krip.domain.tripmate.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.krip.domain.tripmate.document.TripmateImage;
import site.krip.domain.tripmate.document.TripmatePostDraft;
import site.krip.domain.tripmate.repository.TripmateImageRepository;
import site.krip.domain.tripmate.repository.TripmatePostDraftRepository;
import site.krip.domain.tripmate.repository.TripmatePostImageRepository;
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ImageUploadExecutor;
import site.krip.global.common.image.ProcessedVariant;
import site.krip.global.storage.ObjectStorage;
import site.krip.global.storage.StoragePrefix;
import site.krip.global.support.IdGenerator;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 여행 메이트 이미지.
 *
 * <p>업로드: Object Storage 영구 경로 + MongoDB 메타데이터. 고아 이미지 정리는
 * 게시글·임시저장 어디에도 참조되지 않는 이미지를 삭제.
 */
@Service
@Slf4j
public class TripmateImageService {

    /** 업로드 직후 아직 첨부 안 된 이미지를 고아로 오인 삭제하는 레이스 방지 유예기간. */
    private static final Duration ORPHAN_GRACE = Duration.ofMinutes(30);

    private final TripmateImageRepository imageRepository;
    private final TripmatePostImageRepository postImageRepository;
    private final TripmatePostDraftRepository draftRepository;
    private final ObjectStorage storage;
    private final ImageProcessor imageProcessor;
    private final ImageUploadExecutor imageUploadExecutor;

    public TripmateImageService(TripmateImageRepository imageRepository,
                                TripmatePostImageRepository postImageRepository,
                                TripmatePostDraftRepository draftRepository,
                                ObjectStorage storage,
                                ImageProcessor imageProcessor,
                                ImageUploadExecutor imageUploadExecutor) {
        this.imageRepository = imageRepository;
        this.postImageRepository = postImageRepository;
        this.draftRepository = draftRepository;
        this.storage = storage;
        this.imageProcessor = imageProcessor;
        this.imageUploadExecutor = imageUploadExecutor;
    }

    /**
     * 다건 업로드 — CPU 와 I/O 를 풀로 분리(feed 와 동일 전략).
     *
     * <p>Phase 1: 전체 이미지 재인코딩(EXIF 제거·폴리글랏 무력화)을 processingPool 에서 동시 처리(포화 시 429,
     * 잘못된 이미지는 여기서 400). Phase 2: S3 업로드+Mongo 저장을 process()→uploadPool 로 병렬(포화 시 429).
     * 결과는 입력 순서대로 반환.
     */
    public List<TripmateImage> uploadImages(String userId, List<byte[]> files) {
        // Phase 1 (CPU): sanitize
        List<Supplier<ProcessedVariant>> sanitizeTasks = files.stream()
                .map(bytes -> (Supplier<ProcessedVariant>) () -> imageProcessor.sanitize(bytes))
                .toList();
        List<ProcessedVariant> sanitized = imageUploadExecutor.processAll(sanitizeTasks);

        // Phase 2 (I/O): S3 업로드 + Mongo 저장
        Instant uploadedAt = Instant.now();
        List<Supplier<TripmateImage>> uploadTasks = sanitized.stream()
                .map(variant -> (Supplier<TripmateImage>) () -> uploadAndSave(userId, variant, uploadedAt))
                .toList();
        // process() 로 감싸 워커에서 실행 — uploadPool 포화 시 CallerRuns 가 요청(Tomcat) 스레드가 아닌
        // 워커에서 돌아 스레드 격리가 유지된다(feed 와 동일). 두 풀이 분리돼 있어 데드락 없음.
        return imageUploadExecutor.process(() -> imageUploadExecutor.uploadInParallel(uploadTasks));
    }

    private TripmateImage uploadAndSave(String userId, ProcessedVariant sanitized, Instant uploadedAt) {
        String imageId = IdGenerator.tripmateImageId();
        String imageUrl = storage.uploadPerm(
                new ByteArrayInputStream(sanitized.data()), sanitized.data().length,
                "image." + sanitized.fileExt(), sanitized.contentType(),
                StoragePrefix.postPrefix(userId));
        try {
            TripmateImage saved = imageRepository.save(new TripmateImage(userId, imageId, imageUrl, uploadedAt));
            log.info("이미지 업로드 완료 (user_id={}, image_id={})", userId, imageId);
            return saved;
        } catch (RuntimeException e) {
            // Mongo 저장 실패 → 방금 올린 S3 객체를 보상 삭제. 안 지우면 Mongo row 없는 dangling S3 라
            // cleanupOrphanedImages(Mongo row 순회)가 영영 못 찾는 영구 누수가 된다.
            safeDeleteStorage(imageUrl);
            throw e;
        }
    }

    /** 스토리지 단건 삭제 — best-effort. 실패는 orphan 으로 남기고 로깅(ops 알림 대상). */
    private void safeDeleteStorage(String imageUrl) {
        try {
            storage.delete(imageUrl);
        } catch (Exception e) {
            log.warn("스토리지 삭제 실패 — orphan 잔존 (url={})", imageUrl, e);
        }
    }

    /**
     * 고아 이미지 정리 — 게시글/임시저장 어디에도 참조되지 않는 업로드 이미지 삭제.
     *
     * <p>S3 먼저 → 메타데이터 나중: row 가 S3 객체를 찾는 인덱스라, 먼저 지우고 S3 삭제가 실패하면 영구 누수.
     * 안전장치 둘 — 유예기간({@link #ORPHAN_GRACE}) 내 이미지는 건너뛰고(작성 중 레이스 방지),
     * S3 삭제 실패분은 메타데이터를 남겨 다음 호출에서 재시도(멱등).
     */
    public int cleanupOrphanedImages(String userId) {
        List<TripmateImage> allImages = imageRepository.findByUserId(userId);
        if (allImages.isEmpty()) {
            return 0;
        }

        Set<String> referenced = new HashSet<>(postImageRepository.findUrlsByUserId(userId));
        Optional<TripmatePostDraft> draft = draftRepository.findByUserId(userId);
        draft.ifPresent(d -> referenced.addAll(d.getImageUrls()));

        // 유예기간 지난 미참조만 대상. timestamp == null(레거시)은 오래된 것으로 간주.
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
        List<TripmateImage> orphaned = allImages.stream()
                .filter(img -> img.getTimestamp() == null || img.getTimestamp().isBefore(cutoff))
                .filter(img -> !referenced.contains(img.getImageUrl()))
                .toList();
        if (orphaned.isEmpty()) {
            return 0;
        }

        List<String> orphanedUrls = orphaned.stream().map(TripmateImage::getImageUrl).toList();

        List<String> failedUrls = storage.deleteMany(orphanedUrls);

        // 성공분만 메타데이터 제거. 실패분은 row 를 남겨 다음 호출에서 재시도.
        Set<String> failed = failedUrls.isEmpty() ? Set.of() : new HashSet<>(failedUrls);
        List<String> deletableIds = orphaned.stream()
                .filter(img -> !failed.contains(img.getImageUrl()))
                .map(TripmateImage::getImageId)
                .toList();
        imageRepository.deleteByImageIds(deletableIds);

        if (!failed.isEmpty()) {
            log.warn("고아 이미지 S3 삭제 일부 실패 — 메타데이터 보존, 다음 호출 재시도 (user_id={}, 실패={}건)",
                    userId, failed.size());
        }
        log.info("고아 이미지 정리 완료 (user_id={}, 삭제={}건)", userId, deletableIds.size());
        return deletableIds.size();
    }
}
