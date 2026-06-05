package site.krip.domain.friend.dto.response;

import java.time.Instant;

/** 차단 관계 응답. */
public record UserBlockResponse(
        String blockId,
        FriendPeerResponse blocked,
        Instant createdAt
) {
}
