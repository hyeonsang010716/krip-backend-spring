package site.krip.domain.friend.dto.response;

import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.User;

/** 친구 관계/차단 상대 프로필. */
public record FriendPeerResponse(
        String userId,
        String userName,
        int age,
        Gender gender,
        String nationality,
        String profileImageUrl
) {
    /** detail 이 로드된 User 로부터 생성. */
    public static FriendPeerResponse from(User user) {
        var d = user.getDetail();
        return new FriendPeerResponse(
                user.getUserId(), d.getUserName(), d.getAge(), d.getGender(),
                d.getNationality(), d.getProfileImageUrl());
    }
}
