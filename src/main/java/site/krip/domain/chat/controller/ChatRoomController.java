package site.krip.domain.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.chat.dto.request.CreateDirectRoomBody;
import site.krip.domain.chat.dto.request.CreateGroupRoomBody;
import site.krip.domain.chat.dto.request.InviteMembersBody;
import site.krip.domain.chat.dto.request.KickMemberBody;
import site.krip.domain.chat.dto.response.ChatRoomListResponse;
import site.krip.domain.chat.dto.response.ChatRoomResponse;
import site.krip.domain.chat.dto.response.InviteMembersResponse;
import site.krip.domain.chat.dto.response.MessageHistoryResponse;
import site.krip.domain.chat.dto.response.RoomMemberListResponse;
import site.krip.domain.chat.service.MessageHistoryService;
import site.krip.domain.chat.service.RoomService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.exception.ApiException;

/** 채팅 방/메시지 REST — {@code /api/chat/rooms}. */
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final RoomService roomService;
    private final MessageHistoryService historyService;

    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatRoomResponse createDirect(@CurrentUserId String userId,
                                         @Valid @RequestBody CreateDirectRoomBody body) {
        return roomService.createDirectRoom(userId, body.peerUserId());
    }

    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatRoomResponse createGroup(@CurrentUserId String userId,
                                        @Valid @RequestBody CreateGroupRoomBody body) {
        return roomService.createGroupRoom(userId, body.title(), body.memberIds());
    }

    @PostMapping("/{chat_room_id}/invite")
    public InviteMembersResponse invite(@CurrentUserId String userId, @PathVariable("chat_room_id") String chatRoomId,
                                        @Valid @RequestBody InviteMembersBody body) {
        RoomService.InviteResult result = roomService.inviteMembers(userId, chatRoomId, body.userIds());
        return new InviteMembersResponse(result.invited(), result.skipped());
    }

    @PostMapping("/{chat_room_id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@CurrentUserId String userId, @PathVariable("chat_room_id") String chatRoomId) {
        roomService.leaveRoom(userId, chatRoomId);
    }

    @PostMapping("/{chat_room_id}/kick")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@CurrentUserId String userId, @PathVariable("chat_room_id") String chatRoomId,
                     @Valid @RequestBody KickMemberBody body) {
        roomService.kickMember(userId, chatRoomId, body.userId());
    }

    @GetMapping
    public ChatRoomListResponse listRooms(@CurrentUserId String userId) {
        return historyService.listRooms(userId);
    }

    @GetMapping("/{chat_room_id}")
    public ChatRoomResponse getRoom(@CurrentUserId String userId, @PathVariable("chat_room_id") String chatRoomId) {
        return historyService.getRoom(userId, chatRoomId);
    }

    @GetMapping("/{chat_room_id}/members")
    public RoomMemberListResponse listMembers(@CurrentUserId String userId, @PathVariable("chat_room_id") String chatRoomId) {
        return historyService.listRoomMembers(userId, chatRoomId);
    }

    @GetMapping("/{chat_room_id}/invitable-friends")
    public RoomMemberListResponse invitableFriends(@CurrentUserId String userId,
                                                   @PathVariable("chat_room_id") String chatRoomId) {
        return historyService.listInvitableFriends(userId, chatRoomId);
    }

    @GetMapping("/{chat_room_id}/messages")
    public MessageHistoryResponse getMessages(@CurrentUserId String userId, @PathVariable("chat_room_id") String chatRoomId,
                                              @RequestParam(name = "before_server_seq", required = false) Long beforeServerSeq,
                                              @RequestParam(name = "after_server_seq", required = false) Long afterServerSeq,
                                              @RequestParam(defaultValue = "50") int limit) {
        if ((beforeServerSeq == null) == (afterServerSeq == null)) {
            throw ApiException.badRequest("before_server_seq 또는 after_server_seq 중 하나만 지정해야 합니다.");
        }
        if (limit < 1 || limit > 200) {
            throw ApiException.badRequest("limit 은 1~200 범위여야 합니다.");
        }
        if (beforeServerSeq != null) {
            return historyService.findMessagesBefore(userId, chatRoomId, beforeServerSeq, limit);
        }
        return historyService.findMessagesAfter(userId, chatRoomId, afterServerSeq, limit);
    }
}
