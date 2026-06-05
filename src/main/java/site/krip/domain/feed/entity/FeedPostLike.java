package site.krip.domain.feed.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 피드 게시물 좋아요. 복합 PK (user_id, post_id) — 유저당 게시물 1회.
 */
@Entity
@Table(name = "feed_post_like", indexes = {
        @Index(name = "ix_feed_post_like_post_id", columnList = "post_id")
})
@IdClass(FeedPostLikeId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedPostLike {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Id
    @Column(name = "post_id", length = 50)
    private String postId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public FeedPostLike(String userId, String postId) {
        this.userId = userId;
        this.postId = postId;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
