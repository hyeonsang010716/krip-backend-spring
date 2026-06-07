package site.krip.domain.auth.port;

import site.krip.domain.auth.entity.Gender;

/**
 * auth 외부 도메인에 노출하는 유저 프로필 투영.
 *
 * <p>타 도메인이 {@code User}/{@code UserDetailInform} 엔티티와 {@code UserRepository} 에 직접 의존하지
 * 않도록 detail 표시 필드를 평면 record 로 전달한다. 2차 가입 미완료(detail 없음) 유저는 투영되지 않는다.
 */
public record UserProfileView(
        String userId,
        String userName,
        String profileImageUrl,
        int age,
        Gender gender,
        String nationality
) {
}
