package site.krip.domain.friend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.friend.dto.request.BlockUserBody;
import site.krip.domain.friend.dto.response.UserBlockListResponse;
import site.krip.domain.friend.dto.response.UserBlockResponse;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 유저 차단/해제/목록.
 * 경로: {@code /api/friend/blocks}.
 */
@RestController
@RequestMapping("/api/friend/blocks")
@RequiredArgsConstructor
public class UserBlockController {

    private final UserBlockService userBlockService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserBlockResponse blockUser(@CurrentUserId String userId,
                                       @Valid @RequestBody BlockUserBody body) {
        return userBlockService.blockUser(userId, body.targetUserId());
    }

    @DeleteMapping("/{target_user_id}")
    public MessageResponse unblockUser(@CurrentUserId String userId, @PathVariable("target_user_id") String targetUserId) {
        userBlockService.unblockUser(userId, targetUserId);
        return new MessageResponse("차단을 해제했습니다.");
    }

    @GetMapping
    public UserBlockListResponse getBlockedUsers(@CurrentUserId String userId,
                                                 @RequestParam(value = "cursor", required = false) String cursor) {
        return userBlockService.getBlockedUsers(userId, cursor);
    }
}
