package site.krip.domain.friend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.friend.entity.UserBlock;

import java.util.List;
import java.util.Optional;

/**
 * 유저 차단 RDB 접근. 차단 목록 키셋 페이지네이션은 {@link UserBlockRepositoryCustom} 참고.
 */
public interface UserBlockRepository extends JpaRepository<UserBlock, String>, UserBlockRepositoryCustom {

    Optional<UserBlock> findByBlockerIdAndBlockedId(String blockerId, String blockedId);

    boolean existsByBlockerIdAndBlockedId(String blockerId, String blockedId);

    /** 두 유저 간 차단 관계(방향 무관) — 최대 2 row. */
    @Query("select b from UserBlock b where "
            + "(b.blockerId = :a and b.blockedId = :b) or (b.blockerId = :b and b.blockedId = :a)")
    List<UserBlock> findBlocksBetween(@Param("a") String userA, @Param("b") String userB);
}
