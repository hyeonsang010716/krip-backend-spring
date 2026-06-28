package site.krip.domain.auth.adapter;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.TravelStyle;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.domain.auth.repository.UserRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@link UserQueryPort} 어댑터 — auth 영속성으로부터 프로필 투영을 만든다. */
@Component
public class UserQueryAdapter implements UserQueryPort {

    private final UserRepository userRepository;

    public UserQueryAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserProfileView> findProfile(String userId) {
        // detail 만 필요하므로 travelStyles 까지 fetch 하는 withProfile 대신 withDetail 사용.
        // map 의 결과가 null(detail 없음)이면 Optional.empty — 미가입 유저는 제외된다.
        return userRepository.findByIdWithDetail(userId).map(UserQueryAdapter::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UserProfileView> findProfiles(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByIdsWithProfile(userIds).stream()
                .map(UserQueryAdapter::toView)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(UserProfileView::userId, Function.identity(), (a, b) -> a));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TravelStyle> findTravelStyles(String userId) {
        return userRepository.findTravelStyleValues(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveRegistered(String userId) {
        return userRepository.findByIdWithDetail(userId)
                .map(u -> u.getStatus() == UserStatus.ACTIVE && u.getDetail() != null)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> retainGloballyUnmuted(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userRepository.findUnmutedUserIds(userIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findUserIdsByNameLike(String likePattern, int limit) {
        return userRepository.findUserIdsByNameLike(likePattern, PageRequest.of(0, limit));
    }

    /** detail 없으면(2차 가입 미완료) null 반환 → 호출부에서 제외. */
    private static @Nullable UserProfileView toView(User user) {
        UserDetailInform d = user.getDetail();
        if (d == null) {
            return null;
        }
        return new UserProfileView(user.getUserId(), d.getUserName(), d.getProfileImageUrl(),
                d.getAge(), d.getGender(), d.getNationality());
    }
}
