package site.krip.domain.friend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.krip.domain.auth.entity.User;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * 유저 차단 관계 — 단방향. (blocker, blocked) 유일.
 * 자기 자신 차단 불가(CHECK). 양 FK 는 users CASCADE.
 */
@Entity
@Table(name = "user_block",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_block_pair",
                columnNames = {"blocker_id", "blocked_id"}),
        indexes = {
                @Index(name = "ix_user_block_blocker", columnList = "blocker_id"),
                @Index(name = "ix_user_block_blocked", columnList = "blocked_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock {

    @Id
    @Column(name = "block_id", length = 50)
    private String blockId;

    @Column(name = "blocker_id", length = 50, nullable = false)
    private String blockerId;

    @Column(name = "blocked_id", length = 50, nullable = false)
    private String blockedId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_id", insertable = false, updatable = false)
    private User blocked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserBlock(String blockerId, String blockedId) {
        this.blockId = IdGenerator.userBlockId();
        this.blockerId = blockerId;
        this.blockedId = blockedId;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
