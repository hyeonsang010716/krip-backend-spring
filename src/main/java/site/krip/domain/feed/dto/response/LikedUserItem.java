package site.krip.domain.feed.dto.response;

/** 좋아요 누른 유저 1명. detail 결손 시 빈 문자열/null. */
public record LikedUserItem(String userId, String userName, String profileImageUrl) {
}
