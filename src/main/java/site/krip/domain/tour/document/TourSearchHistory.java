package site.krip.domain.tour.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 관광 장소 검색 기록.
 * 유저당 최대 10개, 초과 시 가장 오래된 것 자동 삭제.
 */
@Document(collection = "tour_search_history")
public class TourSearchHistory {

    @Id
    private String id;

    @Indexed
    @Field("user_id")
    private String userId;

    @Field("search_name")
    private String searchName;

    @Field("created_at")
    private Instant createdAt;

    protected TourSearchHistory() {
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
