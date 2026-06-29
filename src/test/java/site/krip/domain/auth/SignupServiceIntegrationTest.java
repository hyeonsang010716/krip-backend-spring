package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.dto.SignupResult;
import site.krip.domain.auth.dto.SignupStatus;
import site.krip.domain.auth.entity.Gender;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.auth.service.SignupService;
import site.krip.support.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 콜백 후 가입 상태 판정 — {@link SignupService#checkAndRegister} 4분기 직접 검증(해피패스는 Google 의존이라 E2E 불가).
 * 분기: 신규→NEW / INACTIVE→WITHDRAWAL_PENDING / detail 없음→IN_PROGRESS / detail 있음→COMPLETE.
 */
@Transactional
class SignupServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private SignupService signupService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserDetailInformRepository detailRepository;

    private String uniqueSub() {
        return "sub-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("신규 provider id → NEW, 유저 생성")
    void newUserCreated() {
        String sub = uniqueSub();

        SignupResult result = signupService.checkAndRegister(OAuthProvider.GOOGLE, sub);

        assertThat(result.status()).isEqualTo(SignupStatus.NEW);
        assertThat(result.userId()).isNotBlank();
        assertThat(userRepository.findByAuthProviderAndAuthProviderId(OAuthProvider.GOOGLE, sub))
                .isPresent();
    }

    @Test
    @DisplayName("기존 유저 + detail 있음 → COMPLETE")
    void existingWithDetailComplete() {
        String sub = uniqueSub();
        User user = userRepository.save(User.createNew(OAuthProvider.GOOGLE, sub));
        detailRepository.save(new UserDetailInform(
                user, user.getUserId() + "@test.local", "완료유저", null, 25, Gender.MALE, "KR"));

        SignupResult result = signupService.checkAndRegister(OAuthProvider.GOOGLE, sub);

        assertThat(result.status()).isEqualTo(SignupStatus.COMPLETE);
        assertThat(result.userId()).isEqualTo(user.getUserId());
    }

    @Test
    @DisplayName("기존 유저 + detail 없음(1차만) → IN_PROGRESS")
    void existingWithoutDetailInProgress() {
        String sub = uniqueSub();
        User user = userRepository.save(User.createNew(OAuthProvider.GOOGLE, sub));

        SignupResult result = signupService.checkAndRegister(OAuthProvider.GOOGLE, sub);

        assertThat(result.status()).isEqualTo(SignupStatus.IN_PROGRESS);
        assertThat(result.userId()).isEqualTo(user.getUserId());
    }

    @Test
    @DisplayName("탈퇴 유예(INACTIVE) 유저 → WITHDRAWAL_PENDING (detail 무관)")
    void inactiveUserWithdrawalPending() {
        String sub = uniqueSub();
        User user = User.createNew(OAuthProvider.GOOGLE, sub);
        user.changeStatus(UserStatus.INACTIVE);
        userRepository.save(user);

        SignupResult result = signupService.checkAndRegister(OAuthProvider.GOOGLE, sub);

        assertThat(result.status()).isEqualTo(SignupStatus.WITHDRAWAL_PENDING);
        assertThat(result.userId()).isEqualTo(user.getUserId());
    }
}
