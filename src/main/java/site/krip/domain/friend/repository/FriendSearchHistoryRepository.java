package site.krip.domain.friend.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import site.krip.domain.friend.document.FriendSearchHistory;

import java.time.Instant;
import java.util.List;

/**
 * 친구 검색 기록 MongoDB 접근.
 * 동일 검색어는 시간만 갱신, 10개 초과 시 가장 오래된 것 자동 삭제.
 */
@Repository
public class FriendSearchHistoryRepository {

    private static final int MAX_SEARCH_HISTORY = 10;

    private final MongoTemplate mongo;

    public FriendSearchHistoryRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public FriendSearchHistory save(String userId, String searchName) {
        Update update = new Update()
                .set("user_id", userId)
                .set("search_name", searchName)
                .set("created_at", Instant.now());

        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);
        FriendSearchHistory saved = mongo.findAndModify(
                Query.query(Criteria.where("user_id").is(userId).and("search_name").is(searchName)),
                update, options, FriendSearchHistory.class);

        trimOldest(userId);
        return saved;
    }

    private void trimOldest(String userId) {
        Query base = Query.query(Criteria.where("user_id").is(userId));
        long count = mongo.count(base, FriendSearchHistory.class);
        if (count <= MAX_SEARCH_HISTORY) {
            return;
        }
        Query oldest = Query.query(Criteria.where("user_id").is(userId))
                .with(Sort.by(Sort.Direction.ASC, "created_at"))
                .limit((int) (count - MAX_SEARCH_HISTORY));
        for (FriendSearchHistory doc : mongo.find(oldest, FriendSearchHistory.class)) {
            mongo.remove(doc);
        }
    }

    public List<FriendSearchHistory> findByUserId(String userId) {
        Query query = Query.query(Criteria.where("user_id").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "created_at"))
                .limit(MAX_SEARCH_HISTORY);
        return mongo.find(query, FriendSearchHistory.class);
    }

    public void deleteOne(String userId, String searchName) {
        mongo.remove(
                Query.query(Criteria.where("user_id").is(userId).and("search_name").is(searchName)),
                FriendSearchHistory.class);
    }

    public void deleteAllByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("user_id").is(userId)), FriendSearchHistory.class);
    }
}
