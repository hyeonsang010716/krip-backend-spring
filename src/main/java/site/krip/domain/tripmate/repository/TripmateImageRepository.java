package site.krip.domain.tripmate.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import site.krip.domain.tripmate.document.TripmateImage;

import java.util.List;
import java.util.Optional;

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

    public Optional<TripmateImage> findByImageId(String imageId) {
        return Optional.ofNullable(
                mongo.findOne(Query.query(Criteria.where("image_id").is(imageId)), TripmateImage.class));
    }

    public void deleteByImageId(String imageId) {
        mongo.remove(Query.query(Criteria.where("image_id").is(imageId)), TripmateImage.class);
    }

    public void deleteByImageIds(List<String> imageIds) {
        if (imageIds.isEmpty()) {
            return;
        }
        mongo.remove(Query.query(Criteria.where("image_id").in(imageIds)), TripmateImage.class);
    }

    public void deleteByUrls(List<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return;
        }
        mongo.remove(Query.query(Criteria.where("image_url").in(imageUrls)), TripmateImage.class);
    }

    public void deleteByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("user_id").is(userId)), TripmateImage.class);
    }
}
