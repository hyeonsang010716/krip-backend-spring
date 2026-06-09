package site.krip.domain.friend.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 친구 추가 화면 유저 검색.
 *
 * <p>ACTIVE 유저를 user_id / user_name 부분일치로 검색하되 본인 · 내가 차단 ·
 * 나를 차단한 유저는 제외. detail INNER JOIN 으로 2차 미완료 유저 자연 제외, 가입 최신순
 * (created_at, user_id) 커서 페이지네이션.
 *
 * <p>검색 OR 의 두 분기(user_id / user_name)는 서로 다른 테이블이라 그대로 두면 인덱스를 못 타
 * users 풀스캔이 된다. 닉네임 분기는 서비스가 {@link #findUserIdsByNameLike}(user_name trigram) 로
 * user_id 를 먼저 해석해 IN-list 로 넘기고, user_id 분기는 trigram GIN(ix_users_user_id_trgm) 으로
 * 가속 — OR 를 모두 users.user_id 한 컬럼에 모아 BitmapOr 인덱스 스캔을 가능하게 한다.
 */
public interface FriendUserSearchRepository extends Repository<User, String> {

    /**
     * 닉네임 부분일치 → user_id 선해석. user_name trigram 인덱스로 가속(검색 쿼리의 IN 분기 재료).
     *
     * <p>메인 검색과 동일 정렬(created_at desc, user_id desc)로 상한을 적용한다 — 매치가 상한을 넘어도
     * "가장 최근 가입" 매치를 보존하므로 결과 앞쪽 페이지들은 누락 없이 완전하다(잘리는 건 깊은 꼬리뿐).
     * 정렬이 없으면 임의의 N개가 뽑혀 최신 유저가 1페이지에서 비결정적으로 사라진다.
     */
    @Query("select d.userId from UserDetailInform d join d.user u "
            + "where lower(d.userName) like lower(:pattern) escape '!' "
            + "order by u.createdAt desc, u.userId desc")
    List<String> findUserIdsByNameLike(@Param("pattern") String pattern, Pageable limit);

    @Query("select distinct u from User u join fetch u.detail d "
            + "where u.userId <> :viewerId and u.status = :active "
            + "and (lower(u.userId) like lower(:pattern) escape '!' or u.userId in :nameMatchedIds) "
            + "and u.userId not in (select b.blockedId from UserBlock b where b.blockerId = :viewerId) "
            + "and u.userId not in (select b2.blockerId from UserBlock b2 where b2.blockedId = :viewerId)")
    List<User> searchFirstPage(@Param("viewerId") String viewerId,
                               @Param("active") UserStatus active,
                               @Param("pattern") String pattern,
                               @Param("nameMatchedIds") Collection<String> nameMatchedIds,
                               Pageable pageable);

    @Query("select distinct u from User u join fetch u.detail d "
            + "where u.userId <> :viewerId and u.status = :active "
            + "and (lower(u.userId) like lower(:pattern) escape '!' or u.userId in :nameMatchedIds) "
            + "and u.userId not in (select b.blockedId from UserBlock b where b.blockerId = :viewerId) "
            + "and u.userId not in (select b2.blockerId from UserBlock b2 where b2.blockedId = :viewerId) "
            + "and (u.createdAt < :cursorAt or (u.createdAt = :cursorAt and u.userId < :cursor))")
    List<User> searchAfterCursor(@Param("viewerId") String viewerId,
                                 @Param("active") UserStatus active,
                                 @Param("pattern") String pattern,
                                 @Param("nameMatchedIds") Collection<String> nameMatchedIds,
                                 @Param("cursorAt") Instant cursorAt,
                                 @Param("cursor") String cursor,
                                 Pageable pageable);
}
