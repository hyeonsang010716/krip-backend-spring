package site.krip.domain.auth.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.notification.port.GlobalMutePort;
import site.krip.global.common.exception.ApiException;

/**
 * notification 의 {@link GlobalMutePort} 구현 — auth 도메인이 User 전역 알림 차단을 적용.
 */
@Component
@RequiredArgsConstructor
public class GlobalMuteAdapter implements GlobalMutePort {

    private final UserRepository userRepo;

    @Override
    @Transactional
    public void setGlobalMute(String userId, boolean muted) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 유저입니다."));
        user.applyNotificationMute(muted);
    }
}
