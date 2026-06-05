package site.krip.domain.chat.dto.response;

/** 1:1 방 상대방 프로필. 탈퇴 시 모든 필드 null. */
public record ChatRoomPeerResponse(
        String userId,
        String userName,
        String profileImageUrl
) {
}
