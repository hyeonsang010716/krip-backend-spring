package site.krip.domain.friend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.friend.dto.response.FriendDetailResponse;
import site.krip.domain.friend.service.FriendDetailService;
import site.krip.global.auth.CurrentUserId;

/**
 * 친구 상세(상대 프로필 + 내 기준 관계) 조회.
 * 경로: {@code /api/friend/detail}.
 */
@RestController
@RequestMapping("/api/friend/detail")
@RequiredArgsConstructor
public class FriendDetailController {

    private final FriendDetailService friendDetailService;

    @GetMapping("/{user_id}")
    public FriendDetailResponse getFriendDetail(@CurrentUserId String viewerId, @PathVariable("user_id") String userId) {
        return friendDetailService.getFriendDetail(viewerId, userId);
    }
}
