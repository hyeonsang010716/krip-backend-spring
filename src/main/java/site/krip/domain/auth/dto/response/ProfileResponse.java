package site.krip.domain.auth.dto.response;

import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.TravelStyle;
import site.krip.domain.auth.entity.User;

import java.util.List;

/** 내 프로필 전체 응답. auth_provider/status 는 enum value(google/active)로 직렬화. */
public record ProfileResponse(
        String userId,
        String authProvider,
        String status,
        String email,
        String userName,
        String phoneNumber,
        int age,
        Gender gender,
        String nationality,
        List<TravelStyle> travelStyles,
        String profileImageUrl,
        boolean notificationMuted
) {
    /** 프로필 로드된 User(detail + travelStyles fetch 완료) → 응답 매핑. */
    public static ProfileResponse from(User user) {
        var d = user.getDetail();
        return new ProfileResponse(
                user.getUserId(),
                user.getAuthProvider().getValue(),
                user.getStatus().getValue(),
                d.getEmail(),
                d.getUserName(),
                d.getPhoneNumber(),
                d.getAge(),
                d.getGender(),
                d.getNationality(),
                user.getTravelStyles().stream().map(s -> s.getStyle()).toList(),
                d.getProfileImageUrl(),
                user.isNotificationMuted());
    }
}
