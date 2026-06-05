package site.krip.domain.tripmate.dto;

/**
 * 좋아요 추가 서비스 내부 전달 객체.
 *
 * <p>트랜잭션 안에서 합성 → 트랜잭션 밖에서 fan-out 호출에 사용. 컨트롤러에는
 * {@code likeCount} 만 노출. {@code recipientId == actorId} 면 fan-out skip(더미값).
 */
public record AddLikePayload(
        long likeCount,
        String recipientId,
        String actorName,
        String actorProfileImageUrl,
        String postPreview
) {
}
