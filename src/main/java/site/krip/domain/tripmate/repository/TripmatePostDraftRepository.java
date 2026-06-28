package site.krip.domain.tripmate.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import site.krip.domain.tripmate.document.TripmatePostDraft;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 게시글 임시저장 MongoDB 접근. 유저당 1개 upsert.
 */
@Repository
@RequiredArgsConstructor
public class TripmatePostDraftRepository {

    private final MongoTemplate mongo;

    /** 임시저장 upsert (단일 atomic). 갱신 후 문서 반환. */
    public TripmatePostDraft upsert(String userId, String title, String content,
                                    Integer preferredAgeMin, Integer preferredAgeMax,
                                    String preferredGender, String region,
                                    LocalDate travelStartDate, LocalDate travelEndDate,
                                    String companionType, List<String> imageUrls) {
        Update update = new Update()
                .set("user_id", userId)
                .set("title", title)
                .set("content", content)
                .set("preferred_age_min", preferredAgeMin)
                .set("preferred_age_max", preferredAgeMax)
                .set("preferred_gender", preferredGender)
                .set("region", region)
                .set("travel_start_date", travelStartDate)
                .set("travel_end_date", travelEndDate)
                .set("companion_type", companionType)
                .set("image_urls", imageUrls == null ? List.of() : imageUrls)
                .set("updated_at", Instant.now());

        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);
        return mongo.findAndModify(
                Query.query(Criteria.where("user_id").is(userId)),
                update, options, TripmatePostDraft.class);
    }

    public Optional<TripmatePostDraft> findByUserId(String userId) {
        return Optional.ofNullable(
                mongo.findOne(Query.query(Criteria.where("user_id").is(userId)), TripmatePostDraft.class));
    }

    public void deleteByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("user_id").is(userId)), TripmatePostDraft.class);
    }
}
