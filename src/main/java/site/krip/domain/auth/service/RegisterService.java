package site.krip.domain.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.dto.request.RegisterRequest;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.entity.UserTravelStyle;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.auth.repository.UserTravelStyleRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;

/** 2차 회원가입 — 상세 정보 + 여행 스타일 저장. 유저 미존재/중복 가입은 모두 409. */
@Service
public class RegisterService {

    private final UserRepository userRepository;
    private final UserDetailInformRepository detailRepository;
    private final UserTravelStyleRepository styleRepository;

    public RegisterService(UserRepository userRepository,
                           UserDetailInformRepository detailRepository,
                           UserTravelStyleRepository styleRepository) {
        this.userRepository = userRepository;
        this.detailRepository = detailRepository;
        this.styleRepository = styleRepository;
    }

    @Transactional
    public void registerDetail(String userId, RegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(409, "존재하지 않는 유저입니다."));

        if (detailRepository.existsById(userId)) {
            throw new ApiException(409, "이미 2차 회원가입이 완료된 유저입니다.");
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
