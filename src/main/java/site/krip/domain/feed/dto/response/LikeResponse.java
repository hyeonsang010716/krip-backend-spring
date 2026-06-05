package site.krip.domain.feed.dto.response;

/** 좋아요 추가/취소 응답. */
public record LikeResponse(String postId, long likeCount) {
}
