package site.krip.domain.tripmate.dto.response;

import java.util.List;

/** 좋아요 누른 유저 목록 응답. */
public record LikedUsersResponse(String postId, List<String> userIds) {
}
