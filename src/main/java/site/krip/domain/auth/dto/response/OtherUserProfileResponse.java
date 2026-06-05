package site.krip.domain.auth.dto.response;

import site.krip.domain.auth.entity.TravelStyle;
import site.krip.domain.auth.entity.User;

import java.util.List;

/** 탐색 목록용 타 유저 프로필 — 최소 공개 정보. */
public record OtherUserProfileResponse(
        String userId,
        String userName,
        String nationality,
        List<TravelStyle> travelStyles,
        String profileImageUrl
) {
    public static OtherUserProfileResponse from(User user) {
        var d = user.getDetail();
        return new OtherUserProfileResponse(
                user.getUserId(),
                d.getUserName(),
                d.getNationality(),
                user.getTravelStyles().stream().map(s -> s.getStyle()).toList(),
                d.getProfileImageUrl());
    }
}
