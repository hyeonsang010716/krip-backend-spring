package site.krip.domain.auth.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import site.krip.global.support.IdGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 유저. PK 는 {@code USER_{epoch}_{uuid8}} 를 생성 시점에 직접 부여하고, status/auth_provider 는
 * enum 이름(ACTIVE/GOOGLE)으로 저장된다. 자식(detail, travel_styles)은 DB CASCADE 로 함께 삭제.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_provider_account",
                columnNames = {"auth_provider", "auth_provider_id"}),
        // 탐색용 partial index(ix_users_active_created, WHERE status='ACTIVE')는 JPA 로 표현
        // 불가하여 Flyway(V1__init_auth_schema.sql)에서 관리. validate 는 인덱스를 검증하지 않음.
        indexes = {
                @Index(name = "ix_provider_lookup", columnList = "auth_provider, auth_provider_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    private OAuthProvider authProvider;

    @Column(name = "auth_provider_id", length = 255, nullable = false)
    private String authProviderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    // 전역 알림 차단 — TRUE 면 차단, NULL = 기본(허용). 명시적 차단만 row 에 적힘.
    @Column(name = "notification_muted")
    private Boolean notificationMuted;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private UserDetailInform detail;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<UserTravelStyle> travelStyles = new ArrayList<>();

    private User(OAuthProvider authProvider, String authProviderId) {
        this.userId = IdGenerator.userId();
        this.authProvider = authProvider;
        this.authProviderId = authProviderId;
        this.status = UserStatus.ACTIVE;
    }

    /** 1차 가입 — OAuth 콜백 시 신규 유저 생성. */
    public static User createNew(OAuthProvider authProvider, String authProviderId) {
        return new User(authProvider, authProviderId);
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    /** 여행 스타일 전체 교체. orphanRemoval=true 라 기존은 삭제되고 새 set 이 삽입된다. */
    public void replaceTravelStyles(List<TravelStyle> styles) {
        this.travelStyles.clear();
        for (TravelStyle style : styles) {
            this.travelStyles.add(new UserTravelStyle(this, style));
        }
    }

    public boolean isNotificationMuted() {
        return Boolean.TRUE.equals(notificationMuted);
    }

    /** 전역 알림 차단 토글 — True 만 저장, 해제는 NULL. */
    public void applyNotificationMute(boolean muted) {
        this.notificationMuted = muted ? Boolean.TRUE : null;
    }
}
