package site.krip.domain.tripmate.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link TripmatePostLike} 복합 PK (user_id, post_id) — JPA {@code @IdClass}.
 */
// 필드는 JPA 가 채움(no-arg 생성자 후 주입) — NullAway 초기화 검사 제외.
@SuppressWarnings("NullAway.Init")
public class TripmatePostLikeId implements Serializable {

    private String userId;
    private String postId;

    public TripmatePostLikeId() {
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
