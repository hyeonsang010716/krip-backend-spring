package site.krip.domain.notification.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import site.krip.domain.notification.document.InboxItem;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 인박스 컬렉션 접근.
 *
 * <p>권한 검증은 query 안에 recipient_id 를 포함해 atomic(타인 항목은 매칭 실패 → 0). 인덱스(dedup partial unique,
 * TTL 30일, 페이지네이션, actor)는 startup 에 생성. fan-out insert 의 dedup 충돌은 service 가 멱등 skip.
 */
@Repository
@RequiredArgsConstructor
public class InboxRepository {

    public static final int PAGE_SIZE = 20;
    public static final int UNREAD_COUNT_CAP = 999;
    private static final long TTL_SECONDS = 60L * 60 * 24 * 30;

    private final MongoTemplate mongo;
    private final Clock clock;

    @PostConstruct
    void createIndexes() {
        var coll = mongo.getCollection("inbox");
        // 페이지네이션 — keyset (created_at DESC, _id DESC) 정렬·predicate 를 완전히 커버하도록 _id 까지 포함.
        coll.createIndex(
                Indexes.compoundIndex(Indexes.ascending("recipient_id"), Indexes.ascending("display"),
                        Indexes.descending("created_at"), Indexes.descending("_id")),
                new IndexOptions().name("ix_inbox_recipient_display_created_id"));
        dropIndexQuietly(coll, "ix_inbox_recipient_display_created"); // _id 미포함 구 인덱스 정리(best-effort)
        coll.createIndex(
                Indexes.compoundIndex(Indexes.ascending("recipient_id"), Indexes.ascending("actor_id"),
                        Indexes.ascending("type"), Indexes.ascending("target_id"), Indexes.ascending("comment_id")),
                new IndexOptions().name("uq_inbox_dedup").unique(true)
                        .partialFilterExpression(new Document("display", true)));
        coll.createIndex(Indexes.ascending("actor_id"), new IndexOptions().name("ix_inbox_actor"));
        // 게시글 삭제 cascade(hideByTarget) — target 기준 조회 인덱스(없으면 COLLSCAN).
        coll.createIndex(
                Indexes.compoundIndex(Indexes.ascending("target_type"), Indexes.ascending("target_id")),
                new IndexOptions().name("ix_inbox_target"));
        coll.createIndex(Indexes.ascending("created_at"),
                new IndexOptions().name("ttl_inbox_created").expireAfter(TTL_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * dedup 키((recipient, actor, type, target, comment))로 upsert — 신규는 insert, 기존(숨김 포함)은
     * display=true·미읽음·최신순으로 되살린다(재좋아요 중복 누적 방지). 동시 fan-out 경합은 DuplicateKeyException propagate.
     */
    public void upsert(InboxItem item) {
        Query q = Query.query(Criteria.where("recipient_id").is(item.getRecipientId())
                .and("actor_id").is(item.getActorId())
                .and("type").is(item.getType().getValue())
                .and("target_id").is(item.getTargetId())
                .and("comment_id").is(item.getCommentId()));
        Update u = new Update()
                .set("display", true)
                .set("read_at", null)
                .set("created_at", item.getCreatedAt())
                .set("actor_name", item.getActorName())
                .set("actor_profile_image_url", item.getActorProfileImageUrl())
                .set("target_preview", item.getTargetPreview())
                .set("comment_preview", item.getCommentPreview())
                .setOnInsert("target_type", item.getTargetType().getValue());
        mongo.upsert(q, u, InboxItem.class);
    }

    /**
     * display=true 최신순(created_at DESC, _id DESC), limit+1 fetch(has_more 판정).
     * 커서는 (created_at, _id) keyset — 2키 정렬과 일치시켜 동일 시각 경계의 항목 skip/중복을 막는다.
     * {@code cursorId} 가 null 이면 timestamp-only(구 커서 하위호환): created_at &lt; cursorTs.
     */
    public List<InboxItem> findByRecipient(String recipientId, @Nullable Instant cursorTs,
                                           @Nullable ObjectId cursorId, int limit) {
        Criteria base = Criteria.where("recipient_id").is(recipientId).and("display").is(true);
        Criteria filter = base;
        if (cursorTs != null) {
            Criteria keyset = (cursorId == null)
                    ? Criteria.where("created_at").lt(cursorTs)
                    : new Criteria().orOperator(
                            Criteria.where("created_at").lt(cursorTs),
                            Criteria.where("created_at").is(cursorTs).and("_id").lt(cursorId));
            filter = new Criteria().andOperator(base, keyset);
        }
        Query q = Query.query(filter)
                .with(Sort.by(Sort.Order.desc("created_at"), Sort.Order.desc("_id")))
                .limit(limit + 1);
        return mongo.find(q, InboxItem.class);
    }

    /** 미읽음(display=true AND read_at=null) — cap 까지만 셈. */
    public long countUnread(String recipientId, int cap) {
        Query q = Query.query(Criteria.where("recipient_id").is(recipientId)
                .and("display").is(true).and("read_at").is(null)).limit(cap + 1);
        return mongo.count(q, InboxItem.class);
    }

    /** X 버튼 — 본인 소유 + display=true 만. 매칭 실패면 false(404). */
    public boolean hide(ObjectId id, String recipientId) {
        Query q = Query.query(Criteria.where("_id").is(id)
                .and("recipient_id").is(recipientId).and("display").is(true));
        return mongo.updateFirst(q, new Update().set("display", false), InboxItem.class)
                .getModifiedCount() == 1;
    }

    /** 지정한 항목들만 read 처리(멱등) — 본인 소유 + 미읽음만 매칭. 반영 건수 반환(이미 읽음/타인 항목은 0). */
    public long markReadByIds(String recipientId, Collection<ObjectId> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        Query q = Query.query(Criteria.where("_id").in(ids)
                .and("recipient_id").is(recipientId)
                .and("read_at").is(null));
        return mongo.updateMulti(q, new Update().set("read_at", clock.instant()), InboxItem.class)
                .getModifiedCount();
    }

    /** 게시글 삭제 cascade — (target_type, target_id) display=true soft hide. */
    public long hideByTarget(String targetType, String targetId) {
        Query q = Query.query(Criteria.where("target_type").is(targetType)
                .and("target_id").is(targetId).and("display").is(true));
        return mongo.updateMulti(q, new Update().set("display", false), InboxItem.class)
                .getModifiedCount();
    }

    /** 유저 탈퇴 cascade — recipient/actor 매칭 hard delete. */
    public long deleteByUser(String userId) {
        Query q = Query.query(new Criteria().orOperator(
                Criteria.where("recipient_id").is(userId),
                Criteria.where("actor_id").is(userId)));
        return mongo.remove(q, InboxItem.class).getDeletedCount();
    }

    /** 인덱스 정리용 — 없으면(최초 부팅 등) 무시. */
    private void dropIndexQuietly(MongoCollection<Document> coll, String name) {
        try {
            coll.dropIndex(name);
        } catch (RuntimeException e) {
            // index not found 등은 무시 (best-effort).
        }
    }
}
