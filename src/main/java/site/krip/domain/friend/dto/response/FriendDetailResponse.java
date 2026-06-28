package site.krip.domain.friend.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.TravelStyle;
import site.krip.domain.friend.entity.FriendshipStatus;

import java.util.List;

/**
 * 상대 유저 공개 프로필 + 내 기준 관계 상태.
 * 민감 정보(auth_provider/status/email/phone_number) 제외.
 */
public record FriendDetailResponse(
        String userId,
        String userName,
        int age,
        Gender gender,
        String nationality,
        List<TravelStyle> travelStyles,
        @Nullable String friendshipId,
        @Nullable FriendshipStatus friendshipStatus,
        @Nullable Boolean isRequester,
        boolean iBlockedPeer,
        @Nullable String profileImageUrl
) {
}
