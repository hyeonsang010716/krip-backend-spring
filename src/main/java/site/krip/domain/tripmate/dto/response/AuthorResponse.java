package site.krip.domain.tripmate.dto.response;

import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.UserDetailInform;

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

    public static AuthorResponse from(UserDetailInform detail) {
        if (detail == null) {
            return anonymous();
        }
        return new AuthorResponse(detail.getUserName(), detail.getAge(), detail.getGender(),
                detail.getNationality());
    }
}
