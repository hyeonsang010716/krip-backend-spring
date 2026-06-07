package site.krip.domain.friend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.krip.domain.auth.entity.User;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * 친구 관계 — 요청 대기 / 수락 / 거절.
 *
 * <p>두 유저 간 관계는 방향 무관 유일 (canonical unique index). {@code requesterId}/
 * {@code addresseeId} 가 FK 를 소유(쓰기)하고 {@code requester}/{@code addressee} 는 읽기 전용
 * 네비게이션. 자기 자신 요청 불가(CHECK). 양 FK 는 users CASCADE.
 */
@Entity
@Table(name = "friendship", indexes = {
        @Index(name = "ix_friendship_requester_status", columnList = "requester_id, status"),
        @Index(name = "ix_friendship_addressee_status", columnList = "addressee_id, status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Friendship {

    @Id
    @Column(name = "friendship_id", length = 50)
    private String friendshipId;

    @Column(name = "requester_id", length = 50, nullable = false)
    private String requesterId;

    @Column(name = "addressee_id", length = 50, nullable = false)
    private String addresseeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", insertable = false, updatable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "addressee_id", insertable = false, updatable = false)
    private User addressee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FriendshipStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 낙관적 락 — 동시 수락/차단 등 같은 관계 행에 대한 lost-update 를 차단(충돌 시 409). */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Friendship(String requesterId, String addresseeId) {
        this.friendshipId = IdGenerator.friendshipId();
        this.requesterId = requesterId;
        this.addresseeId = addresseeId;
        this.status = FriendshipStatus.PENDING;
    }

    public void accept() {
        this.status = FriendshipStatus.ACCEPTED;
    }

    public void reject() {
        this.status = FriendshipStatus.REJECTED;
    }

    /** REJECTED → 재요청: 방향 반전 포함 PENDING 으로 재개. */
    public void reopenAsPending(String requesterId, String addresseeId) {
        this.requesterId = requesterId;
        this.addresseeId = addresseeId;
        this.status = FriendshipStatus.PENDING;
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
