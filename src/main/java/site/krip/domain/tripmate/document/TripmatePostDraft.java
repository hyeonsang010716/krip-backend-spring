package site.krip.domain.tripmate.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 게시글 임시저장 (MongoDB). 유저당 1개(user_id unique), 자동 저장 upsert. 모든 필드 nullable.
 */
@Document(collection = "tripmate_post_draft")
public class TripmatePostDraft {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("user_id")
    private String userId;

    @Field("title")
    private String title;

    @Field("content")
    private String content;

    @Field("preferred_age_min")
    private Integer preferredAgeMin;

    @Field("preferred_age_max")
    private Integer preferredAgeMax;

    @Field("preferred_gender")
    private String preferredGender;

    @Field("region")
    private String region;

    @Field("travel_start_date")
    private LocalDate travelStartDate;

    @Field("travel_end_date")
    private LocalDate travelEndDate;

    @Field("companion_type")
    private String companionType;

    @Field("image_urls")
    private List<String> imageUrls = new ArrayList<>();

    @Field("updated_at")
    private Instant updatedAt;

    protected TripmatePostDraft() {
    }

    public String getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Integer getPreferredAgeMin() {
        return preferredAgeMin;
    }

    public Integer getPreferredAgeMax() {
        return preferredAgeMax;
    }

    public String getPreferredGender() {
        return preferredGender;
    }

    public String getRegion() {
        return region;
    }

    public LocalDate getTravelStartDate() {
        return travelStartDate;
    }

    public LocalDate getTravelEndDate() {
        return travelEndDate;
    }

    public String getCompanionType() {
        return companionType;
    }

    public List<String> getImageUrls() {
        return imageUrls == null ? List.of() : imageUrls;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
