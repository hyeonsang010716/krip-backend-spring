package site.krip.domain.chat.repository;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MongoDB {@code chat_message} 리포지토리.
 *
 * <p>content 가 type 별 다형(text=String / image·file·system=Document)이라 ODM 대신 raw Document 로 다룬다.
 * 시각(created_at/edited_at/deleted_at)은 BSON Date 로 저장한다.
 */
@Repository
public class ChatMessageRepository {

    public static final String COLLECTION = "chat_message";

    private final MongoCollection<Document> collection;

    public ChatMessageRepository(MongoTemplate mongoTemplate) {
        this.collection = mongoTemplate.getCollection(COLLECTION);
    }

    private static final String IDX_ROOM_SEQ = "uq_chat_message_room_seq";
    private static final String IDX_ROOM_SENDER_CLIENT = "uq_chat_message_room_sender_client";

    @PostConstruct
    void createIndexes() {
        collection.createIndex(
                Indexes.ascending("chat_room_id", "server_seq"),
                new IndexOptions().name(IDX_ROOM_SEQ).unique(true));
        // 멱등 앵커 — 동일 (room, sender, client_msg_id) 재시도 중복 차단. client_msg_id 없는 시스템 메시지는 제외(partial).
        collection.createIndex(
                Indexes.ascending("chat_room_id", "sender_id", "client_msg_id"),
                new IndexOptions().name(IDX_ROOM_SENDER_CLIENT).unique(true)
                        .partialFilterExpression(Filters.exists("client_msg_id", true)));
        collection.createIndex(
                Indexes.compoundIndex(Indexes.ascending("chat_room_id"), Indexes.descending("created_at")),
                new IndexOptions().name("ix_chat_message_room_created_at"));
    }

    /** 메시지 1건 insert. seq 중복은 {@link DuplicateSeqException}, client_msg_id 중복은 {@link DuplicateClientMsgException}. */
    public void insert(Document document) {
        try {
            collection.insertOne(document);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                String msg = e.getError().getMessage();
                if (msg != null && msg.contains(IDX_ROOM_SENDER_CLIENT)) {
                    throw new DuplicateClientMsgException(e);
                }
                throw new DuplicateSeqException(e);
            }
            throw e;
        }
    }

    /** (room, sender, client_msg_id) 로 기존 메시지 조회 — 재시도 멱등 ack 용. */
    public Document findByClientMsgId(String chatRoomId, String senderId, String clientMsgId) {
        return collection.find(Filters.and(
                Filters.eq("chat_room_id", chatRoomId),
                Filters.eq("sender_id", senderId),
                Filters.eq("client_msg_id", clientMsgId))).first();
    }

    /** 방의 최대 server_seq (없으면 0) — seq 복구 경로의 base. */
    public long getMaxServerSeq(String chatRoomId) {
        Document doc = collection.find(Filters.eq("chat_room_id", chatRoomId))
                .sort(Sorts.descending("server_seq"))
                .projection(new Document("server_seq", 1).append("_id", 0))
                .first();
        return doc != null ? ((Number) doc.get("server_seq")).longValue() : 0L;
    }

    /** seq &lt; before 메시지 DESC, limit+1 건(호출측이 has_more 판정). */
    public List<Document> findBefore(String chatRoomId, long beforeSeq, int limit) {
        return toList(collection.find(Filters.and(
                        Filters.eq("chat_room_id", chatRoomId),
                        Filters.lt("server_seq", beforeSeq)))
                .sort(Sorts.descending("server_seq"))
                .limit(limit + 1));
    }

    /** seq &gt; after 메시지 ASC, limit+1 건 — 재동기화 catch-up. */
    public List<Document> findAfter(String chatRoomId, long afterSeq, int limit) {
        return toList(collection.find(Filters.and(
                        Filters.eq("chat_room_id", chatRoomId),
                        Filters.gt("server_seq", afterSeq)))
                .sort(Sorts.ascending("server_seq"))
                .limit(limit + 1));
    }

    public Document findById(String messageId) {
        return collection.find(Filters.eq("_id", messageId)).first();
    }

    /** 여러 _id 를 {id: doc} 으로 — 방 리스트 미리보기 배치. */
    public Map<String, Document> findByIds(Collection<String> messageIds) {
        Map<String, Document> result = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return result;
        }
        for (Document doc : collection.find(Filters.in("_id", messageIds))) {
            result.put(doc.getString("_id"), doc);
        }
        return result;
    }

    /** 방별 최신 메시지 1건 배치 aggregate — reconcile 의 dirty 처리용. */
    public Map<String, LastMessage> findLastByRooms(Collection<String> roomIds) {
        Map<String, LastMessage> result = new LinkedHashMap<>();
        if (roomIds == null || roomIds.isEmpty()) {
            return result;
        }
        List<Bson> pipeline = List.of(
                new Document("$match", new Document("chat_room_id", new Document("$in", roomIds))),
                new Document("$sort", new Document("chat_room_id", 1).append("server_seq", -1)),
                new Document("$group", new Document("_id", "$chat_room_id")
                        .append("message_id", new Document("$first", "$_id"))
                        .append("server_seq", new Document("$first", "$server_seq"))
                        .append("created_at", new Document("$first", "$created_at"))));
        for (Document doc : collection.aggregate(pipeline)) {
            result.put(doc.getString("_id"), new LastMessage(
                    doc.getString("message_id"),
                    ((Number) doc.get("server_seq")).longValue(),
                    doc.getDate("created_at")));
        }
        return result;
    }

    /** seq &gt; after 의 비시스템 메시지 개수 — unread 복구. limit 으로 캡(999+) 지원. */
    public long countAfterSeq(String chatRoomId, long afterSeq, int limit) {
        return collection.countDocuments(
                Filters.and(
                        Filters.eq("chat_room_id", chatRoomId),
                        Filters.gt("server_seq", afterSeq),
                        Filters.ne("type", "system")),
                new com.mongodb.client.model.CountOptions().limit(limit));
    }

    /** 본문 교체 + edited_at. modified 1 건이면 true. */
    public boolean updateContent(String messageId, Object newContent, java.util.Date editedAt) {
        return collection.updateOne(Filters.eq("_id", messageId),
                Updates.combine(Updates.set("content", newContent), Updates.set("edited_at", editedAt)))
                .getModifiedCount() == 1;
    }

    /** soft delete — deleted_at 세팅 + content=null. row 보존. */
    public boolean softDelete(String messageId, java.util.Date deletedAt) {
        return collection.updateOne(Filters.eq("_id", messageId),
                Updates.combine(Updates.set("deleted_at", deletedAt), Updates.set("content", null)))
                .getModifiedCount() == 1;
    }

    private static List<Document> toList(Iterable<Document> it) {
        List<Document> list = new ArrayList<>();
        it.forEach(list::add);
        return list;
    }

    /** 방별 최신 메시지 요약. */
    public record LastMessage(String messageId, long serverSeq, java.util.Date createdAt) {
    }

    /** seq UNIQUE 중복 — service 가 force_jump 재시도로 분기. */
    public static class DuplicateSeqException extends RuntimeException {
        public DuplicateSeqException(Throwable cause) {
            super(cause);
        }
    }

    /** client_msg_id UNIQUE 중복 — 동일 메시지 재시도. service 가 기존 ack 반환(멱등). */
    public static class DuplicateClientMsgException extends RuntimeException {
        public DuplicateClientMsgException(Throwable cause) {
            super(cause);
        }
    }
}
