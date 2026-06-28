package site.krip.domain.chat.entity;

import java.io.Serializable;
import java.util.Objects;

/** {@link ChatRoomMember} 복합 PK (chat_room_id, user_id). */
// 필드는 JPA 가 채움(no-arg 생성자 후 주입) — NullAway 초기화 검사 제외.
@SuppressWarnings("NullAway.Init")
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
