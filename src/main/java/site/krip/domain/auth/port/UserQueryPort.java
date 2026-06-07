package site.krip.domain.auth.port;

import site.krip.domain.auth.entity.TravelStyle;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * auth 유저 집합의 읽기 포트.
 *
 * <p>타 도메인(chat/feed/tripmate/notification 등)이 {@code UserRepository}/{@code User} 에 직접
 * 의존하지 않도록 프로필 투영·존재/상태 질의만 노출한다. 프로필이 없는(2차 가입 미완료) 유저는
 * 존재하지 않는 것으로 간주해 결과에서 제외한다.
 */
public interface UserQueryPort {

    Optional<UserProfileView> findProfile(String userId);

    /** 배치 — userId → 프로필. 프로필 없는 유저는 맵에서 제외된다(N+1 회피). */
    Map<String, UserProfileView> findProfiles(Collection<String> userIds);

    List<TravelStyle> findTravelStyles(String userId);

    /** ACTIVE + 2차 가입 완료 여부 (WS 핸드셰이크 게이트). */
    boolean isActiveRegistered(String userId);

    /** 주어진 유저 중 전역 알림 미차단(NULL/false)만 (FCM 푸시 게이팅). */
    List<String> retainGloballyUnmuted(Collection<String> userIds);
}
