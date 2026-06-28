package site.krip.domain.feed.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * 피드 게시물 — 이미지 1장 + visibility + caption.
 *
 * <p>URL 3종(original/small/medium) 모두 NOT NULL. 컴파운드 인덱스 {@code (user_id, created_at DESC, post_id DESC)} 로
 * 본인/친구/비친구 페이지네이션을 인덱스 정렬 그대로 커버하고, visibility 는 힙 필터로 처리한다(V10).
 */
@Entity
@Table(name = "feed_post", indexes = {
        @Index(name = "ix_feed_post_owner_created",
                columnList = "user_id, created_at DESC, post_id DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// dirty 컬럼만 UPDATE — visibility/caption 동시 수정이 서로를 stale 값으로 덮어쓰는 lost-update 차단.
@DynamicUpdate
public class FeedPost {

    public static final int CAPTION_MAX_LENGTH = 100;

    @Id
    @Column(name = "post_id", length = 50)
    private String postId;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private FeedVisibility visibility;

    @Column(name = "caption", length = CAPTION_MAX_LENGTH)
    private @Nullable String caption;

    @Column(name = "original_url", length = 500, nullable = false)
    private String originalUrl;

    @Column(name = "thumbnail_small_url", length = 500, nullable = false)
    private String thumbnailSmallUrl;

    @Column(name = "thumbnail_medium_url", length = 500, nullable = false)
    private String thumbnailMediumUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FeedPost(String postId, String userId, FeedVisibility visibility, @Nullable String caption,
                    String originalUrl, String thumbnailSmallUrl, String thumbnailMediumUrl) {
        this.postId = postId != null ? postId : IdGenerator.feedPostId();
        this.userId = userId;
        this.visibility = visibility;
        this.caption = caption;
        this.originalUrl = originalUrl;
        this.thumbnailSmallUrl = thumbnailSmallUrl;
        this.thumbnailMediumUrl = thumbnailMediumUrl;
    }

    public void changeVisibility(FeedVisibility visibility) {
        this.visibility = visibility;
    }

    public void changeCaption(@Nullable String caption) {
        this.caption = caption;
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
