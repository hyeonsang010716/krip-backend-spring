package site.krip.domain.tripmate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.krip.global.support.IdGenerator;

/**
 * 여행 메이트 게시글 첨부 이미지.
 * post_id FK 는 DB CASCADE. 쓰기는 리포지토리로, 읽기는 {@link TripmatePost#getImages()} 로.
 */
@Entity
@Table(name = "tripmate_post_image", indexes = {
        @Index(name = "ix_tripmate_post_image_post_id", columnList = "post_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripmatePostImage {

    @Id
    @Column(name = "image_id", length = 50)
    private String imageId;

    @Column(name = "post_id", length = 50, nullable = false)
    private String postId;

    @Column(name = "image_url", length = 500, nullable = false)
    private String imageUrl;

    @Column(name = "image_order", nullable = false)
    private int imageOrder;

    public TripmatePostImage(String postId, String imageUrl, int imageOrder) {
        this.imageId = IdGenerator.tripmateImageId();
        this.postId = postId;
        this.imageUrl = imageUrl;
        this.imageOrder = imageOrder;
    }
}
