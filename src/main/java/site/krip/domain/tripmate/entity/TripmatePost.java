package site.krip.domain.tripmate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import site.krip.domain.auth.entity.User;
import site.krip.global.support.IdGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 여행 메이트 모집 게시글.
 *
 * <p>{@code userId} 가 FK 를 소유(쓰기)하고 {@code user} 는 읽기 전용 네비게이션이다.
 * {@code images} 도 읽기 전용 — 쓰기는 {@code TripmatePostImageRepository} 로 수행하고
 * 게시글 삭제 시 자식(이미지·좋아요)은 DB FK CASCADE 로 정리된다.
 */
@Entity
@Table(name = "tripmate_post", indexes = {
        @Index(name = "ix_tripmate_post_user_id", columnList = "user_id"),
        @Index(name = "ix_tripmate_post_region", columnList = "region"),
        @Index(name = "ix_tripmate_post_travel_dates", columnList = "travel_start_date, travel_end_date"),
        @Index(name = "ix_tripmate_post_created", columnList = "created_at, post_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripmatePost {

    @Id
    @Column(name = "post_id", length = 50)
    private String postId;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "content", length = 500, nullable = false)
    private String content;

    @Column(name = "preferred_age_min", nullable = false)
    private int preferredAgeMin;

    @Column(name = "preferred_age_max", nullable = false)
    private int preferredAgeMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_gender", nullable = false)
    private PreferredGender preferredGender;

    @Column(name = "region", length = 100, nullable = false)
    private String region;

    @Column(name = "travel_start_date", nullable = false)
    private LocalDate travelStartDate;

    @Column(name = "travel_end_date", nullable = false)
    private LocalDate travelEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "companion_type", nullable = false)
    private CompanionType companionType;

    @Column(name = "is_displayed", nullable = false)
    private boolean displayed = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", insertable = false, updatable = false)
    @OrderBy("imageOrder ASC")
    @BatchSize(size = 30)
    private List<TripmatePostImage> images = new ArrayList<>();

    public TripmatePost(String userId, String title, String content, int preferredAgeMin,
                        int preferredAgeMax, PreferredGender preferredGender, String region,
                        LocalDate travelStartDate, LocalDate travelEndDate, CompanionType companionType) {
        this.postId = IdGenerator.tripmatePostId();
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.preferredAgeMin = preferredAgeMin;
        this.preferredAgeMax = preferredAgeMax;
        this.preferredGender = preferredGender;
        this.region = region;
        this.travelStartDate = travelStartDate;
        this.travelEndDate = travelEndDate;
        this.companionType = companionType;
        this.displayed = true;
    }

    /** 게시글 필드 일괄 수정. */
    public void update(String title, String content, int preferredAgeMin, int preferredAgeMax,
                       PreferredGender preferredGender, String region, LocalDate travelStartDate,
                       LocalDate travelEndDate, CompanionType companionType) {
        this.title = title;
        this.content = content;
        this.preferredAgeMin = preferredAgeMin;
        this.preferredAgeMax = preferredAgeMax;
        this.preferredGender = preferredGender;
        this.region = region;
        this.travelStartDate = travelStartDate;
        this.travelEndDate = travelEndDate;
        this.companionType = companionType;
    }

    /** 표시 여부 토글 → 변경된 값 반환. */
    public boolean toggleDisplay() {
        this.displayed = !this.displayed;
        return this.displayed;
    }

    // 시각은 JPA 라이프사이클로 설정해 생성 응답에 즉시 반영한다.
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
