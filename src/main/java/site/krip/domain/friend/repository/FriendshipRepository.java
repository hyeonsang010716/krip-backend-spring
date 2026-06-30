package site.krip.domain.friend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.friend.entity.Friendship;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 친구 관계 RDB 접근.
 *
 * <p>목록은 (updated_at, friendship_id) 커서 페이지네이션 — {@link FriendshipRepositoryCustom} 참고.
 * 방향 무관 관계 조회는 {@code findBetween}.
 */
public interface FriendshipRepository extends JpaRepository<Friendship, String>, FriendshipRepositoryCustom {

    @Query("select f from Friendship f where "
            + "(f.requesterId = :a and f.addresseeId = :b) "
            + "or (f.requesterId = :b and f.addresseeId = :a)")
    Optional<Friendship> findBetween(@Param("a") String userA, @Param("b") String userB);

    /**
     * 두 유저 간 관계(방향 무관) 일괄 삭제 — 버전 무시.
     *
     * <p>차단은 무조건 관계를 단절해야 하므로, 엔티티 delete(version 체크) 대신 bulk delete 로
     * 동시 accept 가 버전을 올려도 차단이 낙관락 충돌로 지지 않게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from Friendship f where "
            + "(f.requesterId = :a and f.addresseeId = :b) "
            + "or (f.requesterId = :b and f.addresseeId = :a)")
    void deleteBetween(@Param("a") String userA, @Param("b") String userB);

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
}
