package site.krip.domain.chat.worker;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatMessageRepository.LastMessage;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.global.chat.ChatRedisKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 채팅 last_message 역정규화 reconcile.
 *
 * <p>주기적으로 {@code dirty:chat_room} SET 을 SPOP 배치 소진하며 {@code chat_room.last_message_*} 를
 * Mongo 진실값으로 수렴. 송신과 병렬이라 regress 방지 위해 {@code updateLastMessageIfGreater} 사용.
 * 실패한 방은 dirty 로 재적재. 한 tick 내 백로그를 drain 한다.
 * 멀티 노드에선 {@code @SchedulerLock} 으로 한 노드만 실행해 중복 drain 을 막는다.
 */
@Component
public class ReconcileWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconcileWorker.class);
    private static final int BATCH_SIZE = 500;

    private final StringRedisTemplate redis;
    private final ChatMessageRepository messageRepo;
    private final ChatRoomRepository roomRepo;

    public ReconcileWorker(StringRedisTemplate redis, ChatMessageRepository messageRepo,
                           ChatRoomRepository roomRepo) {
        this.redis = redis;
        this.messageRepo = messageRepo;
        this.roomRepo = roomRepo;
    }

    @Scheduled(fixedDelayString = "${CHAT_RECONCILE_INTERVAL_MS:300000}",
            initialDelayString = "${CHAT_RECONCILE_INITIAL_DELAY_MS:30000}")
    @SchedulerLock(name = "chatReconcile", lockAtMostFor = "9m")
    public void reconcileTick() {
        try {
            int processed;
            do {
                processed = reconcileOnce();
            } while (processed >= BATCH_SIZE);
        } catch (Exception e) {
            log.error("reconcile tick 전역 실패 (다음 tick 재시도)", e);
        }
    }

    /** dirty 에서 한 배치 pop 후 RDB last_message_* 를 Mongo 값으로 갱신. pop 한 방 개수 반환. */
    int reconcileOnce() {
        List<String> roomIds = redis.opsForSet().pop(ChatRedisKeys.DIRTY_CHAT_ROOM_KEY, BATCH_SIZE);
        if (roomIds == null || roomIds.isEmpty()) {
            return 0;
        }

        Map<String, LastMessage> lastByRoom;
        try {
            lastByRoom = messageRepo.findLastByRooms(roomIds);
        } catch (Exception e) {
            log.warn("reconcile: Mongo aggregate 실패 → {}개 방 재적재", roomIds.size(), e);
            redis.opsForSet().add(ChatRedisKeys.DIRTY_CHAT_ROOM_KEY, roomIds.toArray(new String[0]));
            return 0;
        }
        if (lastByRoom.isEmpty()) {
            log.info("reconcile: pop={} 이지만 Mongo hit 0 — skip", roomIds.size());
            return roomIds.size();
        }

        List<String> failed = new ArrayList<>();
        int updated = 0;
        for (var e : lastByRoom.entrySet()) {
            LastMessage doc = e.getValue();
            try {
                roomRepo.updateLastMessageIfGreater(e.getKey(), doc.messageId(), doc.serverSeq(),
                        doc.createdAt().toInstant());
                updated++;
            } catch (Exception ex) {
                log.warn("reconcile: 방 {} UPDATE 실패 — 재적재", e.getKey(), ex);
                failed.add(e.getKey());
            }
        }
        if (!failed.isEmpty()) {
            redis.opsForSet().add(ChatRedisKeys.DIRTY_CHAT_ROOM_KEY, failed.toArray(new String[0]));
        }
        log.info("reconcile: pop={}, mongo_hit={}, updated={}, requeued={}",
                roomIds.size(), lastByRoom.size(), updated, failed.size());
        return roomIds.size();
    }
}
