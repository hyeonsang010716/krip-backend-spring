package site.krip.domain.tour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * 여행 플랜의 카드(여행지) 1건.
 *
 * <p>{@code place_id} 는 MongoDB Place 참조(FK 제약 없음). {@code display_name}/{@code address} 는
 * Place 가 사라져도 카드가 깨지지 않도록 보관하는 스냅샷이며, rating/photos 는 라이브 조회한다.
 */
@Entity
@Table(name = "tour_plan_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_tour_plan_item_position",
                columnNames = {"plan_id", "day_number", "position"}),
        indexes = @Index(name = "ix_tour_plan_item_place_id", columnList = "place_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TourPlanItem {

    @Id
    @Column(name = "item_id", length = 50)
    private String itemId;

    @Column(name = "plan_id", length = 50, nullable = false)
    private String planId;

    @Column(name = "day_number", nullable = false)
    private int dayNumber;

    @Column(name = "position", nullable = false)
    private double position;

    @Column(name = "place_id", length = 255, nullable = false)
    private String placeId;

    @Column(name = "display_name", length = 255, nullable = false)
    private String displayName;

    @Column(name = "address", length = 500, nullable = false)
    private String address;

    @Column(name = "visit_time", length = 5)
    private String visitTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 낙관적 락 — 같은 카드의 동시 교체/이동 lost-update 를 차단(충돌 시 409). position 경합 재시도와는 독립. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public TourPlanItem(String planId, int dayNumber, double position, String placeId,
                        String displayName, String address, String visitTime) {
        this.itemId = IdGenerator.tourPlanItemId();
        this.planId = planId;
        this.dayNumber = dayNumber;
        this.position = position;
        this.placeId = placeId;
        this.displayName = displayName;
        this.address = address;
        this.visitTime = visitTime;
    }

    /** 카드 교체 (PUT) — place 스냅샷 + visit_time 일괄 갱신. day/position 은 move 로만 변경. */
    public void replace(String placeId, String displayName, String address, String visitTime) {
        this.placeId = placeId;
        this.displayName = displayName;
        this.address = address;
        this.visitTime = visitTime;
    }

    /** 카드 이동 — 대상 day/position 으로 재배치. */
    public void moveTo(int dayNumber, double position) {
        this.dayNumber = dayNumber;
        this.position = position;
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
