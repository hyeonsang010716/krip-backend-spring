package site.krip.domain.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.entity.TravelStyle;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;

import java.util.List;
import java.util.Optional;

/** 유저 RDB 접근. */
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByAuthProviderAndAuthProviderId(OAuthProvider authProvider, String authProviderId);

    /** user row 에 X-lock (SELECT ... FOR UPDATE) — cancel/purge 동시성 상호배타. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") String userId);

    /** 미들웨어/필터용 — 유저 + detail 동시 로드 (2차 가입 여부 판정). */
    @Query("select u from User u left join fetch u.detail where u.userId = :userId")
    Optional<User> findByIdWithDetail(@Param("userId") String userId);

    /** 프로필 조회용 — 유저 + detail + travelStyles 일괄 로드. */
    @Query("select distinct u from User u "
            + "left join fetch u.detail left join fetch u.travelStyles "
            + "where u.userId = :userId")
    Optional<User> findByIdWithProfile(@Param("userId") String userId);

    /** 여러 유저 + detail 배치 로드 (채팅 peer/멤버 프로필 N+1 회피). */
    @Query("select distinct u from User u left join fetch u.detail where u.userId in :userIds")
    List<User> findByIdsWithProfile(@Param("userIds") java.util.Collection<String> userIds);

    /** 주어진 유저 중 전역 알림 미차단(NULL/false)만 (FCM 푸시 게이팅). */
    @Query("select u.userId from User u where u.userId in :userIds and u.notificationMuted is not true")
    List<String> findUnmutedUserIds(@Param("userIds") java.util.Collection<String> userIds);

    /** 유저의 여행 스타일 값만 투영 (UserQueryPort.findTravelStyles 용 — UserTravelStyle 엔티티 노출 회피). */
    @Query("select ts.style from User u join u.travelStyles ts where u.userId = :userId")
    List<TravelStyle> findTravelStyleValues(@Param("userId") String userId);

    // TODO 운영 전환 시 — 무한정 전체 조회. (created_at, user_id) 커서 페이지네이션 +
    //  travelStyles 컬렉션 fetch 제거(별도 IN 배치 로드) 필요. 현재는 DEV 전용 API.
    /** 탐색 목록 — 본인 제외 ACTIVE 유저 + 프로필, 최신 가입순. */
    @Query("select distinct u from User u "
            + "left join fetch u.detail left join fetch u.travelStyles "
            + "where u.userId <> :excludeUserId and u.status = :active "
            + "order by u.createdAt desc, u.userId desc")
    List<User> findActiveOthersWithProfile(@Param("excludeUserId") String excludeUserId,
                                           @Param("active") UserStatus active);

    /** 하드 삭제 — DB FK CASCADE 로 연관 데이터 전체 제거. rowcount 반환. */
    @Modifying
    @Query("delete from User u where u.userId = :userId")
    int hardDeleteById(@Param("userId") String userId);
}
