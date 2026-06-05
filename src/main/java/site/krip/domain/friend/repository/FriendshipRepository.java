package site.krip.domain.friend.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 친구 관계 RDB 접근.
 *
 * <p>목록은 (updated_at, friendship_id) 커서 페이지네이션. peer 프로필은 to-one fetch join.
 * 방향 무관 관계 조회는 {@code findBetween}.
 */
public interface FriendshipRepository extends JpaRepository<Friendship, String> {

    @Query("select f from Friendship f where "
            + "(f.requesterId = :a and f.addresseeId = :b) "
            + "or (f.requesterId = :b and f.addresseeId = :a)")
    Optional<Friendship> findBetween(@Param("a") String userA, @Param("b") String userB);

    /** ACCEPTED 친구 수 (마이페이지 stats). */
    @Query("select count(f) from Friendship f where f.status = site.krip.domain.friend.entity.FriendshipStatus.ACCEPTED "
            + "and (f.requesterId = :userId or f.addresseeId = :userId)")
    long countAcceptedForUser(@Param("userId") String userId);

    /** me 의 모든 ACCEPTED 친구 user_id (그룹 방 초대 가능 친구 후보 추출용). */
    @Query("select (case when f.requesterId = :me then f.addresseeId else f.requesterId end) "
            + "from Friendship f where f.status = site.krip.domain.friend.entity.FriendshipStatus.ACCEPTED "
            + "and (f.requesterId = :me or f.addresseeId = :me)")
    List<String> findAcceptedFriendIds(@Param("me") String meId);

    /** me 와 ACCEPTED 친구 관계인 targets 의 서브셋 (그룹 채팅 "친구만 초대" 정책 1쿼리 체크). */
    @Query("select (case when f.requesterId = :me then f.addresseeId else f.requesterId end) "
            + "from Friendship f where f.status = site.krip.domain.friend.entity.FriendshipStatus.ACCEPTED "
            + "and ((f.requesterId = :me and f.addresseeId in :targets) "
            + "  or (f.addresseeId = :me and f.requesterId in :targets))")
    List<String> findAcceptedFriendIdsWith(@Param("me") String meId,
                                           @Param("targets") java.util.Collection<String> targets);

    /** me 와 targets 사이의 관계(방향 무관) 일괄 조회 — 검색 결과 상태 매핑용. */
    @Query("select f from Friendship f where "
            + "(f.requesterId = :me and f.addresseeId in :targets) "
            + "or (f.addresseeId = :me and f.requesterId in :targets)")
    List<Friendship> findFriendshipsWith(@Param("me") String meId,
                                         @Param("targets") Collection<String> targetIds);

    @Query("select f.updatedAt from Friendship f where f.friendshipId = :id")
    Optional<Instant> findUpdatedAt(@Param("id") String friendshipId);

    // ──────────────────── 친구 목록 (ACCEPTED) ────────────────────

    @Query("select f from Friendship f "
            + "left join fetch f.requester ru left join fetch ru.detail "
            + "left join fetch f.addressee au left join fetch au.detail "
            + "where f.status = :status and (f.requesterId = :userId or f.addresseeId = :userId)")
    List<Friendship> findFriendsFirstPage(@Param("userId") String userId,
                                          @Param("status") FriendshipStatus status,
                                          Pageable pageable);

    @Query("select f from Friendship f "
            + "left join fetch f.requester ru left join fetch ru.detail "
            + "left join fetch f.addressee au left join fetch au.detail "
            + "where f.status = :status and (f.requesterId = :userId or f.addresseeId = :userId) "
            + "and (f.updatedAt < :cursorAt or (f.updatedAt = :cursorAt and f.friendshipId < :cursor))")
    List<Friendship> findFriendsAfterCursor(@Param("userId") String userId,
                                            @Param("status") FriendshipStatus status,
                                            @Param("cursorAt") Instant cursorAt,
                                            @Param("cursor") String cursor,
                                            Pageable pageable);

    // ──────────────────── 받은 요청 (PENDING, addressee=me) ────────────────────

    @Query("select f from Friendship f "
            + "left join fetch f.requester ru left join fetch ru.detail "
            + "where f.addresseeId = :userId and f.status = :status")
    List<Friendship> findReceivedFirstPage(@Param("userId") String userId,
                                           @Param("status") FriendshipStatus status,
                                           Pageable pageable);

    @Query("select f from Friendship f "
            + "left join fetch f.requester ru left join fetch ru.detail "
            + "where f.addresseeId = :userId and f.status = :status "
            + "and (f.updatedAt < :cursorAt or (f.updatedAt = :cursorAt and f.friendshipId < :cursor))")
    List<Friendship> findReceivedAfterCursor(@Param("userId") String userId,
                                             @Param("status") FriendshipStatus status,
                                             @Param("cursorAt") Instant cursorAt,
                                             @Param("cursor") String cursor,
                                             Pageable pageable);

    // ──────────────────── 보낸 요청 (PENDING, requester=me) ────────────────────

    @Query("select f from Friendship f "
            + "left join fetch f.addressee au left join fetch au.detail "
            + "where f.requesterId = :userId and f.status = :status")
    List<Friendship> findSentFirstPage(@Param("userId") String userId,
                                       @Param("status") FriendshipStatus status,
                                       Pageable pageable);

    @Query("select f from Friendship f "
            + "left join fetch f.addressee au left join fetch au.detail "
            + "where f.requesterId = :userId and f.status = :status "
            + "and (f.updatedAt < :cursorAt or (f.updatedAt = :cursorAt and f.friendshipId < :cursor))")
    List<Friendship> findSentAfterCursor(@Param("userId") String userId,
                                         @Param("status") FriendshipStatus status,
                                         @Param("cursorAt") Instant cursorAt,
                                         @Param("cursor") String cursor,
                                         Pageable pageable);
}
