package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
public class BlockCacheService implements BlockCachePort {

    private static final Logger log = LoggerFactory.getLogger(BlockCacheService.class);

    private final ChatRoomRepository roomRepo;
    private final StringRedisTemplate redis;

    public BlockCacheService(ChatRoomRepository roomRepo, StringRedisTemplate redis) {
        this.roomRepo = roomRepo;
        this.redis = redis;
    }

    @Override
    @Transactional(readOnly = true)
    public void invalidateBlockCache(String userA, String userB) {
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
