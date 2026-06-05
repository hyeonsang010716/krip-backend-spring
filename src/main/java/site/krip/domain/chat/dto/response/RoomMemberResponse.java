package site.krip.domain.chat.dto.response;

import site.krip.domain.auth.entity.User;

/** 그룹 방 참여자 / 초대 가능 친구 미리보기. */
public record RoomMemberResponse(
        String userId,
        String userName,
        String profileImageUrl
) {
    /** detail 결손 시 닉네임 빈 문자열 fallback (join 누락 방어). */
    public static RoomMemberResponse from(User user) {
        var detail = user.getDetail();
        return new RoomMemberResponse(
                user.getUserId(),
                detail != null ? detail.getUserName() : "",
                detail != null ? detail.getProfileImageUrl() : null);
    }
}
