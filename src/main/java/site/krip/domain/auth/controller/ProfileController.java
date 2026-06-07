package site.krip.domain.auth.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import site.krip.domain.auth.dto.request.ProfileUpdateRequest;
import site.krip.domain.auth.dto.response.OtherUserProfileListResponse;
import site.krip.domain.auth.dto.response.ProfileImageResponse;
import site.krip.domain.auth.dto.response.ProfileResponse;
import site.krip.domain.auth.dto.response.ProfileStatsResponse;
import site.krip.domain.auth.service.ProfileService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;
import site.krip.global.common.validation.ImageUploadValidator;

import java.io.IOException;
import java.util.List;

/** 프로필 조회/수정 + 이미지 CRUD. */
@RestController
@RequestMapping("/api/auth/profile")
public class ProfileController {

    private static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // 5MB

    private final ProfileService profileService;
    private final ImageUploadValidator imageValidator;

    public ProfileController(ProfileService profileService, ImageUploadValidator imageValidator) {
        this.profileService = profileService;
        this.imageValidator = imageValidator;
    }

    @GetMapping("/me")
    public ProfileResponse getMyProfile(@CurrentUserId String userId) {
        return profileService.getMyProfile(userId);
    }

    @PatchMapping("/me")
    public ProfileResponse updateMyProfile(@CurrentUserId String userId,
                                           @Valid @RequestBody ProfileUpdateRequest body) {
        return profileService.updateProfile(userId, body);
    }

    @GetMapping("/me/stats")
    public ProfileStatsResponse getMyStats(@CurrentUserId String userId) {
        return profileService.getMyStats(userId);
    }

    @GetMapping("/all")
    public OtherUserProfileListResponse getAllOtherUsers(@CurrentUserId String userId) {
        return profileService.getAllOtherUsers(userId);
    }

    // ──────────────────── 프로필 이미지 (유저당 1장) ────────────────────

    @PostMapping("/image")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileImageResponse addProfileImage(@CurrentUserId String userId,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        imageValidator.validate(file, ALLOWED_CONTENT_TYPES, MAX_FILE_SIZE);
        return profileService.addProfileImage(
                userId, file.getInputStream(), file.getSize(), filename(file), contentType(file));
    }

    @PutMapping("/image")
    public ProfileImageResponse updateProfileImage(@CurrentUserId String userId,
                                                   @RequestParam("file") MultipartFile file) throws IOException {
        imageValidator.validate(file, ALLOWED_CONTENT_TYPES, MAX_FILE_SIZE);
        return profileService.updateProfileImage(
                userId, file.getInputStream(), file.getSize(), filename(file), contentType(file));
    }

    @DeleteMapping("/image")
    public MessageResponse deleteProfileImage(@CurrentUserId String userId) {
        profileService.deleteProfileImage(userId);
        return new MessageResponse("프로필 이미지가 삭제되었습니다.");
    }

    private String filename(MultipartFile file) {
        return file.getOriginalFilename() != null ? file.getOriginalFilename() : "profile";
    }

    private String contentType(MultipartFile file) {
        return file.getContentType() != null ? file.getContentType() : "image/jpeg";
    }
}
