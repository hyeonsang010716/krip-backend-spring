package site.krip.domain.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.domain.friend.port.BlockCachePort;
import site.krip.global.chat.ChatRedisKeys;

/**
 * 차단 캐시 무효화 — friend 도메인 {@link BlockCachePort} 의 실제 구현.
 *
 * <p>block/unblock 후 두 유저의 1:1 방 {@code room:blocks:{R}} 캐시를 DEL. 그룹 방은 대상 아님.
 * chat 이 자기 Redis 키 규약을 소유하므로 모든 조작은 이 클래스에만 존재.
 */
@Service
@Slf4j
public class BlockCacheService implements BlockCachePort {

    private final ChatRoomRepository roomRepo;
    private final StringRedisTemplate redis;

    public BlockCacheService(ChatRoomRepository roomRepo, StringRedisTemplate redis) {
        this.roomRepo = roomRepo;
        this.redis = redis;
    }

    @Override
    public void invalidateBlockCache(String userA, String userB) {
        // 트랜잭션 어노테이션 없음 — read 1건은 리포지토리 자체 readOnly tx(또는 호출자 tx)에서 돌고,
        // Redis DEL(캐시 무효화)은 tx 밖에서 도는 게 맞다. 여기에 RDB 쓰기를 추가하면 안 된다(이 메서드는 무효화 전용).
        String a = userA.compareTo(userB) < 0 ? userA : userB;
        String b = userA.compareTo(userB) < 0 ? userB : userA;
        ChatRoom room = roomRepo.findDirectByPair(a, b).orElse(null);
        if (room == null) {
            return;
        }
        redis.delete(ChatRedisKeys.roomBlocks(room.getChatRoomId()));
        log.info("block 캐시 무효화: room_id={}, a={}, b={}", room.getChatRoomId(), a, b);
    }
}
