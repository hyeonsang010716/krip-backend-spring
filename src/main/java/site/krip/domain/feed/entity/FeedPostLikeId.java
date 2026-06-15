package site.krip.domain.feed.entity;

import java.io.Serializable;
import java.util.Objects;

/** {@link FeedPostLike} 복합 PK (user_id, post_id). */
public class FeedPostLikeId implements Serializable {

    private String userId;
    private String postId;

    public FeedPostLikeId() {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FeedPostLikeId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(postId, that.postId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, postId);
    }
}
