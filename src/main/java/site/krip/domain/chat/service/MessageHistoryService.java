package site.krip.domain.chat.service;

import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.chat.dto.response.ChatMessageResponse;
import site.krip.domain.chat.dto.response.ChatRoomListResponse;
import site.krip.domain.chat.dto.response.ChatRoomPeerResponse;
import site.krip.domain.chat.dto.response.ChatRoomResponse;
import site.krip.domain.chat.dto.response.LastMessagePreviewResponse;
import site.krip.domain.chat.dto.response.MessageHistoryResponse;
import site.krip.domain.chat.dto.response.RoomMemberListResponse;
import site.krip.domain.chat.dto.response.RoomMemberResponse;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomMemberId;
import site.krip.domain.chat.entity.ChatRoomType;
import site.krip.domain.chat.exception.ChatRoomNotFoundException;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.domain.chat.repository.RoomListRow;
import site.krip.domain.friend.port.FriendQueryPort;
import site.krip.global.common.exception.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 채팅 읽기 전용 — 방 리스트 / 방 상세 / 참여자 / 초대 가능 친구 / 메시지 히스토리.
 */
@Service
public class MessageHistoryService {

    private final ChatRoomRepository roomRepo;
    private final ChatRoomMemberRepository memberRepo;
    private final ChatMessageRepository messageRepo;
    private final UserRepository userRepo;
    private final FriendQueryPort friendQuery;
    private final UnreadService unreadService;

    public MessageHistoryService(ChatRoomRepository roomRepo, ChatRoomMemberRepository memberRepo,
                                 ChatMessageRepository messageRepo, UserRepository userRepo,
                                 FriendQueryPort friendQuery, UnreadService unreadService) {
        this.roomRepo = roomRepo;
        this.memberRepo = memberRepo;
        this.messageRepo = messageRepo;
        this.userRepo = userRepo;
        this.friendQuery = friendQuery;
        this.unreadService = unreadService;
    }

