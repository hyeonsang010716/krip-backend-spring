package site.krip.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
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

    /** tx 경계는 호출 서비스(@Transactional unregisterToken)가 소유. */
    @Modifying
    void deleteByUserIdAndToken(String userId, String token);

    /** 호출부(sendChatPush)가 비-트랜잭션(FCM 호출 중 DB 커넥션 미점유)이라 자체 tx 로 만료 토큰 정리. */
    @Modifying
    @Transactional
    void deleteByTokenIn(Collection<String> tokens);
}
