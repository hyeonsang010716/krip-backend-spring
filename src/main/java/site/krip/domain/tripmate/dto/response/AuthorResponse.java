package site.krip.domain.tripmate.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.port.UserProfileView;

/**
 * 게시글 작성자 프로필.
 */
public record AuthorResponse(
        String userName,
        int age,
        Gender gender,
        String nationality
) {
    /** detail 결손(2차 미완료 등) 시 익명 대체값. */
    public static AuthorResponse anonymous() {
        return new AuthorResponse("anonymous", 0, Gender.MALE, "");
    }

    /** JPA 연관관계(post.getUser().getDetail())로 얻은 detail 용. */
    public static AuthorResponse from(@Nullable UserDetailInform detail) {
        if (detail == null) {
            return anonymous();
        }
        return new AuthorResponse(detail.getUserName(), detail.getAge(), detail.getGender(),
                detail.getNationality());
    }

    public static AuthorResponse from(@Nullable UserProfileView view) {
        if (view == null) {
            return anonymous();
        }
        return new AuthorResponse(view.userName(), view.age(), view.gender(), view.nationality());
    }
}
