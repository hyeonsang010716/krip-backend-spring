package site.krip.domain.friend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.friend.dto.request.SendFriendRequestBody;
import site.krip.domain.friend.dto.response.FriendshipListResponse;
import site.krip.domain.friend.dto.response.FriendshipResponse;
import site.krip.domain.friend.service.FriendshipService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 친구 요청/수락/거절/취소/목록/삭제.
 * 경로: {@code /api/friend/friendships}.
 */
@RestController
@RequestMapping("/api/friend/friendships")
public class FriendshipController {

    private final FriendshipService friendshipService;

    public FriendshipController(FriendshipService friendshipService) {
        this.friendshipService = friendshipService;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public FriendshipResponse sendRequest(@CurrentUserId String userId,
                                          @Valid @RequestBody SendFriendRequestBody body) {
        return friendshipService.sendRequest(userId, body.addresseeId());
    }

    @GetMapping("/requests/received")
    public FriendshipListResponse getReceivedRequests(@CurrentUserId String userId,
                                                      @RequestParam(value = "cursor", required = false) String cursor) {
        return friendshipService.getReceivedRequests(userId, cursor);
    }

    @GetMapping("/requests/sent")
    public FriendshipListResponse getSentRequests(@CurrentUserId String userId,
                                                  @RequestParam(value = "cursor", required = false) String cursor) {
        return friendshipService.getSentRequests(userId, cursor);
    }

    @PatchMapping("/requests/{friendship_id}/accept")
    public MessageResponse acceptRequest(@CurrentUserId String userId, @PathVariable("friendship_id") String friendshipId) {
        friendshipService.acceptRequest(friendshipId, userId);
        return new MessageResponse("친구 요청을 수락했습니다.");
    }

    @PatchMapping("/requests/{friendship_id}/reject")
    public MessageResponse rejectRequest(@CurrentUserId String userId, @PathVariable("friendship_id") String friendshipId) {
        friendshipService.rejectRequest(friendshipId, userId);
        return new MessageResponse("친구 요청을 거절했습니다.");
    }

    @DeleteMapping("/requests/{friendship_id}")
    public MessageResponse cancelRequest(@CurrentUserId String userId, @PathVariable("friendship_id") String friendshipId) {
        friendshipService.cancelRequest(friendshipId, userId);
        return new MessageResponse("친구 요청을 취소했습니다.");
    }

    @GetMapping
    public FriendshipListResponse getFriends(@CurrentUserId String userId,
                                             @RequestParam(value = "cursor", required = false) String cursor) {
        return friendshipService.getFriends(userId, cursor);
    }

    @DeleteMapping("/{friendship_id}")
    public MessageResponse removeFriend(@CurrentUserId String userId, @PathVariable("friendship_id") String friendshipId) {
        friendshipService.removeFriend(friendshipId, userId);
        return new MessageResponse("친구를 삭제했습니다.");
    }
}
