package site.krip.domain.auth.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import site.krip.domain.auth.dto.SignupResult;
import site.krip.domain.auth.dto.SignupStatus;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.auth.repository.UserRepository;

/**
 * OAuth 콜백 후 가입 상태 판정 + 미가입 시 1차 가입.
 *
 * <pre>
 *   provider 조회: 없음 → NEW(신규 생성) / INACTIVE → WITHDRAWAL_PENDING(detail 무관)
 *   detail 존재  : 없음 → IN_PROGRESS / 있음 → COMPLETE
 * </pre>
 */
@Service
public class SignupService {

    private final UserRepository userRepository;
    private final UserDetailInformRepository detailRepository;

    public SignupService(UserRepository userRepository, UserDetailInformRepository detailRepository) {
        this.userRepository = userRepository;
        this.detailRepository = detailRepository;
    }

    public SignupResult checkAndRegister(OAuthProvider authProvider, String authProviderId) {
        User user = findUser(authProvider, authProviderId);

        if (user == null) {
            try {
                User created = userRepository.save(User.createNew(authProvider, authProviderId));
                return new SignupResult(created.getUserId(), SignupStatus.NEW);
            } catch (DataIntegrityViolationException e) {
                // 동시 콜백이 먼저 생성 — uq_provider_account 충돌 시 그 유저로 이어서 진행(멱등).
                user = findUser(authProvider, authProviderId);
                if (user == null) {
                    throw e;
                }
            }
        }

        if (user.getStatus() == UserStatus.INACTIVE) {
            return new SignupResult(user.getUserId(), SignupStatus.WITHDRAWAL_PENDING);
        }

        boolean hasDetail = detailRepository.existsById(user.getUserId());
        return new SignupResult(user.getUserId(),
                hasDetail ? SignupStatus.COMPLETE : SignupStatus.IN_PROGRESS);
    }

    private User findUser(OAuthProvider authProvider, String authProviderId) {
        return userRepository.findByAuthProviderAndAuthProviderId(authProvider, authProviderId)
                .orElse(null);
    }
}
