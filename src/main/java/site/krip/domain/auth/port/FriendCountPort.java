package site.krip.domain.auth.port;

/**
 * 마이페이지 통계용 — ACCEPTED 친구 수 (friend 도메인 소유).
 * friend 도메인 포팅 완료로 실제 구현({@code FriendCountAdapter})이 제공된다.
 */
public interface FriendCountPort {

    long countAcceptedFriends(String userId);
}
