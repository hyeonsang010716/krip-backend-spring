package site.krip.domain.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.auth.entity.UserDetailInform;

import java.util.Optional;

/** 유저 상세 정보 RDB 접근. PK = user_id 이므로 {@code findById(userId)} 가 곧 user 조회. */
public interface UserDetailInformRepository extends JpaRepository<UserDetailInform, String> {

    Optional<UserDetailInform> findByEmail(String email);

    /**
     * 행 잠금(SELECT ... FOR UPDATE)으로 조회 — 프로필 이미지 컬럼의 check-then-set 을 직렬화한다.
     * 동시 추가/수정/삭제가 같은 행을 잠가, READ_COMMITTED 의 lost-update(둘 다 null 읽고 덮어씀)를 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from UserDetailInform d where d.userId = :userId")
    Optional<UserDetailInform> findByIdForUpdate(@Param("userId") String userId);
}
