package site.krip.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * FCM 디바이스 토큰 — 한 유저 1:N 디바이스.
 *
 * <p>{@code token} UNIQUE — 동일 토큰 재등록은 owner 교체 + updated_at 갱신. 탈퇴 시 users FK CASCADE.
 * UNREGISTERED 응답 토큰은 서비스가 즉시 DELETE.
 */
@Entity
@Table(name = "fcm_token",
        uniqueConstraints = @UniqueConstraint(name = "uq_fcm_token_token", columnNames = "token"),
        indexes = @Index(name = "ix_fcm_token_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FcmToken {

    @Id
    @Column(name = "fcm_token_id", length = 50)
    private String fcmTokenId;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Column(name = "token", length = 512, nullable = false)
    private String token;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FcmToken(String userId, String token) {
        this.fcmTokenId = IdGenerator.fcmTokenId();
        this.userId = userId;
        this.token = token;
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
