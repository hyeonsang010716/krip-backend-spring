package site.krip.domain.tour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;
import site.krip.global.support.IdGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 저장된 여행 플랜.
 *
 * <p>{@code travel_days} 는 "부여된 적 있는 day_number 의 최댓값"(monotonic). {@code items} 는
 * (day_number, position) 정렬은 보장하지 않으므로 호출 측에서 정렬한다.
 */
@Entity
@Table(name = "tour_plan", indexes = {
        @Index(name = "ix_tour_plan_user_id", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TourPlan {

    @Id
    @Column(name = "plan_id", length = 50)
    private String planId;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Column(name = "title", length = 100)
    private @Nullable String title;

    @Column(name = "travel_days", nullable = false)
    private int travelDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", insertable = false, updatable = false)
    @OrderBy("dayNumber ASC, position ASC")
    @BatchSize(size = 100)
    private List<TourPlanItem> items = new ArrayList<>();

    public TourPlan(String userId, @Nullable String title, int travelDays) {
        this.planId = IdGenerator.tourPlanId();
        this.userId = userId;
        this.title = title;
        this.travelDays = travelDays;
    }

    /** 플랜 title 수정 (null 이면 제목 제거). */
    public void changeTitle(@Nullable String title) {
        this.title = title;
    }

    /** 빈 일차 추가 — travel_days += 1 (monotonic 증가, gap 재사용 X). */
    public void addDay() {
        this.travelDays += 1;
    }

    /**
     * updated_at 명시적 touch — 카드 변경(추가/이동/삭제·일차삭제)은 plan row 를 안 건드려
     * {@code @PreUpdate} 가 안 터지므로, 필드를 dirty 로 만들어 UPDATE 를 유도한다.
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    // 시각은 JPA 라이프사이클로 in-VM 설정.
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
