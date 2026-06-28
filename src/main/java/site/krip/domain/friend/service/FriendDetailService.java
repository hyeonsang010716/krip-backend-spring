package site.krip.domain.friend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserTravelStyle;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.friend.dto.response.FriendDetailResponse;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.common.exception.ApiException;

/**
 * 상대 유저 공개 프로필 + viewer 기준 관계 상태. 민감 정보 제외.
 * 유저 미존재 → 404, 2차 미완료 → 400.
 */
@Service
@RequiredArgsConstructor
public class FriendDetailService {

    private final UserRepository userRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserBlockRepository userBlockRepository;

    @Transactional(readOnly = true)
    public FriendDetailResponse getFriendDetail(String viewerId, String peerId) {
        User peer = userRepository.findByIdWithProfile(peerId)
                .orElseThrow(() -> ApiException.notFound("존재하지 않는 유저입니다."));
        if (peer.getDetail() == null) {
            throw ApiException.badRequest("2차 회원가입이 완료되지 않은 유저입니다.");
        }

        // 차단 양방향성: 상대가 나를 차단했으면(peer→viewer) 존재 은닉(404) — search/feed/tripmate 와 일관.
        // 내가 상대를 차단한 경우(viewer→peer)는 "차단 해제" 동선을 위해 iBlocked=true 로 그대로 노출한다.
        boolean iBlocked = false;
        for (UserBlock b : userBlockRepository.findBlocksBetween(viewerId, peerId)) {
            if (b.getBlockerId().equals(viewerId)) {
                iBlocked = true;
            } else if (b.getBlockerId().equals(peerId)) {
                throw ApiException.notFound("존재하지 않는 유저입니다.");
            }
        }

        // REJECTED 는 재요청으로 되살아나는(reopenAsPending) 상태라 "관계 없음"으로 마스킹 — 거절 사실 누설·무효 id 노출 방지(search 와 일관).
        Friendship friendship = friendshipRepository.findBetween(viewerId, peerId)
                .filter(f -> f.getStatus() != FriendshipStatus.REJECTED)
                .orElse(null);

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
