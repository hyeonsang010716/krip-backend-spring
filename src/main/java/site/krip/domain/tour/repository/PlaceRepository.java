package site.krip.domain.tour.repository;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import site.krip.domain.tour.document.Place;
import site.krip.global.common.exception.ApiException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 장소 조회 레포지토리 (MongoDB).
 *
 * <p>거리순 조회는 {@code $geoNear} 집계(2dsphere 인덱스 필요)로 한다. 파이프라인 단계 순서는
 * $geoNear → $sort(distance,place_id) → $match(커서 이후) → $limit. 커서는
 * {@code "distance:place_id"} 형식이며, 동일 거리 문서가 잘리지 않도록 minDistance epsilon 을 둔다.
 */
@Repository
public class PlaceRepository {

    /** 장소 조회 페이지 크기. */
    public static final int PAGE_SIZE = 30;

    private static final double CURSOR_EPSILON = 1e-2;

    private final MongoTemplate mongo;

    public PlaceRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ──────────────────── place_id 배치/단건 조회 ────────────────────

    /** place_id 목록으로 장소 배치 조회. 빈 입력이면 빈 리스트. */
    public List<Place> findByPlaceIds(Collection<String> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return List.of();
        }
        return mongo.find(Query.query(Criteria.where("place_id").in(placeIds)), Place.class);
    }

    /** place_id 단건 조회. */
    public Optional<Place> findByPlaceId(String placeId) {
        return Optional.ofNullable(
                mongo.findOne(Query.query(Criteria.where("place_id").is(placeId)), Place.class));
    }

    // ──────────────────── 거리순 조회 ────────────────────

    /** 현재 위치 기준 가까운 장소 (거리순, PAGE_SIZE). */
    public List<NearbyPlace> findNearby(double lat, double lng, String cursor, Double maxDistance) {
        return aggregateNearby(lat, lng, null, cursor, maxDistance);
    }

    /** 키워드 검색(display_name/category ILIKE) + 거리순. */
    public List<NearbyPlace> searchNearby(double lat, double lng, String keyword,
                                          String cursor, Double maxDistance) {
        String escaped = escapeRegex(keyword);
        Document query = new Document("$or", List.of(
                new Document("display_name", new Document("$regex", escaped).append("$options", "i")),
                new Document("category", new Document("$regex", escaped).append("$options", "i"))
        ));
        return aggregateNearby(lat, lng, query, cursor, maxDistance);
    }

    private List<NearbyPlace> aggregateNearby(double lat, double lng, Document query,
                                              String cursor, Double maxDistance) {
        Double cursorDistance = null;
        String cursorPlaceId = null;
        if (cursor != null && !cursor.isBlank()) {
            // cursor 형식: "distance:place_id". 콜론 없음/비숫자 distance 는 잘못된 클라이언트 입력 → 400.
            int idx = cursor.indexOf(':');
            if (idx < 0) {
                throw new ApiException(400, "cursor 형식이 올바르지 않습니다.");
            }
            try {
                cursorDistance = Double.parseDouble(cursor.substring(0, idx));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "cursor 형식이 올바르지 않습니다.");
            }
            cursorPlaceId = cursor.substring(idx + 1);
        }

        // ── $geoNear ──
        Document geoNear = new Document()
                .append("near", new Document("type", "Point").append("coordinates", List.of(lng, lat)))
                .append("distanceField", "distance")
                .append("spherical", true);
        if (query != null) {
            geoNear.append("query", query);
        }
        if (maxDistance != null) {
            geoNear.append("maxDistance", maxDistance);
        }
        if (cursorDistance != null) {
            geoNear.append("minDistance", Math.max(0, cursorDistance - CURSOR_EPSILON));
        }

        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(ctx -> new Document("$geoNear", geoNear));
        // 동일 거리 내 place_id 오름차순 — 정렬 안정성
        ops.add(ctx -> new Document("$sort", new Document("distance", 1).append("place_id", 1)));
        if (cursorDistance != null) {
            Document match = new Document("$or", List.of(
                    new Document("distance", new Document("$gt", cursorDistance)),
                    new Document("distance", cursorDistance)
                            .append("place_id", new Document("$gt", cursorPlaceId))
            ));
            ops.add(ctx -> new Document("$match", match));
        }
        ops.add(ctx -> new Document("$limit", PAGE_SIZE));

        List<Document> raw = mongo.aggregate(
                Aggregation.newAggregation(ops), "place", Document.class).getMappedResults();

        List<NearbyPlace> result = new ArrayList<>(raw.size());
        for (Document d : raw) {
            Place place = mongo.getConverter().read(Place.class, d);
            double distance = d.get("distance") instanceof Number n ? n.doubleValue() : 0.0;
            result.add(new NearbyPlace(place, distance));
        }
        return result;
    }

    /** 다음 페이지 커서 생성 (service 에서 마지막 항목으로 호출). */
    public static String buildCursor(double distance, String placeId) {
        return distance + ":" + placeId;
    }

    /** MongoDB $regex 안전을 위해 정규식 메타문자 escape. */
    private static String escapeRegex(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (char c : s.toCharArray()) {
            if (".^$*+?()[]{}|\\".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 거리순 조회 결과 1건 — 장소 + 집계 산출 거리(미터). */
    public record NearbyPlace(Place place, double distance) {
    }
}
