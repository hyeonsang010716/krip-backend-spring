package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Friendship 낙관적 락(@Version) 통합 — 같은 관계 행을 stale 핸들로 수정하면 충돌이 발생함을 검증.
 * (@SpringBootTest 는 메서드 트랜잭션을 열지 않아 각 repo 호출이 독립 커밋되므로 동시 수정을 재현할 수 있다.)
 */
class FriendshipOptimisticLockTest extends IntegrationTestSupport {

    @Autowired
    private FriendshipRepository friendshipRepo;

    @Test
    @DisplayName("stale 버전으로 수정 시 OptimisticLockingFailureException")
    void staleUpdateThrows() {
        String a = fixtures.createActiveUser("락A");
        String b = fixtures.createActiveUser("락B");
        Friendship stale = friendshipRepo.saveAndFlush(new Friendship(a, b)); // version 0

        // 다른 경로가 먼저 수락 → DB version 0→1
        Friendship concurrent = friendshipRepo.findById(stale.getFriendshipId()).orElseThrow();
        concurrent.accept();
        friendshipRepo.saveAndFlush(concurrent);

        // 보관해 둔 stale 핸들(version 0)로 수정 → 충돌
        stale.accept();
        assertThatThrownBy(() -> friendshipRepo.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
