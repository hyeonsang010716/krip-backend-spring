package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import site.krip.domain.chat.service.BlockCacheService;
import site.krip.domain.friend.dto.response.UserBlockResponse;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 차단 캐시 무효화 트랜잭션 경계 회귀 — invalidateBlockCache 는 커밋 전 호출이라 Redis 실패 시
 * 예외 전파로 블록 INSERT + friendship 삭제까지 롤백돼야 한다(fail-closed). BlockCacheService mock 으로 Redis 장애 시뮬.
 */
@DisplayName("차단 캐시 롤백 — 무효화 실패 시 fail-closed 롤백")
class UserBlockCacheRollbackE2eTest extends IntegrationTestSupport {

    @MockitoBean
    private BlockCacheService blockCacheService;

    @Autowired
    private UserBlockService userBlockService;

    @Test
    @DisplayName("캐시 무효화(Redis) 실패 → 블록 INSERT 와 friendship 삭제가 모두 롤백된다(fail-closed)")
    void redisFailureRollsBackBlock() throws Exception {
        // given
        String a = fixtures.createActiveUser("롤백차단A");
        String b = fixtures.createActiveUser("롤백차단B");
        befriendViaApi(a, b);

        doThrow(new RuntimeException("redis down"))
                .when(blockCacheService).invalidateBlockCache(anyString(), anyString());

        // when
        assertThatThrownBy(() -> userBlockService.blockUser(a, b))
                .isInstanceOf(RuntimeException.class);

        // then
        // 블록은 커밋되지 않았고, friendship 도 삭제되지 않았다(트랜잭션 통째로 롤백).
        assertThat(userBlockRepository.existsByBlockerIdAndBlockedId(a, b)).isFalse();
        assertThat(friendshipRepository.findBetween(a, b)).isPresent();
    }

    @Test
    @DisplayName("캐시 무효화 성공 → 블록 커밋 + friendship 삭제(정상 경로 대조군)")
    void successCommitsBlock() throws Exception {
        // given
        String a = fixtures.createActiveUser("정상차단A");
        String b = fixtures.createActiveUser("정상차단B");
        befriendViaApi(a, b);

        // when
        // mock invalidateBlockCache 는 기본적으로 아무것도 하지 않음(성공으로 간주).
        UserBlockResponse resp = userBlockService.blockUser(a, b);

        // then
        assertThat(resp).isNotNull();
        assertThat(userBlockRepository.existsByBlockerIdAndBlockedId(a, b)).isTrue();
        assertThat(friendshipRepository.findBetween(a, b)).isEmpty();
    }
}
