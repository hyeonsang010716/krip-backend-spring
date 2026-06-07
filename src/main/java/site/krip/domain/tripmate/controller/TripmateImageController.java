package site.krip.domain.tripmate.controller;

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
import site.krip.global.common.validation.ImageUploadValidator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 여행 메이트 이미지 업로드/정리. 경로: {@code /api/tripmate/images}.
 * 다건 업로드, 최대 10개 / 파일당 10MB / jpeg·png·webp·gif.
 */
@RestController
@RequestMapping("/api/tripmate/images")
public class TripmateImageController {

    private static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10MB
    private static final int MAX_FILE_COUNT = 10;

    private final TripmateImageService imageService;
    private final ImageUploadValidator imageValidator;

    public TripmateImageController(TripmateImageService imageService, ImageUploadValidator imageValidator) {
        this.imageService = imageService;
        this.imageValidator = imageValidator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ImageUploadListResponse uploadImages(
            @CurrentUserId String userId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) throws IOException {

        // 파트 누락(null)·빈 목록 모두 400 — 최소 1개 필수.
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("업로드할 이미지 파일이 필요합니다.");
        }
        if (files.size() > MAX_FILE_COUNT) {
            throw ApiException.badRequest("이미지는 최대 " + MAX_FILE_COUNT + "개까지 업로드할 수 있습니다.");
        }
        for (MultipartFile f : files) {
            imageValidator.validate(f, ALLOWED_CONTENT_TYPES, MAX_FILE_SIZE);
        }

        List<ImageUploadResponse> uploaded = new ArrayList<>(files.size());
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() != null ? f.getOriginalFilename() : "image";
            String type = f.getContentType() != null ? f.getContentType() : "image/jpeg";
            TripmateImage saved = imageService.uploadImage(userId, f.getInputStream(), f.getSize(), name, type);
            uploaded.add(new ImageUploadResponse(saved.getImageId(), saved.getImageUrl()));
        }
        return new ImageUploadListResponse(uploaded);
    }

    /** 고아 이미지 정리 — 운영/관리용. */
    @PostMapping("/cleanup")
    public CleanupResponse cleanupOrphanedImages(@CurrentUserId String userId) {
        return new CleanupResponse(imageService.cleanupOrphanedImages(userId));
    }
}
