package site.krip.domain.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import site.krip.global.common.exception.ApiException;

import java.io.IOException;
import java.util.Set;

/** 프로필 조회/수정 + 이미지 CRUD. */
@RestController
@RequestMapping("/api/auth/profile")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // 5MB

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
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
                                                @RequestParam("file") MultipartFile file) {
        validateContentType(file);
        validateSize(file);
        try {
            return profileService.addProfileImage(
                    userId, openStream(file), file.getSize(),
                    filename(file), contentType(file));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("프로필 이미지 추가 실패 (user_id={}): {}", userId, e.toString());
            throw ApiException.internalError("프로필 이미지 업로드에 실패했습니다.");
        }
    }

    @PutMapping("/image")
    public ProfileImageResponse updateProfileImage(@CurrentUserId String userId,
                                                   @RequestParam("file") MultipartFile file) {
        validateContentType(file);
        validateSize(file);
        try {
            return profileService.updateProfileImage(
                    userId, openStream(file), file.getSize(),
                    filename(file), contentType(file));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("프로필 이미지 수정 실패 (user_id={}): {}", userId, e.toString());
            throw ApiException.internalError("프로필 이미지 수정에 실패했습니다.");
        }
    }

    @DeleteMapping("/image")
    public MessageResponse deleteProfileImage(@CurrentUserId String userId) {
        try {
            profileService.deleteProfileImage(userId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("프로필 이미지 삭제 실패 (user_id={}): {}", userId, e.toString());
            throw ApiException.internalError("프로필 이미지 삭제에 실패했습니다.");
        }
        return new MessageResponse("프로필 이미지가 삭제되었습니다.");
    }

    // ──────────────────── 검증 ────────────────────

    private void validateContentType(MultipartFile file) {
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw ApiException.badRequest("허용되지 않는 파일 형식입니다: " + file.getContentType() + " (jpeg, png, webp, gif만 가능)");
        }
    }

    private void validateSize(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw ApiException.badRequest("파일 크기가 5MB를 초과합니다: " + file.getOriginalFilename());
        }
    }

    private java.io.InputStream openStream(MultipartFile file) throws IOException {
        return file.getInputStream();
    }

    private String filename(MultipartFile file) {
        return file.getOriginalFilename() != null ? file.getOriginalFilename() : "profile";
    }

    private String contentType(MultipartFile file) {
        return file.getContentType() != null ? file.getContentType() : "image/jpeg";
    }
}
