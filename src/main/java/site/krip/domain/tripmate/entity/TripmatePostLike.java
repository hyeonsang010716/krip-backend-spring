package site.krip.domain.tripmate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 여행 메이트 게시글 좋아요 — 유저당 게시글 1회.
 * 복합 PK (user_id, post_id). 양 FK 모두 DB CASCADE.
 */
@Entity
@Table(name = "tripmate_post_like", indexes = {
        @Index(name = "ix_tripmate_post_like_post_id", columnList = "post_id")
})
@IdClass(TripmatePostLikeId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripmatePostLike {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Id
    @Column(name = "post_id", length = 50)
    private String postId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TripmatePostLike(String userId, String postId) {
        this.userId = userId;
        this.postId = postId;
    }
}
