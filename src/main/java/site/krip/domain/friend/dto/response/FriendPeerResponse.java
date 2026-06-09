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
        if (d == null) {
            // 방어선: 정상 경로는 write 단계에서 2차 미완료(detail==null) 대상을 차단하지만,
            // 레거시/엣지 행 하나가 목록 전체를 500 내지 않도록 식별자만 노출하고 graceful degrade.
            return new FriendPeerResponse(user.getUserId(), null, 0, null, null, null);
        }
        return new FriendPeerResponse(
                user.getUserId(), d.getUserName(), d.getAge(), d.getGender(),
                d.getNationality(), d.getProfileImageUrl());
    }
}
