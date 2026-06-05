package site.krip.domain.friend.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;

import java.time.Instant;
import java.util.List;

/**
 * 친구 추가 화면 유저 검색.
 *
 * <p>ACTIVE 유저를 user_id / user_name 부분일치로 검색하되 본인 · 내가 차단 ·
 * 나를 차단한 유저는 제외. detail INNER JOIN 으로 2차 미완료 유저 자연 제외, 가입 최신순
 * (created_at, user_id) 커서 페이지네이션.
 */
public interface FriendUserSearchRepository extends Repository<User, String> {

    @Query("select distinct u from User u join fetch u.detail d "
            + "where u.userId <> :viewerId and u.status = :active "
            + "and (lower(u.userId) like lower(:pattern) escape '!' "
            + "     or lower(d.userName) like lower(:pattern) escape '!') "
            + "and u.userId not in (select b.blockedId from UserBlock b where b.blockerId = :viewerId) "
            + "and u.userId not in (select b2.blockerId from UserBlock b2 where b2.blockedId = :viewerId)")
    List<User> searchFirstPage(@Param("viewerId") String viewerId,
                               @Param("active") UserStatus active,
                               @Param("pattern") String pattern,
                               Pageable pageable);

    @Query("select distinct u from User u join fetch u.detail d "
            + "where u.userId <> :viewerId and u.status = :active "
            + "and (lower(u.userId) like lower(:pattern) escape '!' "
            + "     or lower(d.userName) like lower(:pattern) escape '!') "
            + "and u.userId not in (select b.blockedId from UserBlock b where b.blockerId = :viewerId) "
            + "and u.userId not in (select b2.blockerId from UserBlock b2 where b2.blockedId = :viewerId) "
            + "and (u.createdAt < :cursorAt or (u.createdAt = :cursorAt and u.userId < :cursor))")
    List<User> searchAfterCursor(@Param("viewerId") String viewerId,
                                 @Param("active") UserStatus active,
                                 @Param("pattern") String pattern,
                                 @Param("cursorAt") Instant cursorAt,
                                 @Param("cursor") String cursor,
                                 Pageable pageable);

    @Query("select u.createdAt from User u where u.userId = :userId")
    java.util.Optional<Instant> findCreatedAt(@Param("userId") String userId);
}
