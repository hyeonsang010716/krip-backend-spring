package site.krip.domain.chat.dto.response;

import org.jspecify.annotations.Nullable;

/** 1:1 방 상대방 프로필. 탈퇴 시 모든 필드 null. */
public record ChatRoomPeerResponse(
        @Nullable String userId,
        @Nullable String userName,
        @Nullable String profileImageUrl
) {
}
