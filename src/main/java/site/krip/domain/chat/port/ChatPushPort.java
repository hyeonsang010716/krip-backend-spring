package site.krip.domain.chat.port;

import java.util.List;

/**
 * 채팅 메시지 FCM 푸시 훅 (notification 도메인 소유). notification 포팅 전까지 stub.
 *
 * <p>메시지 송신 핫패스 밖(fire-and-forget)에서 호출되며, 어떤 예외도 ACK 를 막지 않는다.
 */
public interface ChatPushPort {

    void sendChatPush(List<String> userIds, String chatRoomId, String senderId, String body);
}
