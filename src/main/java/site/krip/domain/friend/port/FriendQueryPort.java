package site.krip.domain.friend.port;

import java.util.Collection;
import java.util.List;

/**
 * friend 도메인이 외부(chat 등)에 노출하는 차단·친구 읽기 API.
 *
 * <p>소비자가 friend 의 repository/entity 에 직접 의존하지 않도록 published 인터페이스로 제공한다.
 * 반환은 도메인 엔티티가 아닌 식별자/단순 레코드라 경계 누수가 없다.
 */
public interface FriendQueryPort {

    /** 두 유저 사이의 모든 차단 관계(방향 포함). */
    List<BlockPair> findBlocksBetween(String userA, String userB);

    /** me 의 수락된 친구 id 전체. */
    List<String> acceptedFriendIds(String meId);

    /** candidates 중 me 와 수락된 친구인 id 만. */
    List<String> acceptedFriendIdsAmong(String meId, Collection<String> candidates);

    /** 차단 관계 — blocker 가 blocked 를 차단. */
    record BlockPair(String blockerId, String blockedId) {
    }
}
