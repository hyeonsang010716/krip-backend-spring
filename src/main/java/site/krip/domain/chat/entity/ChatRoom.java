package site.krip.domain.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * 채팅방 메타(RDB).
 *
 * <p>DIRECT: {@code (direct_user_a_id, direct_user_b_id)} canonical 정렬(a&lt;b) + partial UNIQUE 로
 * 중복 차단. GROUP: direct_user_* 가 NULL. {@code last_message_*} 는 역정규화(실패 시 dirty 큐 → reconcile).
 * {@code effective_last_at} 은 GENERATED STORED(COALESCE(last_message_at, created_at)) — 정렬 인덱스 1개로 끝.
 *
 * <p>유저 FK 는 모두 ON DELETE SET NULL(대화/방 보존, 탈퇴 자리는 NULL). last_message 갱신은
 * bulk UPDATE(레포지토리)라 엔티티 mutator 불필요 — created_at/updated_at 만 라이프사이클로 설정.
 */
@Entity
@Table(name = "chat_room")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

    @Id
    @Column(name = "chat_room_id", length = 50)
    private String chatRoomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ChatRoomType type;

    @Column(name = "title", length = 100)
    private @Nullable String title;

    @Column(name = "creator_id", length = 50)
    private @Nullable String creatorId;

    @Column(name = "direct_user_a_id", length = 50)
    private @Nullable String directUserAId;

    @Column(name = "direct_user_b_id", length = 50)
    private @Nullable String directUserBId;

    @Column(name = "last_message_id", length = 50)
    private @Nullable String lastMessageId;

    @Column(name = "last_message_server_seq")
    private @Nullable Long lastMessageServerSeq;

    @Column(name = "last_message_at")
    private @Nullable Instant lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // GENERATED ALWAYS AS (COALESCE(last_message_at, created_at)) STORED — DB 가 채움(읽기 전용).
    @Column(name = "effective_last_at", insertable = false, updatable = false)
    private @Nullable Instant effectiveLastAt;

    private ChatRoom(ChatRoomType type, @Nullable String title, String creatorId,
                     @Nullable String directUserAId, @Nullable String directUserBId) {
        this.chatRoomId = IdGenerator.chatRoomId();
        this.type = type;
        this.title = title;
        this.creatorId = creatorId;
        this.directUserAId = directUserAId;
        this.directUserBId = directUserBId;
    }

    /** 1:1 방 생성 — 호출측이 a&lt;b 로 정렬해 넘긴다. */
    public static ChatRoom direct(String creatorId, String userAId, String userBId) {
        return new ChatRoom(ChatRoomType.DIRECT, null, creatorId, userAId, userBId);
    }

    /** 그룹 방 생성. */
    public static ChatRoom group(String creatorId, String title) {
        return new ChatRoom(ChatRoomType.GROUP, title, creatorId, null, null);
    }

    /** 정렬 기준 시각 — DB 미반영(신규 INSERT 직후) 시 created_at 으로 fallback. */
    public Instant effectiveLastAtOrCreated() {
        return effectiveLastAt != null ? effectiveLastAt : createdAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
