package site.krip.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.dto.request.RegisterRequest;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.entity.UserTravelStyle;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.auth.repository.UserTravelStyleRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;

/** 2차 회원가입 — 상세 정보 + 여행 스타일 저장. 유저 미존재/중복 가입은 모두 409. */
@Service
@RequiredArgsConstructor
public class RegisterService {

    private final UserRepository userRepository;
    private final UserDetailInformRepository detailRepository;
    private final UserTravelStyleRepository styleRepository;

    @Transactional
    public void registerDetail(String userId, RegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("존재하지 않는 유저입니다."));

        // 이 경로는 RegisterCheckFilter 제외 대상 — INACTIVE(탈퇴 유예) 유저의 가입 완료를 직접 차단.
        if (user.getStatus() == UserStatus.INACTIVE) {
            throw new ApiException(ApiException.WITHDRAWAL_PENDING_STATUS,
                    "회원 탈퇴가 진행 중입니다. 먼저 탈퇴를 취소해 주세요.");
        }

        if (detailRepository.existsById(userId)) {
            throw ApiException.conflict("이미 2차 회원가입이 완료된 유저입니다.");
        }

        UserDetailInform detail = new UserDetailInform(
                user,
                request.email(),
                request.userName(),
                request.phoneNumber(),
                request.age(),
                request.gender(),
                request.nationality());
        detailRepository.save(detail);

        List<UserTravelStyle> styles = request.travelStyles().stream()
                .map(style -> new UserTravelStyle(user, style))
                .toList();
        styleRepository.saveAll(styles);
    }
}
