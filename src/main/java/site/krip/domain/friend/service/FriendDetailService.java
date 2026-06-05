package site.krip.domain.friend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserTravelStyle;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.friend.dto.response.FriendDetailResponse;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.common.exception.ApiException;

/**
 * 상대 유저 공개 프로필 + viewer 기준 관계 상태. 민감 정보 제외.
 * 유저 미존재 → 404, 2차 미완료 → 400.
 */
@Service
public class FriendDetailService {

    private final UserRepository userRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserBlockRepository userBlockRepository;

    public FriendDetailService(UserRepository userRepository,
                               FriendshipRepository friendshipRepository,
                               UserBlockRepository userBlockRepository) {
        this.userRepository = userRepository;
        this.friendshipRepository = friendshipRepository;
        this.userBlockRepository = userBlockRepository;
    }

    @Transactional(readOnly = true)
    public FriendDetailResponse getFriendDetail(String viewerId, String peerId) {
        User peer = userRepository.findByIdWithProfile(peerId)
                .orElseThrow(() -> new ApiException(404, "존재하지 않는 유저입니다."));
        if (peer.getDetail() == null) {
            throw new ApiException(400, "2차 회원가입이 완료되지 않은 유저입니다.");
        }

        Friendship friendship = friendshipRepository.findBetween(viewerId, peerId).orElse(null);
        boolean iBlocked = userBlockRepository.existsByBlockerIdAndBlockedId(viewerId, peerId);

        String friendshipId = friendship != null ? friendship.getFriendshipId() : null;
        FriendshipStatus status = friendship != null ? friendship.getStatus() : null;
        Boolean isRequester = friendship != null ? friendship.getRequesterId().equals(viewerId) : null;

        var d = peer.getDetail();
        return new FriendDetailResponse(
                peer.getUserId(), d.getUserName(), d.getAge(), d.getGender(), d.getNationality(),
                peer.getTravelStyles().stream().map(UserTravelStyle::getStyle).toList(),
                friendshipId, status, isRequester, iBlocked, d.getProfileImageUrl());
    }
}
