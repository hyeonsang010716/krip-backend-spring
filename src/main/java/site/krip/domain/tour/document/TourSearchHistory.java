package site.krip.domain.tour.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 관광 장소 검색 기록.
 * 유저당 최대 10개, 초과 시 가장 오래된 것 자동 삭제.
 *
 * <p>{@code (user_id, search_name)} 복합 유니크 — upsert 매칭 키이자 동시 동일 키워드 중복 삽입 방지.
 * user_id 단독 조회(trim/count)도 이 인덱스의 prefix 로 처리된다.
 */
@Document(collection = "tour_search_history")
@CompoundIndex(name = "uq_user_search", def = "{'user_id': 1, 'search_name': 1}", unique = true)
public class TourSearchHistory {

    @Id
    private String id;

    @Field("user_id")
    private String userId;

    @Field("search_name")
    private String searchName;

    @Field("created_at")
    private Instant createdAt;

    protected TourSearchHistory() {
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getSearchName() {
        return searchName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
