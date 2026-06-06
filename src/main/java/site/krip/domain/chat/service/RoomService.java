package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.chat.dto.response.ChatRoomPeerResponse;
import site.krip.domain.chat.dto.response.ChatRoomResponse;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomType;
import site.krip.domain.chat.exception.ChatRoomNotFoundException;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.common.exception.ApiException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 채팅방 생성/멤버십/읽음.
 *
 * <p>DIRECT 생성은 canonical 정렬(a&lt;b) + partial UNIQUE 로 idempotent — 경합은 새 트랜잭션 재조회.
 * 멤버 변경 순서: Redis(room:members SADD/SREM) → RDB → 구독(subscribe/unsubscribe) → 시스템 메시지.
 * (퇴장/강퇴는 SREM 을 먼저 해 in-flight 송신이 즉시 거절되게, 시스템 메시지 전에 구독 해제해 자기 에코 차단.)
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final ChatRoomRepository roomRepo;
    private final ChatRoomMemberRepository memberRepo;
    private final UserBlockRepository blockRepo;
    private final FriendshipRepository friendshipRepo;
    private final UserRepository userRepo;
    private final FanoutService fanout;
    private final MessageService messageService;
    private final site.krip.domain.chat.repository.ChatMessageRepository messageRepo;
    private final StringRedisTemplate redis;
    private final TransactionTemplate txTemplate;

    public RoomService(ChatRoomRepository roomRepo, ChatRoomMemberRepository memberRepo,
                       UserBlockRepository blockRepo, FriendshipRepository friendshipRepo,
                       UserRepository userRepo, FanoutService fanout, MessageService messageService,
                       site.krip.domain.chat.repository.ChatMessageRepository messageRepo,
                       StringRedisTemplate redis, TransactionTemplate txTemplate) {
        this.roomRepo = roomRepo;
        this.memberRepo = memberRepo;
        this.blockRepo = blockRepo;
        this.friendshipRepo = friendshipRepo;
        this.userRepo = userRepo;
        this.fanout = fanout;
        this.messageService = messageService;
        this.messageRepo = messageRepo;
        this.redis = redis;
        this.txTemplate = txTemplate;
    }

    // ──────────────────── 1:1 방 ────────────────────

    public ChatRoomResponse createDirectRoom(String meId, String peerUserId) {
        if (meId.equals(peerUserId)) {
            throw new ApiException(400, "자기 자신과의 방은 만들 수 없습니다.");
        }
        User peer = userRepo.findByIdWithProfile(peerUserId)
                .orElseThrow(() -> new ApiException(400, "존재하지 않는 유저입니다."));

        List<UserBlock> blocks = blockRepo.findBlocksBetween(meId, peerUserId);
        if (blocks.stream().anyMatch(b -> b.getBlockerId().equals(meId))) {
            throw new ApiException(400, "차단한 유저와는 방을 만들 수 없습니다. 먼저 차단을 해제해주세요.");
        }
        if (!blocks.isEmpty()) {
            throw new ApiException(400, "해당 유저와는 방을 만들 수 없습니다.");
        }

        String userA = meId.compareTo(peerUserId) < 0 ? meId : peerUserId;
        String userB = meId.compareTo(peerUserId) < 0 ? peerUserId : meId;

        ChatRoom existing = roomRepo.findDirectByPair(userA, userB).orElse(null);
        if (existing != null) {
            return toDirectDto(existing, peer);
        }

        ChatRoom created;
        try {
            created = txTemplate.execute(s -> {
                ChatRoom room = roomRepo.saveAndFlush(ChatRoom.direct(meId, userA, userB));
                memberRepo.saveAll(List.of(
                        new ChatRoomMember(room.getChatRoomId(), userA, null),
                        new ChatRoomMember(room.getChatRoomId(), userB, null)));
                return room;
            });
        } catch (DataIntegrityViolationException e) {
            ChatRoom raced = roomRepo.findDirectByPair(userA, userB).orElse(null);
            if (raced == null) {
                throw new ApiException(400, "방 생성 경합 실패. 잠시 후 다시 시도해주세요.");
            }
            return toDirectDto(raced, peer);
        }

        String roomId = created.getChatRoomId();
        redis.opsForSet().add(ChatRedisKeys.roomMembers(roomId), userA, userB);
        redis.expire(ChatRedisKeys.roomMembers(roomId), Duration.ofSeconds(ChatRedisKeys.ROOM_MEMBERS_TTL));

        // 구독을 fan-out 보다 먼저 — race 차단.
        fanout.subscribeUserToRoom(userA, roomId);
        fanout.subscribeUserToRoom(userB, roomId);
        fanout.fanOutToUser(userA, Map.of("type", "room_joined", "room_id", roomId));
        fanout.fanOutToUser(userB, Map.of("type", "room_joined", "room_id", roomId));

        log.info("1:1 방 생성 완료: room_id={}, a={}, b={}", roomId, userA, userB);
        return toDirectDto(created, peer);
    }

    @Transactional(readOnly = true)
    public List<String> listUserRoomIds(String userId) {
        return memberRepo.findUserRoomIds(userId);
    }

    // ──────────────────── 그룹 방 ────────────────────

    public ChatRoomResponse createGroupRoom(String meId, String title, List<String> memberIds) {
        Set<String> targets = new HashSet<>(memberIds);
        targets.remove(meId);
        if (targets.isEmpty()) {
            throw new ApiException(400, "초대할 대상이 없습니다 (본인 외 멤버 없음).");
        }
        Set<String> friendIds = new HashSet<>(friendshipRepo.findAcceptedFriendIdsWith(meId, targets));
        Set<String> nonFriends = new TreeSet<>(targets);
        nonFriends.removeAll(friendIds);
        if (!nonFriends.isEmpty()) {
            throw new ApiException(400, "친구가 아닌 유저는 초대할 수 없습니다: " + new ArrayList<>(nonFriends));
        }

        Set<String> allMemberIds = new TreeSet<>(targets);
        allMemberIds.add(meId);

        ChatRoom created = txTemplate.execute(s -> {
            ChatRoom room = roomRepo.saveAndFlush(ChatRoom.group(meId, title));
            List<ChatRoomMember> members = new ArrayList<>();
            for (String uid : allMemberIds) {
                members.add(new ChatRoomMember(room.getChatRoomId(), uid, null));
            }
            memberRepo.saveAll(members);
            return room;
        });

        String roomId = created.getChatRoomId();
        redis.opsForSet().add(ChatRedisKeys.roomMembers(roomId), allMemberIds.toArray(new String[0]));
        redis.expire(ChatRedisKeys.roomMembers(roomId), Duration.ofSeconds(ChatRedisKeys.ROOM_MEMBERS_TTL));
        for (String uid : allMemberIds) {
            redis.opsForHash().put(ChatRedisKeys.unread(uid), roomId, "0");
        }

        for (String uid : allMemberIds) {
            fanout.subscribeUserToRoom(uid, roomId);
        }
        for (String uid : allMemberIds) {
            fanout.fanOutToUser(uid, Map.of("type", "room_joined", "room_id", roomId));
        }

        messageService.sendSystemMessage(roomId, "created", meId);

        log.info("그룹 방 생성: room_id={}, creator={}, members={}", roomId, meId, allMemberIds);
        return toGroupDto(created);
    }

    public InviteResult inviteMembers(String meId, String roomId, List<String> userIds) {
        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));
        if (room.getType() != ChatRoomType.GROUP) {
            throw new ApiException(400, "그룹 방에만 멤버를 초대할 수 있습니다.");
        }
        if (!memberRepo.isActiveMember(roomId, meId)) {
            throw new ApiException(403, "이 방의 활성 멤버만 초대할 수 있습니다.");
        }
        Set<String> targets = new HashSet<>(userIds);
        targets.remove(meId);
        if (targets.isEmpty()) {
            throw new ApiException(400, "초대할 대상이 없습니다.");
        }
        Set<String> friendIds = new HashSet<>(friendshipRepo.findAcceptedFriendIdsWith(meId, targets));
        Set<String> nonFriends = new TreeSet<>(targets);
        nonFriends.removeAll(friendIds);
        if (!nonFriends.isEmpty()) {
            throw new ApiException(400, "친구가 아닌 유저는 초대할 수 없습니다: " + new ArrayList<>(nonFriends));
        }

        long currentSeq = getCurrentSeq(roomId);

        List<String> invited = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> newMembers = new ArrayList<>();
        Map<String, Long> rejoined = new java.util.LinkedHashMap<>(); // uid -> last_read

        txTemplate.executeWithoutResult(s -> {
            for (String uid : new TreeSet<>(targets)) {
                ChatRoomMember existing = memberRepo.findById(
                        new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, uid)).orElse(null);
                if (existing != null && !existing.isLeft()) {
                    skipped.add(uid);
                    continue;
                }
                if (existing != null && existing.isLeft()) {
                    existing.rejoin();
                    memberRepo.save(existing);
                    rejoined.put(uid, existing.getLastReadMessageServerSeq() != null
                            ? existing.getLastReadMessageServerSeq() : 0L);
                    invited.add(uid);
                } else {
                    memberRepo.save(new ChatRoomMember(roomId, uid, currentSeq > 0 ? currentSeq : null));
                    newMembers.add(uid);
                    invited.add(uid);
                }
            }
        });

        if (invited.isEmpty()) {
            return new InviteResult(List.of(), skipped);
        }

        redis.opsForSet().add(ChatRedisKeys.roomMembers(roomId), invited.toArray(new String[0]));
        redis.expire(ChatRedisKeys.roomMembers(roomId), Duration.ofSeconds(ChatRedisKeys.ROOM_MEMBERS_TTL));
        for (var e : rejoined.entrySet()) {
            long unread = Math.max(0, currentSeq - e.getValue());
            redis.opsForHash().put(ChatRedisKeys.unread(e.getKey()), roomId, String.valueOf(unread));
        }
        for (String uid : newMembers) {
            redis.opsForHash().put(ChatRedisKeys.unread(uid), roomId, "0");
        }

        for (String uid : invited) {
            fanout.subscribeUserToRoom(uid, roomId);
        }
        for (String uid : invited) {
            fanout.fanOutToUser(uid, Map.of("type", "room_joined", "room_id", roomId));
        }

        messageService.sendSystemMessage(roomId, "join", meId, invited);

        log.info("멤버 초대: room_id={}, inviter={}, invited={}, skipped={}", roomId, meId, invited, skipped);
        return new InviteResult(invited, skipped);
    }

    public void leaveRoom(String meId, String roomId) {
        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));
        if (room.getType() != ChatRoomType.GROUP) {
            throw new ApiException(400, "그룹 방만 퇴장할 수 있습니다.");
        }
        ChatRoomMember member = memberRepo.findById(
                new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, meId)).orElse(null);
        if (member == null || member.isLeft()) {
            throw new ApiException(403, "이 방의 활성 멤버가 아닙니다.");
        }

        redis.opsForSet().remove(ChatRedisKeys.roomMembers(roomId), meId);
        redis.opsForHash().delete(ChatRedisKeys.unread(meId), roomId);

        txTemplate.executeWithoutResult(s -> {
            ChatRoomMember m = memberRepo.findById(
                    new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, meId))
                    .orElseThrow(() -> new ApiException(403, "이 방의 활성 멤버가 아닙니다."));
            m.markLeft();
            memberRepo.save(m);
        });

        fanout.fanOutToUser(meId, Map.of("type", "room_left", "room_id", roomId));
        // 시스템 메시지 이전에 구독 해제 — leaver 가 자기 "방 나감" 메시지를 받지 않도록.
        fanout.unsubscribeUserFromRoom(meId, roomId);
        messageService.sendSystemMessage(roomId, "leave", meId);

        log.info("그룹 방 퇴장: room_id={}, user_id={}", roomId, meId);
    }

    public void kickMember(String meId, String roomId, String targetUserId) {
        if (meId.equals(targetUserId)) {
            throw new ApiException(400, "자기 자신은 강퇴할 수 없습니다. 퇴장 API 를 사용하세요.");
        }
        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));
        if (room.getType() != ChatRoomType.GROUP) {
            throw new ApiException(400, "그룹 방에서만 강퇴할 수 있습니다.");
        }
        if (!meId.equals(room.getCreatorId())) {
            throw new ApiException(403, "방장만 강퇴할 수 있습니다.");
        }
        if (!memberRepo.isActiveMember(roomId, meId)) {
            throw new ApiException(403, "방장이 이미 방을 떠난 상태입니다.");
        }
        ChatRoomMember target = memberRepo.findById(
                new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, targetUserId)).orElse(null);
        if (target == null || target.isLeft()) {
            throw new ApiException(400, "강퇴 대상이 활성 멤버가 아닙니다.");
        }

        redis.opsForSet().remove(ChatRedisKeys.roomMembers(roomId), targetUserId);
        redis.opsForHash().delete(ChatRedisKeys.unread(targetUserId), roomId);

        txTemplate.executeWithoutResult(s -> {
            ChatRoomMember m = memberRepo.findById(
                    new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, targetUserId))
                    .orElseThrow(() -> new ApiException(400, "강퇴 대상이 활성 멤버가 아닙니다."));
            m.markLeft();
            memberRepo.save(m);
        });

        fanout.fanOutToUser(targetUserId, Map.of("type", "room_left", "room_id", roomId));
        fanout.unsubscribeUserFromRoom(targetUserId, roomId);
        messageService.sendSystemMessage(roomId, "kick", meId, List.of(targetUserId));

        log.info("멤버 강퇴: room_id={}, kicker={}, target={}", roomId, meId, targetUserId);
    }

    // ──────────────────── 읽음 ────────────────────

    public long markRead(String meId, String meSessionId, String roomId, long upToServerSeq) {
        if (upToServerSeq <= 0) {
            throw new ApiException(400, "up_to_server_seq 는 1 이상이어야 합니다.");
        }
        roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));

        Long finalSeq = txTemplate.execute(s -> {
            int updated = memberRepo.markRead(roomId, meId, upToServerSeq);
            if (updated == 0) {
                return null;
            }
            return memberRepo.findLastReadSeq(roomId, meId).orElse(upToServerSeq);
        });
        if (finalSeq == null) {
            throw new ApiException(403, "이 방의 활성 멤버가 아닙니다.");
        }

        redis.opsForHash().put(ChatRedisKeys.unread(meId), roomId, "0");

        fanout.fanOutToSession(meSessionId, Map.of(
                "type", "read_ack", "room_id", roomId, "up_to_server_seq", finalSeq));
        fanout.fanOutToRoom(roomId, Map.of(
                "type", "read", "user_id", meId, "sender_session_id", meSessionId,
                "up_to_server_seq", finalSeq));

        log.info("읽음 마킹: room_id={}, user_id={}, up_to_seq={}, final_seq={}",
                roomId, meId, upToServerSeq, finalSeq);
        return finalSeq;
    }

    // ──────────────────── 헬퍼 ────────────────────

    private long getCurrentSeq(String roomId) {
        String raw = redis.opsForValue().get(ChatRedisKeys.roomSeq(roomId));
        if (raw != null) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                // fall through to Mongo
            }
        }
        return messageRepo.getMaxServerSeq(roomId);
    }

    private ChatRoomResponse toDirectDto(ChatRoom room, User peer) {
        var detail = peer.getDetail();
        ChatRoomPeerResponse peerResp = new ChatRoomPeerResponse(
                peer.getUserId(),
                detail != null ? detail.getUserName() : null,
                detail != null ? detail.getProfileImageUrl() : null);
        return new ChatRoomResponse(room.getChatRoomId(), room.getType(), room.getTitle(), peerResp,
                null, 0, room.getLastMessageAt(), room.effectiveLastAtOrCreated(), false);
    }

    private ChatRoomResponse toGroupDto(ChatRoom room) {
        return new ChatRoomResponse(room.getChatRoomId(), room.getType(), room.getTitle(), null,
                null, 0, room.getLastMessageAt(), room.effectiveLastAtOrCreated(), false);
    }

    /** invite 결과 (초대된 id, 이미 멤버라 skip 된 id). */
    public record InviteResult(List<String> invited, List<String> skipped) {
    }
}
