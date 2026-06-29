package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
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
class FriendConcurrencyRecoveryServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserBlockService userBlockService;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    /** 두 작업을 최대한 동시에 출발시키고 (성공 수, 마지막 예외) 를 돌려준다. */
    private Result runConcurrently(Runnable op) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<>();

        for (int i = 0; i < 2; i++) {
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
        done.await(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        return new Result(ok.get(), err.get());
    }

    @Test
    @DisplayName("동시 친구요청: 정확히 하나만 성공, 나머지는 400(500 아님), 최종 1건")
    void concurrentSendRequest() throws Exception {
        String a = fixtures.createActiveUser("concReqA");
        String b = fixtures.createActiveUser("concReqB");

        Result r = runConcurrently(() -> friendshipService.sendRequest(a, b));

        assertThat(r.successCount()).isEqualTo(1);
        assertThat(r.lastError()).isInstanceOf(ApiException.class);
        assertThat(((ApiException) r.lastError()).getStatus()).isEqualTo(400);
        assertThat(friendshipRepository.findBetween(a, b)).isPresent()
                .get().extracting(f -> f.getStatus()).isEqualTo(FriendshipStatus.PENDING);
    }

    @Test
    @DisplayName("동시 차단: 정확히 하나만 성공, 나머지는 400(500 아님), 최종 1건")
    void concurrentBlock() throws Exception {
        String a = fixtures.createActiveUser("concBlkA");
        String b = fixtures.createActiveUser("concBlkB");

        Result r = runConcurrently(() -> userBlockService.blockUser(a, b));

        assertThat(r.successCount()).isEqualTo(1);
        assertThat(r.lastError()).isInstanceOf(ApiException.class);
        assertThat(((ApiException) r.lastError()).getStatus()).isEqualTo(400);
        assertThat(userBlockRepository.existsByBlockerIdAndBlockedId(a, b)).isTrue();
        assertThat(userBlockRepository.findBlocksFirstPage(a,
                org.springframework.data.domain.PageRequest.of(0, 30))).hasSize(1);
    }

    private record Result(int successCount, Throwable lastError) {
    }
}
