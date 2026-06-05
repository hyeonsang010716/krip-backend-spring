package site.krip.domain.tripmate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.tripmate.document.TripmateImage;
import site.krip.domain.tripmate.document.TripmatePostDraft;
import site.krip.domain.tripmate.exception.PostAccessDeniedException;
import site.krip.domain.tripmate.exception.PostNotFoundException;
import site.krip.domain.tripmate.repository.TripmateImageRepository;
import site.krip.domain.tripmate.repository.TripmatePostDraftRepository;
import site.krip.domain.tripmate.repository.TripmatePostImageRepository;
import site.krip.global.storage.ObjectStorage;
import site.krip.global.storage.StoragePrefix;
import site.krip.global.support.IdGenerator;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 여행 메이트 이미지.
 *
 * <p>업로드: Object Storage 영구 경로 + MongoDB 메타데이터. 고아 이미지 정리는
 * 게시글·임시저장 어디에도 참조되지 않는 이미지를 삭제.
 */
@Service
public class TripmateImageService {

    private static final Logger log = LoggerFactory.getLogger(TripmateImageService.class);

    private final TripmateImageRepository imageRepository;
    private final TripmatePostImageRepository postImageRepository;
    private final TripmatePostDraftRepository draftRepository;
    private final ObjectStorage storage;

    public TripmateImageService(TripmateImageRepository imageRepository,
                                TripmatePostImageRepository postImageRepository,
                                TripmatePostDraftRepository draftRepository,
                                ObjectStorage storage) {
        this.imageRepository = imageRepository;
        this.postImageRepository = postImageRepository;
        this.draftRepository = draftRepository;
        this.storage = storage;
    }

    public TripmateImage uploadImage(String userId, InputStream content, long contentLength,
                                     String fileName, String contentType) {
        String imageId = IdGenerator.tripmateImageId();
        String imageUrl = storage.uploadPerm(content, contentLength, fileName, contentType,
                StoragePrefix.postPrefix(userId));
        log.info("이미지 업로드 완료 (user_id={}, image_id={})", userId, imageId);
        return imageRepository.save(new TripmateImage(userId, imageId, imageUrl, Instant.now()));
    }

    public List<TripmateImage> getImages(String userId) {
        return imageRepository.findByUserId(userId);
    }

    public void deleteImage(String userId, String imageId) {
        TripmateImage image = imageRepository.findByImageId(imageId)
                .orElseThrow(PostNotFoundException::new);
        if (!image.getUserId().equals(userId)) {
            throw new PostAccessDeniedException("이미지 삭제 권한이 없습니다.");
        }
        storage.delete(image.getImageUrl());
        imageRepository.deleteByImageId(imageId);
        log.info("이미지 삭제 완료 (user_id={}, image_id={})", userId, imageId);
    }

    /** 고아 이미지 정리 — 게시글/임시저장 어디에도 참조되지 않는 업로드 이미지 삭제. */
    @Transactional(readOnly = true)
    public int cleanupOrphanedImages(String userId) {
        List<TripmateImage> allImages = imageRepository.findByUserId(userId);
        if (allImages.isEmpty()) {
            return 0;
        }

        Set<String> referenced = new HashSet<>(postImageRepository.findUrlsByUserId(userId));
        Optional<TripmatePostDraft> draft = draftRepository.findByUserId(userId);
        draft.ifPresent(d -> referenced.addAll(d.getImageUrls()));

        List<TripmateImage> orphaned = allImages.stream()
                .filter(img -> !referenced.contains(img.getImageUrl()))
                .toList();
        if (orphaned.isEmpty()) {
            return 0;
        }

        List<String> orphanedUrls = new ArrayList<>();
        List<String> orphanedIds = new ArrayList<>();
        for (TripmateImage img : orphaned) {
            orphanedUrls.add(img.getImageUrl());
            orphanedIds.add(img.getImageId());
        }

        storage.deleteMany(orphanedUrls);
        imageRepository.deleteByImageIds(orphanedIds);

        log.info("고아 이미지 정리 완료 (user_id={}, 삭제={}건)", userId, orphaned.size());
        return orphaned.size();
    }
}
