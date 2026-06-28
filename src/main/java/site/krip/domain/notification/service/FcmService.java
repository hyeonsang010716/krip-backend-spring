package site.krip.domain.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
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
@Slf4j
public class FcmService {

    private static final String DEFAULT_CHAT_PUSH_TITLE = "새 메시지";
    private static final int MAX_REGISTER_RETRY = 3;

    private final FcmTokenRepository tokenRepo;
    private final ChatRoomMemberRepository memberRepo;
    private final UserQueryPort userQuery;
    private final FcmClient fcmClient;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public FcmService(FcmTokenRepository tokenRepo, ChatRoomMemberRepository memberRepo,
                      UserQueryPort userQuery, FcmClient fcmClient, Clock clock,
                      TransactionTemplate txTemplate) {
        this.tokenRepo = tokenRepo;
        this.memberRepo = memberRepo;
        this.userQuery = userQuery;
        this.fcmClient = fcmClient;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /**
     * 디바이스 토큰 등록 — UNIQUE(token) 충돌 시 owner 교체 + updated_at 갱신(재로그인/계정 전환).
     *
     * <p>INSERT 가 충돌하면 그 트랜잭션은 abort 되므로 같은 트랜잭션에서 복구할 수 없다. 시도마다 독립
     * 트랜잭션을 열어 바운디드 재시도한다 — 다음 시도의 findByToken 이 reassign(행 존재) 또는
     * 재삽입(충돌 행이 그새 삭제됨)을 자연 처리한다. reassign 은 PK UPDATE 라 충돌은 insert 경로에서만 난다.
     */
    public FcmTokenResponse registerToken(String userId, String token) {
        for (int attempt = 0; attempt < MAX_REGISTER_RETRY; attempt++) {
            try {
                return txTemplate.execute(s -> doRegisterToken(userId, token));
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_REGISTER_RETRY - 1) {
                    throw e;
                }
                // 동시 insert 충돌 — 다음 iteration 에서 재조회 후 reassign 또는 재삽입.
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private FcmTokenResponse doRegisterToken(String userId, String token) {
        FcmToken existing = tokenRepo.findByToken(token).orElse(null);
        // 0행 = 그새 동시 삭제됨 → insert 로 폴백(무음 유실 방지). id/created_at 은 불변이라 기존 값 반환.
        if (existing != null && tokenRepo.reassignOwner(token, userId, clock.instant()) == 1) {
            return new FcmTokenResponse(existing.getFcmTokenId(), existing.getCreatedAt());
        }
        FcmToken saved = tokenRepo.saveAndFlush(new FcmToken(userId, token));
        return new FcmTokenResponse(saved.getFcmTokenId(), saved.getCreatedAt());
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
    public int sendChatPush(List<String> userIds, String chatRoomId, String senderId, String body,
                            @Nullable String title) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        List<String> pushableInRoom = memberRepo.findPushableUserIdsInRoom(chatRoomId, userIds);
        if (pushableInRoom.isEmpty()) {
            return 0;
        }
        List<String> allowed = userQuery.retainGloballyUnmuted(pushableInRoom);
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
            UserProfileView sender = userQuery.findProfile(senderId).orElse(null);
            if (sender != null && sender.userName() != null && !sender.userName().isBlank()) {
                return sender.userName();
            }
        } catch (Exception e) {
            log.warn("발신자 이름 조회 실패 sender_id={}", senderId, e);
        }
        return DEFAULT_CHAT_PUSH_TITLE;
    }
}
