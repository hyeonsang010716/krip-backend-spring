package site.krip.domain.notification.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 사용자 인박스 항목.
 *
 * <p>actor 의 행위가 recipient 인박스에 쌓인다. actor 닉네임/프로필 + target preview 는 insert 시점 snapshot.
 * soft hide({@code display=false}) + 30일 TTL hard delete. {@code read_at=null} = 미읽음.
 * {@code type}/{@code target_type} 은 소문자 value 문자열로 저장한다.
 */
@Document(collection = "inbox")
public class InboxItem {

    @Id
    private String id;

    @Field("recipient_id")
    private String recipientId;

    @Field("actor_id")
    private String actorId;

    @Field("type")
    private String type;

    @Field("target_type")
    private String targetType;

    @Field("target_id")
    private String targetId;

    @Field("comment_id")
    private String commentId;

    @Field("actor_name")
    private String actorName;

    @Field("actor_profile_image_url")
    private String actorProfileImageUrl;

    @Field("target_preview")
    private String targetPreview;

    @Field("comment_preview")
    private String commentPreview;

    @Field("display")
    private boolean display;

    @Field("read_at")
    private Instant readAt;

    @Field("created_at")
    private Instant createdAt;

    protected InboxItem() {
    }

    private InboxItem(String recipientId, String actorId, InboxItemType type, TargetType targetType,
                      String targetId, String commentId, String actorName, String actorProfileImageUrl,
                      String targetPreview, String commentPreview) {
        this.recipientId = recipientId;
        this.actorId = actorId;
        this.type = type.getValue();
        this.targetType = targetType.getValue();
        this.targetId = targetId;
        this.commentId = commentId;
        this.actorName = actorName;
        this.actorProfileImageUrl = actorProfileImageUrl;
        this.targetPreview = targetPreview;
        this.commentPreview = commentPreview;
        this.display = true;
        this.readAt = null;
        this.createdAt = Instant.now();
    }

    public static InboxItem feedLike(String recipientId, String actorId, String actorName,
                                     String actorProfileImageUrl, String postId, String postPreview) {
        return new InboxItem(recipientId, actorId, InboxItemType.FEED_LIKE, TargetType.FEED_POST,
                postId, null, actorName, actorProfileImageUrl, postPreview, null);
    }

    public static InboxItem feedComment(String recipientId, String actorId, String actorName,
                                        String actorProfileImageUrl, String postId, String postPreview,
                                        String commentId, String commentPreview) {
        return new InboxItem(recipientId, actorId, InboxItemType.FEED_COMMENT, TargetType.FEED_POST,
                postId, commentId, actorName, actorProfileImageUrl, postPreview, commentPreview);
    }

    public static InboxItem tripmateLike(String recipientId, String actorId, String actorName,
                                         String actorProfileImageUrl, String postId, String postPreview) {
        return new InboxItem(recipientId, actorId, InboxItemType.TRIPMATE_LIKE, TargetType.TRIPMATE_POST,
                postId, null, actorName, actorProfileImageUrl, postPreview, null);
    }

    public String getId() {
        return id;
    }

    public String getActorId() {
        return actorId;
    }

    public InboxItemType getType() {
        return InboxItemType.from(type);
    }

    public TargetType getTargetType() {
        return TargetType.from(targetType);
    }

    public String getTargetId() {
        return targetId;
    }

    public String getCommentId() {
        return commentId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getActorProfileImageUrl() {
        return actorProfileImageUrl;
    }

    public String getTargetPreview() {
        return targetPreview;
    }

    public String getCommentPreview() {
        return commentPreview;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
