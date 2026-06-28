package site.krip.domain.auth.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.dto.request.ProfileUpdateRequest;
import site.krip.domain.auth.dto.response.OtherUserProfileListResponse;
import site.krip.domain.auth.dto.response.OtherUserProfileResponse;
import site.krip.domain.auth.dto.response.ProfileImageResponse;
import site.krip.domain.auth.dto.response.ProfileResponse;
import site.krip.domain.auth.dto.response.ProfileStatsResponse;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.exception.ProfileImageAlreadyExistsException;
import site.krip.domain.auth.exception.ProfileImageNotFoundException;
import site.krip.domain.auth.exception.ProfileNotRegisteredException;
import site.krip.domain.auth.exception.UserNotFoundException;
import site.krip.domain.auth.port.FeedLikeCountPort;
import site.krip.domain.auth.port.FriendCountPort;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ImageUploadExecutor;
import site.krip.global.common.image.ProcessedVariant;
import site.krip.global.storage.ObjectStorage;
import site.krip.global.storage.StoragePrefix;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 프로필 조회/수정/이미지 관리.
 *
 * <p>이미지 작업은 DB 변경(트랜잭션)과 스토리지 삭제(트랜잭션 밖, best-effort)를 분리하고,
 * self-invocation 프록시 한계를 피해 {@link TransactionTemplate} 으로 트랜잭션을 연다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final UserDetailInformRepository detailRepository;
    private final ObjectStorage storage;
    private final FeedLikeCountPort feedLikeCountPort;
    private final FriendCountPort friendCountPort;
    private final TransactionTemplate txTemplate;
    private final ImageProcessor imageProcessor;
    private final ImageUploadExecutor imageUploadExecutor;

    // TODO 운영 전환 시 — 무한정 전체 조회(대규모 시 메모리/지연 위험). 커서 페이지네이션 +
    //  travelStyles 별도 IN 배치 로드로 전환 필요. 현재는 DEV 전용 API 라 단순 전체 반환.
    @Transactional(readOnly = true)
    public OtherUserProfileListResponse getAllOtherUsers(String userId) {
        List<User> users = userRepository.findActiveOthersWithProfile(userId, UserStatus.ACTIVE);
        List<OtherUserProfileResponse> result = users.stream()
                .filter(u -> u.getDetail() != null) // 2차 미완료 제외
                .map(OtherUserProfileResponse::from)
                .toList();
        return new OtherUserProfileListResponse(result);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile(String userId) {
        User user = userRepository.findByIdWithProfile(userId)
                .orElseThrow(UserNotFoundException::new);
        if (user.getDetail() == null) {
            throw new ProfileNotRegisteredException();
        }
        return ProfileResponse.from(user);
    }

    @Transactional(readOnly = true)
    public ProfileStatsResponse getMyStats(String userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException();
        }
        long likes = feedLikeCountPort.countTotalFeedLikes(userId);
        long friends = friendCountPort.countAcceptedFriends(userId);
        return new ProfileStatsResponse(likes, friends);
    }

    @Transactional
    public ProfileResponse updateProfile(String userId, ProfileUpdateRequest req) {
        User user = userRepository.findByIdWithProfile(userId)
                .orElseThrow(UserNotFoundException::new);
        if (user.getDetail() == null) {
            throw new ProfileNotRegisteredException();
        }

        UserDetailInform detail = user.getDetail();
        if (req.email() != null) detail.changeEmail(req.email());
        if (req.userName() != null) detail.changeUserName(req.userName());
        if (req.phoneNumber() != null) detail.changePhoneNumber(req.phoneNumber());
        if (req.age() != null) detail.changeAge(req.age());
        if (req.gender() != null) detail.changeGender(req.gender());
        if (req.nationality() != null) detail.changeNationality(req.nationality());

        // null = 변경 없음, [] = 전체 삭제, [..] = 전체 교체
        if (req.travelStyles() != null) {
            user.replaceTravelStyles(req.travelStyles());
        }

        log.info("프로필 수정 완료 (user_id={})", userId);
        return ProfileResponse.from(user);
    }

    // ──────────────────── 프로필 이미지 (유저당 1장) ────────────────────

    public ProfileImageResponse addProfileImage(String userId, byte[] bytes) {
        // 1) 사전 검증 (짧은 읽기 트랜잭션) — 미등록 404, 이미 존재 409
        txTemplate.executeWithoutResult(status -> {
            UserDetailInform detail = detailRepository.findById(userId)
                    .orElseThrow(ProfileNotRegisteredException::new);
            if (detail.getProfileImageUrl() != null) {
                throw new ProfileImageAlreadyExistsException();
            }
        });

        // 2) 정제 후 S3 업로드 (트랜잭션 밖 — DB 커넥션을 외부 I/O 동안 점유하지 않음)
        String url = sanitizeAndUpload(userId, bytes);

        // 3) 컬럼 반영 (짧은 쓰기 트랜잭션) — 행 잠금으로 동시 추가를 직렬화. 이미 채워졌으면 방금 올린 파일 정리 후 409
        try {
            txTemplate.executeWithoutResult(status -> {
                UserDetailInform detail = detailRepository.findByIdForUpdate(userId)
                        .orElseThrow(ProfileNotRegisteredException::new);
                if (detail.getProfileImageUrl() != null) {
                    throw new ProfileImageAlreadyExistsException();
                }
                detail.changeProfileImageUrl(url);
            });
        } catch (RuntimeException e) {
            safeDelete(url, userId, "추가 실패 cleanup");
            throw e;
        }
        log.info("프로필 이미지 추가 완료 (user_id={})", userId);
        return new ProfileImageResponse(url);
    }

    public ProfileImageResponse updateProfileImage(String userId, byte[] bytes) {
        // 1) 사전 검증 (짧은 읽기 트랜잭션) — 기존 이미지 없으면 404
        txTemplate.executeWithoutResult(status -> {
            UserDetailInform detail = detailRepository.findById(userId)
                    .orElseThrow(ProfileNotRegisteredException::new);
            if (detail.getProfileImageUrl() == null) {
                throw new ProfileImageNotFoundException("수정할 프로필 이미지가 없습니다. 먼저 POST 로 추가해주세요.");
            }
        });

        // 2) 정제 후 S3 업로드 (트랜잭션 밖)
        String newUrl = sanitizeAndUpload(userId, bytes);

        // 3) 컬럼 교체 (짧은 쓰기 트랜잭션) — 행 잠금으로 동시 수정을 직렬화. 실패 시 방금 올린 파일 정리, oldUrl 캡처
        String oldUrl;
        try {
            oldUrl = txTemplate.execute(status -> {
                UserDetailInform detail = detailRepository.findByIdForUpdate(userId)
                        .orElseThrow(ProfileNotRegisteredException::new);
                String old = detail.getProfileImageUrl();
                if (old == null) {
                    throw new ProfileImageNotFoundException("수정할 프로필 이미지가 없습니다. 먼저 POST 로 추가해주세요.");
                }
                detail.changeProfileImageUrl(newUrl);
                return old;
            });
        } catch (RuntimeException e) {
            safeDelete(newUrl, userId, "수정 실패 cleanup");
            throw e;
        }

        // 4) 이전 이미지 삭제 (best-effort)
        safeDelete(oldUrl, userId, "이전 프로필 이미지 삭제");
        log.info("프로필 이미지 수정 완료 (user_id={})", userId);
        return new ProfileImageResponse(newUrl);
    }

    public void deleteProfileImage(String userId) {
        String oldUrl = txTemplate.execute(status -> {
            // 행 잠금으로 동시 수정/삭제를 직렬화 — old 캡처와 null 세팅 사이의 lost-update 방지.
            UserDetailInform detail = detailRepository.findByIdForUpdate(userId)
                    .orElseThrow(ProfileNotRegisteredException::new);
            String old = detail.getProfileImageUrl();
            if (old == null) {
                throw new ProfileImageNotFoundException("삭제할 프로필 이미지가 없습니다.");
            }
            detail.changeProfileImageUrl(null);
            return old;
        });

        safeDelete(oldUrl, userId, "프로필 이미지 파일 삭제");
        log.info("프로필 이미지 삭제 완료 (user_id={})", userId);
    }

    /** 재인코딩(EXIF/메타데이터 제거·폴리글랏 무력화)으로 정제 후 영구 경로 업로드. content-type·확장자는 감지 포맷에서 도출. */
    private String sanitizeAndUpload(String userId, byte[] bytes) {
        ProcessedVariant variant = imageUploadExecutor.process(() -> imageProcessor.sanitize(bytes));
        return storage.uploadPerm(
                new ByteArrayInputStream(variant.data()), variant.data().length,
                "image." + variant.fileExt(), variant.contentType(),
                StoragePrefix.profilePrefix(userId));
    }

    /** 스토리지 삭제 best-effort — 실패해도 orphan 만 남기고 흐름은 진행. */
    private void safeDelete(@Nullable String url, String userId, String ctx) {
        if (url == null) {
            return;
        }
        try {
            storage.delete(url);
        } catch (Exception e) {
            log.warn("{} 실패 — orphan 잔존 (user_id={})", ctx, userId, e);
        }
    }
}
