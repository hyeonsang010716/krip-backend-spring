package site.krip.domain.feed.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * 피드 게시물 댓글. 본문 ≥1자.
 */
@Entity
@Table(name = "feed_post_comment", indexes = {
        @Index(name = "ix_feed_post_comment_post_created", columnList = "post_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedPostComment {

    public static final int COMMENT_MAX_LENGTH = 500;

    @Id
    @Column(name = "comment_id", length = 50)
    private String commentId;

    @Column(name = "post_id", length = 50, nullable = false)
    private String postId;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Column(name = "content", length = COMMENT_MAX_LENGTH, nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FeedPostComment(String postId, String userId, String content) {
        this.commentId = IdGenerator.feedPostCommentId();
        this.postId = postId;
        this.userId = userId;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
