package site.krip.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import site.krip.domain.notification.service.InboxService;
import site.krip.support.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재좋아요 dedup 동시성 검증 — hidden 문서 부활(upsert)과 hide 가 경합해도 같은 dedup 튜플의
 * visible 문서가 2건 이상 생기지 않아야 한다(partial unique index + upsert 멱등).
 */
class InboxRelikeRaceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private InboxService inboxService;

    @Autowired
    private MongoTemplate mongo;

    @Test
    @DisplayName("동시 재좋아요 ↔ hide 경합에서도 같은 튜플 visible 문서는 최대 1건")
    void concurrentRelikeAndHideNeverDuplicatesVisible() throws Exception {
        String recipient = fixtures.createActiveUser("dedupR");
        String actor = fixtures.createActiveUser("dedupA");
        String postId = "post-race-1";

        int rounds = 25;
        int threads = 16;
        for (int round = 0; round < rounds; round++) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (idx % 4 == 0) {
                        // hide all visible — 부활(upsert)과 동시에 display=true→false 를 일으켜 경합 유도
                        hideAllVisible(recipient);
                    } else {
                        inboxService.notifyFeedLike(recipient, actor, "dedupA", null, postId, "preview");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            long visible = countVisible(recipient, actor, postId);
            assertThat(visible)
                    .as("round %d — 같은 튜플 visible 문서 수", round)
                    .isLessThanOrEqualTo(1);
        }
    }

    private void hideAllVisible(String recipient) {
        mongo.updateMulti(
                Query.query(Criteria.where("recipient_id").is(recipient).and("display").is(true)),
                new Update().set("display", false), "inbox");
    }

    private long countVisible(String recipient, String actor, String postId) {
        return mongo.count(
                Query.query(Criteria.where("recipient_id").is(recipient)
                        .and("actor_id").is(actor).and("target_id").is(postId).and("display").is(true)),
                "inbox");
    }
}
