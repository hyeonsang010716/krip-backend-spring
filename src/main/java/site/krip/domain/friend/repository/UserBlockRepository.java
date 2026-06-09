package site.krip.domain.friend.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.friend.entity.UserBlock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 유저 차단 RDB 접근.
 */
public interface UserBlockRepository extends JpaRepository<UserBlock, String> {

    Optional<UserBlock> findByBlockerIdAndBlockedId(String blockerId, String blockedId);

    boolean existsByBlockerIdAndBlockedId(String blockerId, String blockedId);

    /** 두 유저 간 차단 관계(방향 무관) — 최대 2 row. */
    @Query("select b from UserBlock b where "
            + "(b.blockerId = :a and b.blockedId = :b) or (b.blockerId = :b and b.blockedId = :a)")
    List<UserBlock> findBlocksBetween(@Param("a") String userA, @Param("b") String userB);


    // ──────────────────── 차단 목록 (blocked 프로필 fetch) ────────────────────

    @Query("select b from UserBlock b "
            + "left join fetch b.blocked bu left join fetch bu.detail "
            + "where b.blockerId = :blockerId")
    List<UserBlock> findBlocksFirstPage(@Param("blockerId") String blockerId, Pageable pageable);

    @Query("select b from UserBlock b "
            + "left join fetch b.blocked bu left join fetch bu.detail "
            + "where b.blockerId = :blockerId "
            + "and (b.createdAt < :cursorAt or (b.createdAt = :cursorAt and b.blockId < :cursor))")
    List<UserBlock> findBlocksAfterCursor(@Param("blockerId") String blockerId,
                                          @Param("cursorAt") Instant cursorAt,
                                          @Param("cursor") String cursor,
                                          Pageable pageable);
}
