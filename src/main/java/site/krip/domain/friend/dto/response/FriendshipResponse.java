package site.krip.domain.friend.dto.response;

import site.krip.domain.friend.entity.FriendshipStatus;

import java.time.Instant;

/** 친구 관계 응답. */
public record FriendshipResponse(
        String friendshipId,
        FriendshipStatus status,
        FriendPeerResponse peer,
        boolean isRequester,
        Instant createdAt,
        Instant updatedAt
) {
}
