package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.domain.chat.dto.response.ChatRoomPeerResponse;
import site.krip.domain.chat.dto.response.ChatRoomResponse;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomType;
import site.krip.domain.chat.exception.ChatRoomNotFoundException;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.domain.friend.port.FriendQueryPort;
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
 * 퇴장/강퇴는 RDB 커밋 후 Redis(room:members SREM) 정리 — tx 롤백 시 멤버십 불일치 방지.
 * 구독 해제는 시스템 메시지 이전에 수행해 leaver 가 자기 에코를 받지 않게 한다.
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final ChatRoomRepository roomRepo;
    private final ChatRoomMemberRepository memberRepo;
    private final FriendQueryPort friendQuery;
    private final UserQueryPort userQuery;
    private final FanoutService fanout;
    private final MessageService messageService;
    private final UnreadService unreadService;
    private final site.krip.domain.chat.repository.ChatMessageRepository messageRepo;
    private final StringRedisTemplate redis;
    private final TransactionTemplate txTemplate;

    public RoomService(ChatRoomRepository roomRepo, ChatRoomMemberRepository memberRepo,
                       FriendQueryPort friendQuery,
                       UserQueryPort userQuery, FanoutService fanout, MessageService messageService,
                       UnreadService unreadService,
                       site.krip.domain.chat.repository.ChatMessageRepository messageRepo,
                       StringRedisTemplate redis, TransactionTemplate txTemplate) {
        this.roomRepo = roomRepo;
        this.memberRepo = memberRepo;
        this.friendQuery = friendQuery;
        this.userQuery = userQuery;
        this.fanout = fanout;
        this.messageService = messageService;
        this.unreadService = unreadService;
        this.messageRepo = messageRepo;
        this.redis = redis;
        this.txTemplate = txTemplate;
    }

    // ──────────────────── 1:1 방 ────────────────────

    public ChatRoomResponse createDirectRoom(String meId, String peerUserId) {
        if (meId.equals(peerUserId)) {
            throw ApiException.badRequest("자기 자신과의 방은 만들 수 없습니다.");
        }
        UserProfileView peer = userQuery.findProfile(peerUserId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 유저입니다."));

        List<FriendQueryPort.BlockPair> blocks = friendQuery.findBlocksBetween(meId, peerUserId);
        if (blocks.stream().anyMatch(b -> b.blockerId().equals(meId))) {
            throw ApiException.badRequest("차단한 유저와는 방을 만들 수 없습니다. 먼저 차단을 해제해주세요.");
        }
        if (!blocks.isEmpty()) {
            throw ApiException.badRequest("해당 유저와는 방을 만들 수 없습니다.");
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
            ChatRoom raced = roomRepo.findDirectByPair(userA, userB)
                    .orElseThrow(() -> ApiException.badRequest("방 생성 경합 실패. 잠시 후 다시 시도해주세요."));
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
            throw ApiException.badRequest("초대할 대상이 없습니다 (본인 외 멤버 없음).");
        }
        Set<String> friendIds = new HashSet<>(friendQuery.acceptedFriendIdsAmong(meId, targets));
        Set<String> nonFriends = new TreeSet<>(targets);
        nonFriends.removeAll(friendIds);
        if (!nonFriends.isEmpty()) {
            throw ApiException.badRequest("친구가 아닌 유저는 초대할 수 없습니다: " + new ArrayList<>(nonFriends));
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
            unreadService.resetToZero(uid, roomId);
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
            throw ApiException.badRequest("그룹 방에만 멤버를 초대할 수 있습니다.");
        }
        if (!memberRepo.isActiveMember(roomId, meId)) {
            throw ApiException.forbidden("이 방의 활성 멤버만 초대할 수 있습니다.");
        }
        Set<String> targets = new HashSet<>(userIds);
        targets.remove(meId);
        if (targets.isEmpty()) {
            throw ApiException.badRequest("초대할 대상이 없습니다.");
        }
        Set<String> friendIds = new HashSet<>(friendQuery.acceptedFriendIdsAmong(meId, targets));
        Set<String> nonFriends = new TreeSet<>(targets);
        nonFriends.removeAll(friendIds);
        if (!nonFriends.isEmpty()) {
            throw ApiException.badRequest("친구가 아닌 유저는 초대할 수 없습니다: " + new ArrayList<>(nonFriends));
        }

        long currentSeq = getCurrentSeq(roomId);

        List<String> invited = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> newMembers = new ArrayList<>();
        List<String> rejoined = new ArrayList<>();

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
                    rejoined.add(uid);
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
        // 재입장은 보존된 last_read 로 다음 읽기에 재계산(gap-safe), 신규는 0.
        for (String uid : rejoined) {
            unreadService.clear(uid, roomId);
        }
        for (String uid : newMembers) {
            unreadService.resetToZero(uid, roomId);
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
            throw ApiException.badRequest("그룹 방만 퇴장할 수 있습니다.");
        }
        ChatRoomMember member = memberRepo.findById(
                new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, meId)).orElse(null);
        if (member == null || member.isLeft()) {
            throw ApiException.forbidden("이 방의 활성 멤버가 아닙니다.");
        }

        txTemplate.executeWithoutResult(s -> {
            ChatRoomMember m = memberRepo.findById(
                    new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, meId))
                    .orElseThrow(() -> ApiException.forbidden("이 방의 활성 멤버가 아닙니다."));
            m.markLeft();
            memberRepo.save(m);
        });

        // 커밋 성공 후에만 Redis 정리 — tx 롤백 시 캐시가 RDB 보다 앞서지 않게.
        redis.opsForSet().remove(ChatRedisKeys.roomMembers(roomId), meId);
        unreadService.clear(meId, roomId);

        fanout.fanOutToUser(meId, Map.of("type", "room_left", "room_id", roomId));
        // 시스템 메시지 이전에 구독 해제 — leaver 가 자기 "방 나감" 메시지를 받지 않도록.
        fanout.unsubscribeUserFromRoom(meId, roomId);
        messageService.sendSystemMessage(roomId, "leave", meId);

        log.info("그룹 방 퇴장: room_id={}, user_id={}", roomId, meId);
    }

    public void kickMember(String meId, String roomId, String targetUserId) {
        if (meId.equals(targetUserId)) {
            throw ApiException.badRequest("자기 자신은 강퇴할 수 없습니다. 퇴장 API 를 사용하세요.");
        }
        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));
        if (room.getType() != ChatRoomType.GROUP) {
            throw ApiException.badRequest("그룹 방에서만 강퇴할 수 있습니다.");
        }
        if (!meId.equals(room.getCreatorId())) {
            throw ApiException.forbidden("방장만 강퇴할 수 있습니다.");
        }
        if (!memberRepo.isActiveMember(roomId, meId)) {
            throw ApiException.forbidden("방장이 이미 방을 떠난 상태입니다.");
        }
        ChatRoomMember target = memberRepo.findById(
                new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, targetUserId)).orElse(null);
        if (target == null || target.isLeft()) {
            throw ApiException.badRequest("강퇴 대상이 활성 멤버가 아닙니다.");
        }

        txTemplate.executeWithoutResult(s -> {
            ChatRoomMember m = memberRepo.findById(
                    new site.krip.domain.chat.entity.ChatRoomMemberId(roomId, targetUserId))
                    .orElseThrow(() -> ApiException.badRequest("강퇴 대상이 활성 멤버가 아닙니다."));
            m.markLeft();
            memberRepo.save(m);
        });

        // 커밋 성공 후에만 Redis 정리 — tx 롤백 시 캐시가 RDB 보다 앞서지 않게.
        redis.opsForSet().remove(ChatRedisKeys.roomMembers(roomId), targetUserId);
        unreadService.clear(targetUserId, roomId);

        fanout.fanOutToUser(targetUserId, Map.of("type", "room_left", "room_id", roomId));
        fanout.unsubscribeUserFromRoom(targetUserId, roomId);
        messageService.sendSystemMessage(roomId, "kick", meId, List.of(targetUserId));

        log.info("멤버 강퇴: room_id={}, kicker={}, target={}", roomId, meId, targetUserId);
    }

    // ──────────────────── 읽음 ────────────────────

    public long markRead(String meId, String meSessionId, String roomId, long upToServerSeq) {
        if (upToServerSeq <= 0) {
            throw ApiException.badRequest("up_to_server_seq 는 1 이상이어야 합니다.");
        }
        roomRepo.findById(roomId)
                .orElseThrow(() -> new ChatRoomNotFoundException("존재하지 않는 방입니다."));

        // 클라이언트 입력을 방의 현재 seq 로 클램프 — 미래 seq 주입 시 last_read 가 GREATEST 로 영구(비가역)
        // 박혀 unread 가 영영 0 이 되는 걸 차단. 송신은 room:seq INCR → 저장 순서라 정상 읽음은 깎이지 않는다.
        long currentSeq = getCurrentSeq(roomId);
        long upTo = Math.min(upToServerSeq, currentSeq);

        Long finalSeq = txTemplate.execute(s -> {
            int updated = memberRepo.markRead(roomId, meId, upTo);
            if (updated == 0) {
                return null;
            }
            return memberRepo.findLastReadSeq(roomId, meId).orElse(upTo);
        });
        if (finalSeq == null) {
            throw ApiException.forbidden("이 방의 활성 멤버가 아닙니다.");
        }

        syncUnreadCacheAfterRead(meId, roomId, finalSeq, currentSeq);

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

    /**
     * 읽음 후 unread 캐시 동기화. read 는 고빈도라 매번 무효화(clear)하면 다음 조회가 Mongo count 를 강제한다.
     * 방의 현재 seq 이하까지 읽었으면(흔한 경우) Mongo 없이 0 을 캐시한다.
     *
     * <p>경합 가드: 송신 경로는 항상 {@code room:seq} 증가 → … → unread hDel 순서다. 0 기록 후 seq 를 재확인해
     * 그 사이 새 메시지로 진전됐으면 0 을 버린다(상대 hDel 이 0 보다 먼저 일어나 0 이 무효화를 덮어쓰는 경우 방지).
     */
    private void syncUnreadCacheAfterRead(String meId, String roomId, long finalSeq, long currentSeq) {
        if (finalSeq < currentSeq) {
            unreadService.clear(meId, roomId);   // 아직 더 최신 메시지 — 다음 읽기에 진실로 재계산
            return;
        }
        unreadService.resetToZero(meId, roomId); // 최신까지 읽음 → 0 확정 (Mongo 불필요)
        if (getCurrentSeq(roomId) > finalSeq) {  // fresh read — 0 기록 중 새 메시지 유입 시 0 폐기
            unreadService.clear(meId, roomId);
        }
    }

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

    private ChatRoomResponse toDirectDto(ChatRoom room, UserProfileView peer) {
        ChatRoomPeerResponse peerResp = new ChatRoomPeerResponse(
                peer.userId(), peer.userName(), peer.profileImageUrl());
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
