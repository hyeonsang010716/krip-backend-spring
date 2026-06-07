package site.krip.domain.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.notification.dto.response.FcmTokenResponse;
import site.krip.domain.notification.entity.FcmToken;
import site.krip.domain.notification.fcm.FcmClient;
import site.krip.domain.notification.repository.FcmTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FCM 토큰 등록/해제 + 채팅 푸시 발송.
 *
 * <p>채팅 푸시 게이팅 순서: 방별(활성+방 미차단) → 전역(미차단) → 토큰 일괄 → multicast. 무효 토큰은 즉시 정리.
 * FCM 비활성(자격증명 미설정) 시 게이팅까지만 수행하고 발송은 skip(반환 0).
 */
@Service
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);
    private static final String DEFAULT_CHAT_PUSH_TITLE = "새 메시지";

    private final FcmTokenRepository tokenRepo;
    private final ChatRoomMemberRepository memberRepo;
    private final UserRepository userRepo;
    private final FcmClient fcmClient;
    private final Clock clock;

    public FcmService(FcmTokenRepository tokenRepo, ChatRoomMemberRepository memberRepo,
                      UserRepository userRepo, FcmClient fcmClient, Clock clock) {
        this.tokenRepo = tokenRepo;
        this.memberRepo = memberRepo;
        this.userRepo = userRepo;
        this.fcmClient = fcmClient;
        this.clock = clock;
    }

    /** 디바이스 토큰 등록 — UNIQUE(token) 충돌 시 owner 교체 + updated_at 갱신(재로그인/계정 전환), race 안전. */
    @Transactional
    public FcmTokenResponse registerToken(String userId, String token) {
        FcmToken existing = tokenRepo.findByToken(token).orElse(null);
        if (existing != null) {
            existing.reassign(userId);
            tokenRepo.save(existing);
            return new FcmTokenResponse(existing.getFcmTokenId(), existing.getCreatedAt());
        }
        try {
            FcmToken saved = tokenRepo.saveAndFlush(new FcmToken(userId, token));
            return new FcmTokenResponse(saved.getFcmTokenId(), saved.getCreatedAt());
        } catch (DataIntegrityViolationException e) {
            // 동시 등록 race — 재조회 후 재등록 처리.
            FcmToken raced = tokenRepo.findByToken(token)
                    .orElseThrow(() -> e);
            raced.reassign(userId);
            tokenRepo.save(raced);
            return new FcmTokenResponse(raced.getFcmTokenId(), raced.getCreatedAt());
        }
    }

    /** 본인 소유만 삭제 — 없거나 타인 소유여도 멱등. */
    @Transactional
    public void unregisterToken(String userId, String token) {
        tokenRepo.deleteByUserIdAndToken(userId, token);
    }

    /**
     * 채팅 새 메시지 푸시 — N명 fan-out. 게이팅(방/전역 뮤트) 후 multicast 1회.
     * 트랜잭션 없이 각 repo 호출이 독립 — Firebase 네트워크 호출이 DB 커넥션을 점유하지 않게 한다.
     */
    public int sendChatPush(List<String> userIds, String chatRoomId, String senderId, String body, String title) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        List<String> pushableInRoom = memberRepo.findPushableUserIdsInRoom(chatRoomId, userIds);
        if (pushableInRoom.isEmpty()) {
            return 0;
        }
        List<String> allowed = userRepo.findUnmutedUserIds(pushableInRoom);
        if (allowed.isEmpty()) {
            return 0;
        }
        // 무효 토큰 정리 가드 기준 — 이 시점 이후 재등록(updated_at 갱신)된 토큰은 정리에서 제외한다.
        Instant asOf = clock.instant();
        List<FcmToken> rows = tokenRepo.findByUserIdIn(allowed);
        if (rows.isEmpty()) {
            return 0;
        }
        if (!fcmClient.isEnabled()) {
            return 0;
        }

        String finalTitle = title != null ? title : resolveSenderDisplayName(senderId);
        List<String> tokens = rows.stream().map(FcmToken::getToken).toList();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "chat");
        data.put("chatRoomId", chatRoomId);
        data.put("senderId", senderId);
        data.put("url", "/chat/" + chatRoomId);

        FcmClient.SendResult result = fcmClient.sendMulticast(tokens, finalTitle, body, data);
        if (!result.invalidTokens().isEmpty()) {
            int removed = tokenRepo.deleteByTokenInAndUpdatedAtLessThanEqual(result.invalidTokens(), asOf);
            log.info("FCM 무효 토큰 정리 chat_room_id={} invalid={} removed={}",
                    chatRoomId, result.invalidTokens().size(), removed);
        }
        return result.successCount();
    }

    private String resolveSenderDisplayName(String senderId) {
        try {
            UserDetailInform detail = userRepo.findByIdWithProfile(senderId).map(User::getDetail).orElse(null);
            if (detail != null && detail.getUserName() != null && !detail.getUserName().isBlank()) {
                return detail.getUserName();
            }
        } catch (Exception e) {
            log.warn("발신자 이름 조회 실패 sender_id={}", senderId, e);
        }
        return DEFAULT_CHAT_PUSH_TITLE;
    }
}
