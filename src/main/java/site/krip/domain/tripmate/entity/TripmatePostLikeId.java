package site.krip.domain.tripmate.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link TripmatePostLike} 복합 PK (user_id, post_id) — JPA {@code @IdClass}.
 */
public class TripmatePostLikeId implements Serializable {

    private String userId;
    private String postId;

    public TripmatePostLikeId() {
    }

    public TripmatePostLikeId(String userId, String postId) {
        this.userId = userId;
        this.postId = postId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TripmatePostLikeId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(postId, that.postId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, postId);
    }
}