    @Transactional(readOnly = true)
    public ChatRoomListResponse listRooms(String meId) {
        List<RoomListRow> rows = roomRepo.findRoomsOfUser(meId, PageRequest.of(0, ChatRoomRepository.PAGE_SIZE));

        Set<String> peerIds = new LinkedHashSet<>();
        Set<String> messageIds = new LinkedHashSet<>();
        for (RoomListRow row : rows) {
            if (row.peerUserId() != null) {
                peerIds.add(row.peerUserId());
            }
            ChatRoom r = row.room();
            if (r.getLastMessageId() != null) {
                messageIds.add(r.getLastMessageId());
            }
        }
        Map<String, User> peerMap = userRepo.findByIdsWithProfile(peerIds).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a));
        Map<String, Integer> unreadMap = unreadCounts(meId);
        Map<String, Document> messagesById = messageRepo.findByIds(messageIds);

        List<ChatRoomResponse> items = new ArrayList<>();
        for (RoomListRow row : rows) {
            ChatRoom room = row.room();
            String peerUserId = row.peerUserId();
            Boolean mute = row.notificationMuted();
            Document lastDoc = room.getLastMessageId() != null
                    ? messagesById.get(room.getLastMessageId()) : null;
            items.add(roomToResponse(room, peerUserId,
                    peerUserId != null ? peerMap.get(peerUserId) : null,
                    unreadMap.getOrDefault(room.getChatRoomId(), 0),
                    lastDoc, Boolean.TRUE.equals(mute)));
        }
        return new ChatRoomListResponse(items, null);
    }

    @Transactional(readOnly = true)
    public ChatRoomResponse getRoom(String meId, String roomId) {
        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));
        ChatRoomMember member = memberRepo.findById(new ChatRoomMemberId(roomId, meId)).orElse(null);
        if (member == null || member.isLeft()) {
            throw ApiException.forbidden("이 방의 멤버가 아닙니다.");
        }

        String peerUserId = null;
        if (room.getType() == ChatRoomType.DIRECT) {
            peerUserId = meId.equals(room.getDirectUserAId())
                    ? room.getDirectUserBId() : room.getDirectUserAId();
        }
        User peerUser = peerUserId != null ? userRepo.findByIdWithProfile(peerUserId).orElse(null) : null;

        int unread = unreadService.countForRoom(meId, roomId);

        Document lastDoc = room.getLastMessageId() != null
                ? messageRepo.findById(room.getLastMessageId()) : null;

        return roomToResponse(room, peerUserId, peerUser, unread, lastDoc,
                Boolean.TRUE.equals(member.getNotificationMuted()));
    }

    @Transactional(readOnly = true)
    public RoomMemberListResponse listRoomMembers(String meId, String roomId) {
        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));
        if (!memberRepo.isActiveMember(roomId, meId)) {
            throw ApiException.forbidden("이 방의 멤버가 아닙니다.");
        }
        if (room.getType() != ChatRoomType.GROUP) {
            throw ApiException.badRequest("그룹 방의 참여자 목록만 조회할 수 있습니다.");
        }
        List<RoomMemberResponse> items = memberRepo.findActiveMemberUsers(roomId).stream()
                .map(RoomMemberResponse::from).toList();
        return new RoomMemberListResponse(items);
    }

    @Transactional(readOnly = true)
    public RoomMemberListResponse listInvitableFriends(String meId, String roomId) {
        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));
        if (!memberRepo.isActiveMember(roomId, meId)) {
            throw ApiException.forbidden("이 방의 멤버가 아닙니다.");
        }
        if (room.getType() != ChatRoomType.GROUP) {
            throw ApiException.badRequest("그룹 방에만 친구를 초대할 수 있습니다.");
        }
        Set<String> friendIds = new TreeSet<>(friendQuery.acceptedFriendIds(meId));
        if (friendIds.isEmpty()) {
            return new RoomMemberListResponse(List.of());
        }
        Set<String> activeIds = new java.util.HashSet<>(memberRepo.findActiveMemberIds(roomId));
        friendIds.removeAll(activeIds);
        if (friendIds.isEmpty()) {
            return new RoomMemberListResponse(List.of());
        }
        Map<String, User> usersMap = userRepo.findByIdsWithProfile(friendIds).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a));
        List<RoomMemberResponse> items = friendIds.stream()
                .filter(usersMap::containsKey)
                .map(uid -> RoomMemberResponse.from(usersMap.get(uid)))
                .toList();
        return new RoomMemberListResponse(items);
    }

    @Transactional(readOnly = true)
    public MessageHistoryResponse findMessagesBefore(String meId, String roomId, long beforeSeq, int limit) {
        assertRoomMember(roomId, meId);
        return toMessageList(messageRepo.findBefore(roomId, beforeSeq, limit), limit);
    }

    @Transactional(readOnly = true)
    public MessageHistoryResponse findMessagesAfter(String meId, String roomId, long afterSeq, int limit) {
        assertRoomMember(roomId, meId);
        return toMessageList(messageRepo.findAfter(roomId, afterSeq, limit), limit);
    }

    /** 유저 전체 활성 방의 unread (커서 파생, 캐시 miss 인 방만 재계산). */
    public Map<String, Integer> unreadCounts(String meId) {
        return unreadService.countsForUser(meId);
    }

    private void assertRoomMember(String roomId, String userId) {
        if (!memberRepo.isActiveMember(roomId, userId)) {
            throw ApiException.forbidden("이 방의 멤버가 아닙니다.");
        }
    }

    private static ChatRoomResponse roomToResponse(ChatRoom room, String peerUserId, User peerUser,
                                                   int unreadCount, Document lastMessageDoc, boolean muted) {
        ChatRoomPeerResponse peer = null;
        if (peerUserId != null) {
            if (peerUser == null) {
                peer = new ChatRoomPeerResponse(peerUserId, null, null);
            } else {
                UserDetailInform d = peerUser.getDetail();
                peer = new ChatRoomPeerResponse(peerUser.getUserId(),
                        d != null ? d.getUserName() : null, d != null ? d.getProfileImageUrl() : null);
            }
        } else if (room.getType() == ChatRoomType.DIRECT) {
            peer = new ChatRoomPeerResponse(null, null, null);
        }

        LastMessagePreviewResponse last = lastMessageDoc != null
                ? LastMessagePreviewResponse.fromDoc(lastMessageDoc) : null;

        return new ChatRoomResponse(room.getChatRoomId(), room.getType(), room.getTitle(), peer, last,
                unreadCount, room.getLastMessageAt(), room.effectiveLastAtOrCreated(), muted);
    }

    private static MessageHistoryResponse toMessageList(List<Document> raw, int limit) {
        boolean hasMore = raw.size() > limit;
        List<Document> items = hasMore ? raw.subList(0, limit) : raw;
        List<ChatMessageResponse> messages = items.stream().map(ChatMessageResponse::fromDoc).toList();
        Long nextCursor = (!messages.isEmpty() && hasMore) ? messages.get(messages.size() - 1).serverSeq() : null;
        return new MessageHistoryResponse(messages, hasMore, nextCursor);
    }
}
