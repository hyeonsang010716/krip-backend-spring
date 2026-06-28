package site.krip.domain.tour.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import site.krip.domain.tour.document.TourSearchHistory;

import java.time.Instant;
import java.util.List;

/**
 * 관광 장소 검색 기록 MongoDB 접근.
 * 동일 검색어는 시간만 갱신, 10개 초과 시 가장 오래된 것 자동 삭제.
 */
@Repository
@RequiredArgsConstructor
public class TourSearchHistoryRepository {

    private static final int MAX_SEARCH_HISTORY = 10;

    private final MongoTemplate mongo;

    /** 검색어 저장 — 동일 검색어는 시각만 갱신(upsert), 10개 초과 시 가장 오래된 것 삭제. */
    public TourSearchHistory save(String userId, String searchName) {
        Update update = new Update()
                .set("user_id", userId)
                .set("search_name", searchName)
                .set("created_at", Instant.now());
        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);
        TourSearchHistory saved = mongo.findAndModify(
                Query.query(Criteria.where("user_id").is(userId).and("search_name").is(searchName)),
                update, options, TourSearchHistory.class);

        trimOldest(userId);
        return saved;
    }

    private void trimOldest(String userId) {
        Query base = Query.query(Criteria.where("user_id").is(userId));
        long count = mongo.count(base, TourSearchHistory.class);
        if (count <= MAX_SEARCH_HISTORY) {
            return;
        }
        Query oldest = Query.query(Criteria.where("user_id").is(userId))
                .with(Sort.by(Sort.Direction.ASC, "created_at"))
                .limit((int) (count - MAX_SEARCH_HISTORY));
        oldest.fields().include("_id");
        List<String> ids = mongo.find(oldest, TourSearchHistory.class).stream()
                .map(TourSearchHistory::getId).toList();
        if (!ids.isEmpty()) {
            // N 회 개별 remove 대신 _id IN 단일 삭제.
            mongo.remove(Query.query(Criteria.where("_id").in(ids)), TourSearchHistory.class);
        }
    }

    /** 검색 기록 조회 (최신순, 최대 10개). */
    public List<TourSearchHistory> findByUserId(String userId) {
        Query query = Query.query(Criteria.where("user_id").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "created_at"))
                .limit(MAX_SEARCH_HISTORY);
        return mongo.find(query, TourSearchHistory.class);
    }

    public void deleteOne(String userId, String searchName) {
        mongo.remove(
                Query.query(Criteria.where("user_id").is(userId).and("search_name").is(searchName)),
                TourSearchHistory.class);
    }

    public void deleteAllByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("user_id").is(userId)), TourSearchHistory.class);
    }
}
