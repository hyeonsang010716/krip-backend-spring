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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 채팅 last_message 역정규화 reconcile.
 *
 * <p>주기적으로 {@code dirty:chat_room} SET 을 SPOP 배치 소진하며 {@code chat_room.last_message_*} 를
 * Mongo 진실값으로 수렴. 송신과 병렬이라 regress 방지 위해 {@code updateLastMessageIfGreater} 사용.
 * 실패한 방은 dirty 로 재적재. 한 tick 은 시간·배치 수 예산 내에서만 drain 하고 잔여 백로그는 다음 tick 으로
 * 이월한다 — 무제한 drain 이 {@code lockAtMostFor} 를 넘겨 락 만료 후 다른 노드와 동시 drain 하는 것을 막는다.
 * 멀티 노드에선 {@code @SchedulerLock} 으로 한 노드만 실행해 중복 drain 을 막는다.
 */
@Component
public class ReconcileWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconcileWorker.class);
    private static final int BATCH_SIZE = 500;
    // tick 예산 — lockAtMostFor(9m) 보다 충분히 작게 잡아 락 보유 중 drain 이 끝나도록 보장.
    private static final int MAX_BATCHES_PER_TICK = 50;                          // 하드 상한 25k 방/tick
    private static final long MAX_RUN_NANOS = Duration.ofMinutes(4).toNanos();   // 벽시계 상한

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
    @SchedulerLock(name = "chatReconcile", lockAtMostFor = "9m", lockAtLeastFor = "30s")
    public void reconcileTick() {
        try {
            long deadline = System.nanoTime() + MAX_RUN_NANOS;
            int batches = 0;
            int processed;
            do {
                processed = reconcileOnce();
                batches++;
            } while (processed >= BATCH_SIZE && batches < MAX_BATCHES_PER_TICK
                    && System.nanoTime() - deadline < 0);
            if (processed >= BATCH_SIZE) {
                log.info("reconcile: tick 예산 소진 (batches={}) — 잔여 백로그는 다음 tick 으로 이월", batches);
            }
        } catch (Exception e) {
            log.error("reconcile tick 전역 실패 (다음 tick 재시도)", e);
        }
    }

    /**
     * dirty 에서 한 배치를 읽어(제거하지 않음) RDB last_message_* 를 Mongo 값으로 갱신하고, 해소된 방만 SREM.
     * 해소 방 개수 반환.
     *
     * <p>at-least-once: SPOP(선제거) 대신 SRANDMEMBER(읽기) → 성공분만 SREM 한다. 크래시/재시작이 read 와
     * SREM 사이에 끼어도 방이 set 에 남아 다음 tick 에 재처리된다({@code updateLastMessageIfGreater} 가 멱등·단조라
     * 재처리 무해). {@code @SchedulerLock}+{@code @Scheduled}(non-overlapping) 로 소비자는 단일이라 경합 없음.
     */
    int reconcileOnce() {
        Set<String> sampled = redis.opsForSet()
                .distinctRandomMembers(ChatRedisKeys.DIRTY_CHAT_ROOM_KEY, BATCH_SIZE);
        if (sampled == null || sampled.isEmpty()) {
            return 0;
        }
        List<String> roomIds = new ArrayList<>(sampled);

        Map<String, LastMessage> lastByRoom;
        try {
            lastByRoom = messageRepo.findLastByRooms(roomIds);
        } catch (Exception e) {
            // 읽기만 했으므로 방은 set 에 그대로 — 다음 tick 재시도(at-least-once).
            log.warn("reconcile: Mongo aggregate 실패 → {}개 방 다음 tick 재시도", roomIds.size(), e);
            return 0;
        }

        // 해소된 방만 제거: 갱신 성공 + Mongo 데이터 없음(수렴할 것 없음) = 해소. UPDATE 실패는 남겨 재시도.
        List<String> resolved = new ArrayList<>();
        int updated = 0;
        for (String roomId : roomIds) {
            LastMessage doc = lastByRoom.get(roomId);
            if (doc == null) {
                resolved.add(roomId); // Mongo 메시지 없음 — reconcile 불필요, 해소 처리
                continue;
            }
            try {
                roomRepo.updateLastMessageIfGreater(roomId, doc.messageId(), doc.serverSeq(),
                        doc.createdAt().toInstant());
                updated++;
                resolved.add(roomId);
            } catch (Exception ex) {
                log.warn("reconcile: 방 {} UPDATE 실패 — set 에 유지(다음 tick 재시도)", roomId, ex);
            }
        }
        if (!resolved.isEmpty()) {
            redis.opsForSet().remove(ChatRedisKeys.DIRTY_CHAT_ROOM_KEY, resolved.toArray());
        }
        log.info("reconcile: read={}, mongo_hit={}, updated={}, resolved={}",
                roomIds.size(), lastByRoom.size(), updated, resolved.size());
        return resolved.size();
    }
}
