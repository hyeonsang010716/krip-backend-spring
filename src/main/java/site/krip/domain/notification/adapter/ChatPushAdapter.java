package site.krip.domain.notification.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.krip.domain.chat.port.ChatPushPort;
import site.krip.domain.notification.service.FcmService;

import java.util.List;

/**
 * chat 의 {@link ChatPushPort} 실제 구현 — 채팅 새 메시지 FCM 푸시(title 은 발신자 이름으로 서비스가 해석).
 */
@Component
@RequiredArgsConstructor
public class ChatPushAdapter implements ChatPushPort {

    private final FcmService fcmService;

    @Override
    public void sendChatPush(List<String> userIds, String chatRoomId, String senderId, String body) {
        fcmService.sendChatPush(userIds, chatRoomId, senderId, body, null);
    }
}
