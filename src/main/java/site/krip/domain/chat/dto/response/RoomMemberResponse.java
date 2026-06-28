package site.krip.domain.chat.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.port.UserProfileView;

/** 그룹 방 참여자 / 초대 가능 친구 미리보기. */
public record RoomMemberResponse(
        String userId,
        String userName,
        @Nullable String profileImageUrl
) {
    /** 멤버 목록 — ChatRoomMember.user JPA 연관관계로 얻은 User 용. detail 결손 시 빈 문자열 fallback. */
    public static RoomMemberResponse from(User user) {
        var detail = user.getDetail();
        return new RoomMemberResponse(
                user.getUserId(),
                detail != null ? detail.getUserName() : "",
                detail != null ? detail.getProfileImageUrl() : null);
    }

    public static RoomMemberResponse from(UserProfileView view) {
        return new RoomMemberResponse(view.userId(), view.userName(), view.profileImageUrl());
    }
}
