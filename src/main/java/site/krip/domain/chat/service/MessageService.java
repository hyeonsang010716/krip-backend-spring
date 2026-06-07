package site.krip.domain.chat.service;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import site.krip.domain.chat.dto.response.EditMessageResponse;
import site.krip.domain.chat.dto.response.MessageSentAck;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomType;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.exception.ChatUpstreamException;
import site.krip.domain.chat.port.ChatPushPort;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.IdGenerator;
import site.krip.global.support.IsoTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 메시지 송신/시스템/편집/삭제.
 *
 * <p>핫패스: 멤버십(Redis 캐시) → rate limit(Lua) → 차단(DIRECT) → dedupe(NX) → seq 채번 →
 * Mongo insert(DuplicateKey 시 force_jump 재시도) → last_message 역정규화(실패 시 dirty 큐) →
 * unread 증가 → fan-out → FCM 푸시(fire-and-forget). RDB 쓰기는 last_message bulk UPDATE 1건뿐.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final int MAX_INSERT_ATTEMPTS = 3;
    private static final Duration EDIT_TIME_LIMIT = Duration.ofMinutes(5);
    private static final int PUSH_BODY_PREVIEW_LIMIT = 100;

    private final ChatRoomMemberRepository memberRepo;
    private final ChatRoomRepository roomRepo;
    private final ChatMessageRepository messageRepo;
    private final UserBlockRepository blockRepo;
    private final FanoutService fanout;
    private final ChatSeqAllocator seq;
    private final ChatPushPort push;
    private final StringRedisTemplate redis;
    private final StringRedisTemplate dedupeRedis;
    private final Executor pushExecutor;

    public MessageService(ChatRoomMemberRepository memberRepo, ChatRoomRepository roomRepo,
                          ChatMessageRepository messageRepo, UserBlockRepository blockRepo,
                          FanoutService fanout, ChatSeqAllocator seq, ChatPushPort push,
                          StringRedisTemplate redis,
                          @Qualifier("dedupeRedisTemplate") StringRedisTemplate dedupeRedis,
                          @Qualifier("pushExecutor") Executor pushExecutor) {
        this.memberRepo = memberRepo;
        this.roomRepo = roomRepo;
        this.messageRepo = messageRepo;
        this.blockRepo = blockRepo;
        this.fanout = fanout;
        this.seq = seq;
        this.push = push;
        this.redis = redis;
        this.dedupeRedis = dedupeRedis;
        this.pushExecutor = pushExecutor;
    }

    // ──────────────────── 메시지 송신 ────────────────────

    public MessageSentAck sendMessage(String senderUserId, String senderSessionId, String roomId,
                                      String clientMsgId, MessageType msgType, String content) {
        ensureMembership(roomId, senderUserId);

        long count = seq.incrWithTtl(ChatRedisKeys.rateMsg(senderUserId), ChatRedisKeys.RATE_LIMIT_TTL);
        if (count > ChatRedisKeys.RATE_LIMIT_THRESHOLD) {
            throw ApiException.badRequest("메시지 전송 속도 제한에 걸렸습니다. 잠시 후 다시 시도해주세요.");
        }

        ChatRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 방입니다."));
        if (room.getType() == ChatRoomType.DIRECT && isDirectBlocked(room, senderUserId)) {
            throw ApiException.forbidden("차단 관계인 유저에게는 메시지를 보낼 수 없습니다.");
        }

        String dedupeK = ChatRedisKeys.dedupe(senderUserId, clientMsgId);
        Boolean first = dedupeRedis.opsForValue()
                .setIfAbsent(dedupeK, "1", Duration.ofSeconds(ChatRedisKeys.DEDUPE_TTL));
        if (!Boolean.TRUE.equals(first)) {
            throw ApiException.badRequest("이미 처리된 메시지입니다 (dedupe).");
        }

        Instant now = Instant.now();
        String messageId = IdGenerator.messageId();
        long serverSeq;
        try {
            serverSeq = seq.allocateSeq(roomId);
            Document doc = baseDoc(messageId, roomId, serverSeq, senderUserId, msgType, content, now);
            serverSeq = insertWithRetry(doc, roomId, serverSeq);
        } catch (RuntimeException e) {
            dedupeRedis.delete(dedupeK);
            throw e;
        }

        updateLastMessageBestEffort(roomId, messageId, serverSeq, now);

        if (msgType != MessageType.SYSTEM) {
            bumpUnread(roomId, senderUserId);
        }

        fanout.fanOutToRoom(roomId, messageNewPayload(senderSessionId, messageId, roomId, serverSeq,
                senderUserId, msgType, content, now));

        if (msgType != MessageType.SYSTEM) {
            spawnPush(roomId, senderUserId, content);
        }

        return new MessageSentAck(clientMsgId, messageId, serverSeq, now);
    }

    // ──────────────────── 시스템 메시지 ────────────────────

    /** 방 관리 액션(created/join/leave/kick) 타임라인 기록 — 멤버십/rate/dedupe/unread/push skip. */
    public void sendSystemMessage(String roomId, String action, String actorId,
                                  List<String> targetIds, String actorSessionId) {
        Instant now = Instant.now();
        String messageId = IdGenerator.messageId();
        Map<String, Object> content = new HashMap<>();
        content.put("action", action);
        content.put("actor_id", actorId);
        if (targetIds != null && !targetIds.isEmpty()) {
            content.put("target_ids", new ArrayList<>(targetIds));
        }

        long serverSeq = seq.allocateSeq(roomId);
        Document doc = baseDoc(messageId, roomId, serverSeq, null, MessageType.SYSTEM, content, now);
        serverSeq = insertWithRetry(doc, roomId, serverSeq);

        updateLastMessageBestEffort(roomId, messageId, serverSeq, now);

        fanout.fanOutToRoom(roomId, messageNewPayload(actorSessionId, messageId, roomId, serverSeq,
                null, MessageType.SYSTEM, content, now));

        log.info("시스템 메시지: room_id={}, action={}, actor={}, seq={}, target_ids={}",
                roomId, action, actorId, serverSeq, targetIds);
    }

    public void sendSystemMessage(String roomId, String action, String actorId) {
        sendSystemMessage(roomId, action, actorId, null, null);
    }

    public void sendSystemMessage(String roomId, String action, String actorId, List<String> targetIds) {
        sendSystemMessage(roomId, action, actorId, targetIds, null);
    }

    // ──────────────────── 편집 ────────────────────

    public EditMessageResponse editMessage(String messageId, String editorUserId,
                                            String editorSessionId, String newContent) {
        Document doc = messageRepo.findById(messageId);
        if (doc == null) {
            throw ApiException.badRequest("존재하지 않는 메시지입니다.");
        }
        if (doc.getDate("deleted_at") != null) {
            throw ApiException.badRequest("삭제된 메시지는 편집할 수 없습니다.");
        }
        if (MessageType.SYSTEM.getValue().equals(doc.getString("type"))) {
            throw ApiException.forbidden("시스템 메시지는 편집할 수 없습니다.");
        }
        if (!editorUserId.equals(doc.getString("sender_id"))) {
            throw ApiException.forbidden("본인 메시지만 편집할 수 있습니다.");
        }
        String roomId = doc.getString("chat_room_id");
        if (!memberRepo.isActiveMember(roomId, editorUserId)) {
            throw ApiException.forbidden("이 방의 활성 멤버가 아닙니다.");
        }
        Instant now = Instant.now();
        Date createdAt = doc.getDate("created_at");
        if (createdAt != null && Duration.between(createdAt.toInstant(), now).compareTo(EDIT_TIME_LIMIT) > 0) {
            throw ApiException.badRequest("메시지 편집 제한 시간(5분)이 지났습니다.");
        }

        messageRepo.updateContent(messageId, newContent, Date.from(now));

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "message.updated");
        payload.put("sender_session_id", editorSessionId);
        payload.put("message_id", messageId);
        payload.put("content", newContent);
        payload.put("edited_at", IsoTimestamp.format(now));
        fanout.fanOutToRoom(roomId, payload);

        return new EditMessageResponse(messageId, newContent, now);
    }

    // ──────────────────── 삭제 (soft) ────────────────────

    public void deleteMessage(String messageId, String deleterUserId, String deleterSessionId) {
        Document doc = messageRepo.findById(messageId);
        if (doc == null) {
            throw ApiException.badRequest("존재하지 않는 메시지입니다.");
        }
        if (doc.getDate("deleted_at") != null) {
            throw ApiException.badRequest("이미 삭제된 메시지입니다.");
        }
        if (MessageType.SYSTEM.getValue().equals(doc.getString("type"))) {
            throw ApiException.forbidden("시스템 메시지는 삭제할 수 없습니다.");
        }
        String roomId = doc.getString("chat_room_id");
        String senderId = doc.getString("sender_id");
        if (!memberRepo.isActiveMember(roomId, deleterUserId)) {
            throw ApiException.forbidden("이 방의 활성 멤버가 아닙니다.");
        }
        if (!deleterUserId.equals(senderId)) {
            ChatRoom room = roomRepo.findById(roomId)
                    .orElseThrow(() -> ApiException.badRequest("존재하지 않는 방입니다."));
            boolean isGroupCreator = room.getType() == ChatRoomType.GROUP
                    && deleterUserId.equals(room.getCreatorId());
            if (!isGroupCreator) {
                throw ApiException.forbidden("본인 메시지 또는 그룹 방장만 삭제할 수 있습니다.");
            }
        }

        Instant now = Instant.now();
        messageRepo.softDelete(messageId, Date.from(now));

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "message.deleted");
        payload.put("sender_session_id", deleterSessionId);
        payload.put("message_id", messageId);
        payload.put("deleted_at", IsoTimestamp.format(now));
        fanout.fanOutToRoom(roomId, payload);
    }

    // ──────────────────── 헬퍼 ────────────────────

    private void ensureMembership(String roomId, String userId) {
        String key = ChatRedisKeys.roomMembers(roomId);
        Boolean isMember = redis.opsForSet().isMember(key, userId);
        if (Boolean.TRUE.equals(isMember)) {
            return;
        }
        List<String> members = memberRepo.findActiveMemberIds(roomId);
        if (members.isEmpty()) {
            throw ApiException.badRequest("존재하지 않는 방이거나 멤버가 없습니다.");
        }
        redis.opsForSet().add(key, members.toArray(new String[0]));
        redis.expire(key, Duration.ofSeconds(ChatRedisKeys.ROOM_MEMBERS_TTL));
        if (!members.contains(userId)) {
            throw ApiException.forbidden("이 방의 멤버가 아닙니다.");
        }
    }

    private boolean isDirectBlocked(ChatRoom room, String senderUserId) {
        String peerId = senderUserId.equals(room.getDirectUserAId())
                ? room.getDirectUserBId() : room.getDirectUserAId();
        if (peerId == null) {
            return false;
        }
        String key = ChatRedisKeys.roomBlocks(room.getChatRoomId());
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            List<UserBlock> blocks = blockRepo.findBlocksBetween(senderUserId, peerId);
            List<String> members = new ArrayList<>();
            for (UserBlock b : blocks) {
                members.add(b.getBlockerId() + ":" + b.getBlockedId());
            }
            if (members.isEmpty()) {
                members.add("__none__");
            }
            redis.opsForSet().add(key, members.toArray(new String[0]));
            redis.expire(key, Duration.ofSeconds(ChatRedisKeys.ROOM_BLOCKS_TTL));
        }
        return Boolean.TRUE.equals(redis.opsForSet().isMember(key, senderUserId + ":" + peerId))
                || Boolean.TRUE.equals(redis.opsForSet().isMember(key, peerId + ":" + senderUserId));
    }

    private void bumpUnread(String roomId, String senderUserId) {
        try {
            Set<String> members = redis.opsForSet().members(ChatRedisKeys.roomMembers(roomId));
            if (members == null || members.isEmpty()) {
                return;
            }
            List<String> recipients = new ArrayList<>();
            for (String uid : members) {
                if (!uid.equals(senderUserId)) {
                    recipients.add(uid);
                }
            }
            if (recipients.isEmpty()) {
                return;
            }
            // 발신자 제외 멤버 HINCRBY 를 단일 파이프라인 1 RTT 로 — 대형 그룹방에서도 멤버 수만큼 왕복하지 않는다.
            redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                for (String uid : recipients) {
                    conn.hIncrBy(ChatRedisKeys.unread(uid), roomId, 1);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("unread 증가 실패 (무시): room_id={}", roomId, e);
        }
    }

    private long insertWithRetry(Document doc, String roomId, long serverSeq) {
        for (int attempt = 0; attempt < MAX_INSERT_ATTEMPTS; attempt++) {
            try {
                messageRepo.insert(doc);
                return serverSeq;
            } catch (ChatMessageRepository.DuplicateSeqException e) {
                int jitter = ThreadLocalRandom.current().nextInt(1, ChatRedisKeys.SEQ_FORCE_JUMP_JITTER_MAX + 1);
                serverSeq = seq.forceJump(roomId, jitter);
                doc.put("server_seq", serverSeq);
            }
        }
        log.error("메시지 저장 {}회 연속 DuplicateKey: room_id={}", MAX_INSERT_ATTEMPTS, roomId);
        throw new ChatUpstreamException("메시지 저장에 실패했습니다. 잠시 후 다시 시도해주세요.");
    }

    private void updateLastMessageBestEffort(String roomId, String messageId, long serverSeq, Instant at) {
        try {
            // 동시 송신 race 시 낮은 seq 가 덮어쓰지 않도록 IfGreater 가드 (regress 방지).
            roomRepo.updateLastMessageIfGreater(roomId, messageId, serverSeq, at);
        } catch (Exception e) {
            log.warn("last_message 갱신 실패 → dirty 큐: room_id={}, err={}", roomId, e.toString());
            redis.opsForSet().add(ChatRedisKeys.DIRTY_CHAT_ROOM_KEY, roomId);
        }
    }

    private static Document baseDoc(String messageId, String roomId, long serverSeq, String senderId,
                                    MessageType type, Object content, Instant now) {
        Document doc = new Document();
        doc.put("_id", messageId);
        doc.put("chat_room_id", roomId);
        doc.put("server_seq", serverSeq);
        doc.put("sender_id", senderId);
        doc.put("type", type.getValue());
        doc.put("content", content);
        doc.put("created_at", Date.from(now));
        doc.put("edited_at", null);
        doc.put("deleted_at", null);
        return doc;
    }

    private static Map<String, Object> messageNewPayload(String senderSessionId, String messageId,
                                                         String roomId, long serverSeq, String senderId,
                                                         MessageType type, Object content, Instant now) {
        Map<String, Object> message = new HashMap<>();
        message.put("message_id", messageId);
        message.put("chat_room_id", roomId);
        message.put("server_seq", serverSeq);
        message.put("sender_id", senderId);
        message.put("type", type.getValue());
        message.put("content", content);
        message.put("created_at", IsoTimestamp.format(now));

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "message.new");
        payload.put("sender_session_id", senderSessionId);
        payload.put("message", message);
        return payload;
    }

    private void spawnPush(String roomId, String senderUserId, String content) {
        pushExecutor.execute(() -> {
            try {
                Set<String> members = redis.opsForSet().members(ChatRedisKeys.roomMembers(roomId));
                if (members == null || members.isEmpty()) {
                    return;
                }
                List<String> recipients = new ArrayList<>();
                for (String uid : members) {
                    if (!uid.equals(senderUserId)) {
                        recipients.add(uid);
                    }
                }
                if (recipients.isEmpty()) {
                    return;
                }
                String body = content.length() > PUSH_BODY_PREVIEW_LIMIT
                        ? content.substring(0, PUSH_BODY_PREVIEW_LIMIT) + "..." : content;
                push.sendChatPush(recipients, roomId, senderUserId, body);
            } catch (Exception e) {
                log.warn("FCM 푸시 helper 실패 (무시): room_id={}", roomId, e);
            }
        });
    }
}
