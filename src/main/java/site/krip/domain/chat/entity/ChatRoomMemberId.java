package site.krip.domain.chat.entity;

import java.io.Serializable;
import java.util.Objects;

/** {@link ChatRoomMember} 복합 PK (chat_room_id, user_id). */
public class ChatRoomMemberId implements Serializable {

    private String chatRoomId;
    private String userId;

    public ChatRoomMemberId() {
    }

    public ChatRoomMemberId(String chatRoomId, String userId) {
        this.chatRoomId = chatRoomId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatRoomMemberId that)) {
            return false;
        }
        return Objects.equals(chatRoomId, that.chatRoomId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chatRoomId, userId);
    }
}
