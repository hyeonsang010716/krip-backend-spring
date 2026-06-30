package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.service.FriendshipService;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.global.common.exception.ApiException;
import site.krip.support.IntegrationTestSupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 친구요청/차단의 동시성 복구 분기 통합 — 같은 INSERT 경합 시 한쪽 성공, 다른 쪽은
 * DataIntegrityViolation → 재조회 catch 로 500이 아닌 400. "정확히 하나 성공 + 나머지 400 + 최종 1건" 불변식 검증.
 */
@DisplayName("친구/차단 동시성 — 단일 성공·나머지 400(500 아님)")
class FriendConcurrencyRecoveryServiceIntegrationTest extends IntegrationTestSupport {

    private static final int CONTENDERS = 2;
    private static final int AWAIT_TIMEOUT_SECONDS = 20;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserBlockService userBlockService;

    /** 두 작업을 최대한 동시에 출발시키고 (성공 수, 마지막 예외) 를 돌려준다. */
    private Result runConcurrently(Runnable op) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONTENDERS);
        AtomicInteger ok = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<>();

        for (int i = 0; i < CONTENDERS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    op.run();
                    ok.incrementAndGet();
                } catch (Throwable t) {
                    err.set(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(finished).as("두 작업이 제한 시간 내 종료").isTrue();
        return new Result(ok.get(), err.get());
    }

    @Test
    @DisplayName("동시 친구요청: 정확히 하나만 성공, 나머지는 400(500 아님), 최종 1건")
    void concurrentSendRequest() throws Exception {
        // given
        String a = fixtures.createActiveUser("concReqA");
        String b = fixtures.createActiveUser("concReqB");

        // when
        Result r = runConcurrently(() -> friendshipService.sendRequest(a, b));

        // then
        assertThat(r.successCount()).isEqualTo(1);
        assertThat(r.lastError()).isInstanceOf(ApiException.class);
        assertThat(((ApiException) r.lastError()).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(friendshipRepository.findBetween(a, b)).isPresent()
                .get().extracting(Friendship::getStatus).isEqualTo(FriendshipStatus.PENDING);
    }

    @Test
    @DisplayName("동시 차단: 정확히 하나만 성공, 나머지는 400(500 아님), 최종 1건")
    void concurrentBlock() throws Exception {
        // given
        String a = fixtures.createActiveUser("concBlkA");
        String b = fixtures.createActiveUser("concBlkB");

        // when
        Result r = runConcurrently(() -> userBlockService.blockUser(a, b));

        // then
        assertThat(r.successCount()).isEqualTo(1);
        assertThat(r.lastError()).isInstanceOf(ApiException.class);
        assertThat(((ApiException) r.lastError()).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(userBlockRepository.existsByBlockerIdAndBlockedId(a, b)).isTrue();
        assertThat(userBlockRepository.findBlocks(a, null, null, 30)).hasSize(1);
    }

    private record Result(int successCount, Throwable lastError) {
    }
}
