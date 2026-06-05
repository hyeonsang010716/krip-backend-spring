package site.krip.domain.tour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.krip.global.support.IdGenerator;

import java.time.Instant;

/**
 * 유저 즐겨찾기 장소.
 *
 * <p>User(RDB)와 Place(MongoDB) 간 즐겨찾기 관계. {@code place_id} 는 Mongo Place 참조라 FK 없음.
 * (user_id, place_id) UNIQUE 로 중복 방지.
 */
@Entity
@Table(name = "favorite_place",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_favorite_place",
                columnNames = {"user_id", "place_id"}),
        indexes = {
                @Index(name = "ix_favorite_user_id", columnList = "user_id"),
                @Index(name = "ix_favorite_place_id", columnList = "place_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FavoritePlace {

    @Id
    @Column(name = "favorite_id", length = 50)
    private String favoriteId;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Column(name = "place_id", length = 255, nullable = false)
    private String placeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public FavoritePlace(String userId, String placeId) {
        this.favoriteId = IdGenerator.favoritePlaceId();
        this.userId = userId;
        this.placeId = placeId;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
