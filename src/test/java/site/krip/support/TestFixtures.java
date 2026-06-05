package site.krip.support;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.auth.repository.UserRepository;

import java.util.UUID;

/**
 * 통합 테스트용 데이터 시드 — 실제 트랜잭션으로 커밋(MockMvc 요청과 가시성 공유).
 *
 * <p>{@code @Transactional} 메서드를 별도 빈으로 두어 self-invocation 프록시 함정을 피한다.
 */
@TestComponent
public class TestFixtures {

    private final UserRepository userRepository;
    private final UserDetailInformRepository detailRepository;

    public TestFixtures(UserRepository userRepository, UserDetailInformRepository detailRepository) {
        this.userRepository = userRepository;
        this.detailRepository = detailRepository;
    }

    /** 2차 가입까지 완료한 ACTIVE 유저를 생성하고 user_id 반환 (모든 인증 필터 자연 통과). */
    @Transactional
    public String createActiveUser() {
        return createActiveUser("tester");
    }

    /**
     * 1차 가입만 끝난(상세정보 미등록) 유저 생성 — {@code detail == null}.
     * {@code POST /api/auth/register} 등 2차 가입 경로 테스트에 사용. (RegisterCheckFilter 제외 경로에서만 접근 가능)
     */
    @Transactional
    public String createPreRegisterUser() {
        User user = userRepository.save(User.createNew(OAuthProvider.GOOGLE, "prov-" + UUID.randomUUID()));
        return user.getUserId();
    }

    /** 표시 이름을 지정해 ACTIVE + detail 유저 생성. */
    @Transactional
    public String createActiveUser(String userName) {
        // assigned-id 라 save() 는 merge 로 동작 → 반환된 managed 인스턴스를 써야 detail 연결이 일관(원본은 detached).
        User user = userRepository.save(User.createNew(OAuthProvider.GOOGLE, "prov-" + UUID.randomUUID()));
        String uid = user.getUserId();
        UserDetailInform detail = new UserDetailInform(
                user, uid + "@test.local", userName, null, 25, Gender.MALE, "KR");
        detailRepository.save(detail);
        return uid;
    }
}
