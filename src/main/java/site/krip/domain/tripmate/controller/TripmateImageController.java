package site.krip.domain.tripmate.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import site.krip.domain.tripmate.document.TripmateImage;
import site.krip.domain.tripmate.dto.response.CleanupResponse;
import site.krip.domain.tripmate.dto.response.ImageUploadListResponse;
import site.krip.domain.tripmate.dto.response.ImageUploadResponse;
import site.krip.domain.tripmate.service.TripmateImageService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.exception.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 여행 메이트 이미지 업로드/정리. 경로: {@code /api/tripmate/images}.
 * 다건 업로드, 최대 10개 / 파일당 10MB / jpeg·png·webp·gif.
 */
@RestController
@RequestMapping("/api/tripmate/images")
public class TripmateImageController {

    private static final Logger log = LoggerFactory.getLogger(TripmateImageController.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10MB
    private static final int MAX_FILE_COUNT = 10;

    private final TripmateImageService imageService;

    public TripmateImageController(TripmateImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ImageUploadListResponse uploadImages(
            @CurrentUserId String userId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {

        // 파트 누락(null)·빈 목록 모두 400 — 최소 1개 필수.
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("업로드할 이미지 파일이 필요합니다.");
        }
        if (files.size() > MAX_FILE_COUNT) {
            throw ApiException.badRequest("이미지는 최대 " + MAX_FILE_COUNT + "개까지 업로드할 수 있습니다.");
        }
        for (MultipartFile f : files) {
            if (!ALLOWED_CONTENT_TYPES.contains(f.getContentType())) {
                throw ApiException.badRequest("허용되지 않는 파일 형식입니다: " + f.getContentType() + " (jpeg, png, webp, gif만 가능)");
            }
            if (f.getSize() > MAX_FILE_SIZE) {
                throw ApiException.badRequest("파일 크기가 10MB를 초과합니다: " + f.getOriginalFilename());
            }
        }

        List<ImageUploadResponse> uploaded = new ArrayList<>(files.size());
        try {
            for (MultipartFile f : files) {
                String name = f.getOriginalFilename() != null ? f.getOriginalFilename() : "image";
                String type = f.getContentType() != null ? f.getContentType() : "image/jpeg";
                TripmateImage saved = imageService.uploadImage(
                        userId, f.getInputStream(), f.getSize(), name, type);
                uploaded.add(new ImageUploadResponse(saved.getImageId(), saved.getImageUrl()));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("이미지 업로드 실패 (user_id={}): {}", userId, e.toString());
            throw ApiException.internalError("이미지 업로드에 실패했습니다.");
        }

        return new ImageUploadListResponse(uploaded);
    }

    /** 고아 이미지 정리 — 운영/관리용. */
    @PostMapping("/cleanup")
    public CleanupResponse cleanupOrphanedImages(@CurrentUserId String userId) {
        try {
            return new CleanupResponse(imageService.cleanupOrphanedImages(userId));
        } catch (Exception e) {
            log.error("고아 이미지 정리 실패 (user_id={}): {}", userId, e.toString());
            throw ApiException.internalError("고아 이미지 정리에 실패했습니다.");
        }
    }
}
