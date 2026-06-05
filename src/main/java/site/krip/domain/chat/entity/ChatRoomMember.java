package site.krip.domain.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 채팅방 ↔ 유저 매핑(복합 PK).
 *
 * <p>퇴장은 {@code is_left=true} soft delete — 재초대 시 {@code last_read_*} 유지로 미읽음 보존.
 * {@code last_read_message_server_seq} 는 읽음 뱃지의 유일한 소스(GREATEST 로 regress 방지).
 * {@code notification_muted}: true=차단, null=기본(허용).
 */
@Entity
@Table(name = "chat_room_member")
@IdClass(ChatRoomMemberId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomMember {

    @Id
    @Column(name = "chat_room_id", length = 50)
    private String chatRoomId;

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "last_read_message_server_seq")
    private Long lastReadMessageServerSeq;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    @Column(name = "is_left", nullable = false)
    private boolean left = false;

    @Column(name = "notification_muted")
    private Boolean notificationMuted;

    public ChatRoomMember(String chatRoomId, String userId, Long lastReadMessageServerSeq) {
        this.chatRoomId = chatRoomId;
        this.userId = userId;
        this.lastReadMessageServerSeq = lastReadMessageServerSeq;
        this.left = false;
    }

    /** 재초대 — soft delete 해제 + joined_at 갱신 + mute 리셋(last_read 는 유지). */
    public void rejoin() {
        this.left = false;
        this.joinedAt = Instant.now();
        this.notificationMuted = null;
    }

    /** 퇴장/강퇴 — soft delete. */
    public void markLeft() {
        this.left = true;
    }

    /** 방별 알림 차단 토글 — True 만 저장, 해제는 NULL. */
    public void applyNotificationMute(boolean muted) {
        this.notificationMuted = muted ? Boolean.TRUE : null;
    }

    @PrePersist
    void onCreate() {
        if (this.joinedAt == null) {
            this.joinedAt = Instant.now();
        }
    }
}
