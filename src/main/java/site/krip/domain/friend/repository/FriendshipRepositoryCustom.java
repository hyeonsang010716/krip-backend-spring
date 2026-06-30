package site.krip.domain.friend.repository;

import org.jspecify.annotations.Nullable;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;

import java.time.Instant;
import java.util.List;

/**
 * 친구/요청 목록 키셋 페이지네이션 — 커서 유무 분기를 QueryDSL 동적 쿼리로 처리.
 *
 * <p>모두 (updatedAt, friendshipId) desc 정렬, peer 프로필+detail fetch join.
 * {@code cursorAt}/{@code cursor} 가 모두 null 이면 첫 페이지, 아니면 키셋 이후.
 */
public interface FriendshipRepositoryCustom {

    /** ACCEPTED 친구 목록(방향 무관) — requester/addressee 양쪽 프로필+detail fetch join. */
    List<Friendship> findFriends(String userId, FriendshipStatus status,
                                 @Nullable Instant cursorAt, @Nullable String cursor, int limit);

    /** 받은 요청(addressee=me) — requester 프로필+detail fetch join. */
    List<Friendship> findReceived(String userId, FriendshipStatus status,
                                  @Nullable Instant cursorAt, @Nullable String cursor, int limit);

    /** 보낸 요청(requester=me) — addressee 프로필+detail fetch join. */
    List<Friendship> findSent(String userId, FriendshipStatus status,
                              @Nullable Instant cursorAt, @Nullable String cursor, int limit);
}
