package site.krip.domain.friend.dto.response;

import site.krip.domain.auth.entity.TravelStyle;
import site.krip.domain.friend.entity.FriendshipStatus;

import java.util.List;

/**
 * 친구 추가 화면 검색 결과 단건.
 * 민감 정보(나이/성별 등)는 제외. isRequester 는 PENDING 일 때만 의미(그 외 null).
 */
public record FriendSearchItemResponse(
        String userId,
        String userName,
        String profileImageUrl,
        String nationality,
        List<TravelStyle> travelStyles,
        FriendshipStatus friendshipStatus,
        Boolean isRequester,
        boolean iBlockedPeer
) {
}
