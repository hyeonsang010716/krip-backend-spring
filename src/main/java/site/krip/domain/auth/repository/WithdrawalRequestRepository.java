package site.krip.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import site.krip.domain.auth.document.WithdrawalRequest;

import java.time.Instant;
import java.util.List;

/** 탈퇴 요청 MongoDB 접근 — 유저당 1건, 적재는 항상 upsert(재요청 가드는 RDB status). */
@Repository
@RequiredArgsConstructor
public class WithdrawalRequestRepository {

    private final MongoTemplate mongo;

    /** 동일 user_id doc 가 있으면 시각만 갱신, 없으면 삽입 (round-trip 1회, race-safe). */
    public void upsert(String userId, Instant requestedAt, Instant scheduledPurgeAt) {
        Query query = Query.query(Criteria.where("user_id").is(userId));
        Update update = new Update()
                .set("user_id", userId)
                .set("requested_at", requestedAt)
                .set("scheduled_purge_at", scheduledPurgeAt);
        mongo.upsert(query, update, WithdrawalRequest.class);
    }

    /** {@code scheduled_purge_at <= now} 인 모든 요청 조회. */
    public List<WithdrawalRequest> findDue(Instant now) {
        Query query = Query.query(Criteria.where("scheduled_purge_at").lte(now));
        return mongo.find(query, WithdrawalRequest.class);
    }

    /** user_id 의 탈퇴 요청 doc 삭제 — 영구 삭제 사이클 최종 단계. */
    public void deleteByUserId(String userId) {
        Query query = Query.query(Criteria.where("user_id").is(userId));
        mongo.remove(query, WithdrawalRequest.class);
    }
}
