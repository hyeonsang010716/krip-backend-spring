package site.krip.domain.notification.repository;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import site.krip.domain.notification.entity.FcmToken;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * FCM 토큰 RDB 접근.
 * 등록은 token UNIQUE 기준 upsert(owner 교체) — 서비스가 findByToken + 충돌 재조회로 race 처리.
 */
public interface FcmTokenRepository extends JpaRepository<FcmToken, String> {

    Optional<FcmToken> findByToken(String token);

    /** 그룹방 fan-out bulk. */
    List<FcmToken> findByUserIdIn(Collection<String> userIds);

    @Modifying
    @Transactional
    void deleteByUserIdAndToken(String userId, String token);

    @Modifying
    @Transactional
    void deleteByTokenIn(Collection<String> tokens);
}
