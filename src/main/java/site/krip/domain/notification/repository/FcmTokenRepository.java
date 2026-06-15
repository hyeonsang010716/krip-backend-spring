package site.krip.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.notification.entity.FcmToken;

import java.time.Instant;
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

    /** 재등록 — owner·updated_at 원자 갱신. 반환 0 = 동시 삭제로 행 없음 → 호출부가 insert 폴백. */
    @Modifying
    @Query("update FcmToken f set f.userId = :userId, f.updatedAt = :now where f.token = :token")
    int reassignOwner(@Param("token") String token, @Param("userId") String userId, @Param("now") Instant now);

    /**
     * 무효 토큰 정리 — 발송 시작 시점({@code asOf}) 이후 갱신된 행은 제외한다.
     * 발송~정리 사이 동일 토큰이 재등록(updated_at 갱신)되면 방금 유효해진 행을 지우지 않는다.
     * 호출부(sendChatPush)가 비-트랜잭션이라 자체 tx 로 정리. 반환값은 실제 삭제 건수.
     */
    @Modifying
    @Transactional
    int deleteByTokenInAndUpdatedAtLessThanEqual(Collection<String> tokens, Instant asOf);
}
