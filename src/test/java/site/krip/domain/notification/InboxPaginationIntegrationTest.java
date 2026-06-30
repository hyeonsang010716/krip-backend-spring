package site.krip.domain.notification;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import site.krip.domain.notification.document.InboxItem;
import site.krip.domain.notification.repository.InboxRepository;
import site.krip.support.IntegrationTestSupport;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인박스 keyset 커서 페이지네이션 회귀 — 커서가 created_at 단일키였을 때 동일 created_at 항목이
 * 페이지 경계에서 유실됐다. (created_at, _id) keyset 적용 후 전부 정확히 한 번씩 반환되어야 한다.
 */
@DisplayName("인박스 페이지네이션 — 동일 created_at 경계 무손실")
class InboxPaginationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private InboxRepository inboxRepo;

    @Autowired
    private MongoTemplate mongo;

    @Test
    @DisplayName("동일 created_at 항목이 페이지 경계를 가로질러도 skip/중복 없이 전부 반환된다")
    void keysetCursorLosesNothingAtEqualTimestampBoundary() {
        String recipient = "RCPT_" + new ObjectId().toHexString();
        int total = InboxRepository.PAGE_SIZE + 5; // 한 페이지 + 경계 넘어가는 5건
        Instant sharedAt = Instant.parse("2026-03-01T00:00:00Z"); // 전 항목 동일 시각

        Set<String> seeded = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            ObjectId id = new ObjectId();
            seeded.add(id.toHexString());
            mongo.getCollection("inbox").insertOne(new Document("_id", id)
                    .append("recipient_id", recipient)
                    .append("actor_id", "actor")
                    .append("type", "feed_like")
                    .append("target_type", "feed_post")
                    .append("target_id", "post-" + i) // dedup 유니크 인덱스 회피 위해 distinct
                    .append("display", true)
                    .append("created_at", Date.from(sharedAt)));
        }

        // 1페이지: 커서 없음 → PAGE_SIZE 만큼 + has_more 판정용 1건.
        List<InboxItem> page1 = inboxRepo.findByRecipient(recipient, null, null, InboxRepository.PAGE_SIZE);
        assertThat(page1).hasSize(InboxRepository.PAGE_SIZE + 1);
        page1 = page1.subList(0, InboxRepository.PAGE_SIZE);

        // 1페이지 마지막 항목으로 (created_at, _id) keyset 커서 구성.
        InboxItem last = page1.get(page1.size() - 1);
        List<InboxItem> page2 = inboxRepo.findByRecipient(
                recipient, last.getCreatedAt(), new ObjectId(last.getId()), InboxRepository.PAGE_SIZE);

        Set<String> returned = new LinkedHashSet<>();
        page1.forEach(it -> returned.add(it.getId()));
        int beforePage2 = returned.size();
        page2.forEach(it -> returned.add(it.getId()));

        // 경계의 5건이 유실되지 않고(구 코드라면 page2 가 비어 유실), 중복도 없어야 한다.
        assertThat(page2).hasSize(5);
        assertThat(returned.size() - beforePage2).isEqualTo(5); // page2 전부 신규(중복 0)
        assertThat(returned).hasSize(total).containsExactlyInAnyOrderElementsOf(seeded);
    }
}
