package site.krip.domain.tripmate.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import site.krip.domain.tripmate.document.TripmateImage;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 업로드 이미지 메타데이터 MongoDB 접근.
 */
@Repository
public class TripmateImageRepository {

    private final MongoTemplate mongo;

    public TripmateImageRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public TripmateImage save(TripmateImage image) {
        return mongo.insert(image);
    }

    public List<TripmateImage> findByUserId(String userId) {
        Query query = Query.query(Criteria.where("user_id").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "timestamp"));
        return mongo.find(query, TripmateImage.class);
    }

    public void deleteByImageIds(List<String> imageIds) {
        if (imageIds.isEmpty()) {
            return;
        }
        mongo.remove(Query.query(Criteria.where("image_id").in(imageIds)), TripmateImage.class);
    }

    /** 주어진 URL 중 해당 유저 소유만 추려 반환 (소유권 검증용). */
    public Set<String> findOwnedUrls(String userId, Collection<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return Set.of();
        }
        Query query = Query.query(
                Criteria.where("user_id").is(userId).and("image_url").in(imageUrls));
        query.fields().include("image_url").exclude("_id");
        return mongo.find(query, TripmateImage.class).stream()
                .map(TripmateImage::getImageUrl)
                .collect(Collectors.toSet());
    }

    /** 본인 소유 이미지에 한해 URL 로 삭제 (타인 이미지 교차 삭제 방지). */
    public void deleteByUserIdAndUrls(String userId, List<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return;
        }
        mongo.remove(Query.query(
                        Criteria.where("user_id").is(userId).and("image_url").in(imageUrls)),
                TripmateImage.class);
    }

    public void deleteByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("user_id").is(userId)), TripmateImage.class);
    }
}
