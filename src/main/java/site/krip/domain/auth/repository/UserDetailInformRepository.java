package site.krip.domain.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.krip.domain.auth.entity.UserDetailInform;

import java.util.Optional;

/** 유저 상세 정보 RDB 접근. PK = user_id 이므로 {@code findById(userId)} 가 곧 user 조회. */
public interface UserDetailInformRepository extends JpaRepository<UserDetailInform, String> {

    Optional<UserDetailInform> findByEmail(String email);
}
