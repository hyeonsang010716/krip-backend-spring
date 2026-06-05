package site.krip.domain.tripmate.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 업로드 이미지 추적 (MongoDB). 유저별 업로드 전체 추적.
 */
@Document(collection = "tripmate_image")
public class TripmateImage {

    @Id
    private String id;

    @Indexed
    @Field("user_id")
    private String userId;

    @Indexed(unique = true)
    @Field("image_id")
    private String imageId;

    @Field("image_url")
    private String imageUrl;

    @Field("timestamp")
    private Instant timestamp;

    protected TripmateImage() {
    }

    public TripmateImage(String userId, String imageId, String imageUrl, Instant timestamp) {
        this.userId = userId;
        this.imageId = imageId;
        this.imageUrl = imageUrl;
        this.timestamp = timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public String getImageId() {
        return imageId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
