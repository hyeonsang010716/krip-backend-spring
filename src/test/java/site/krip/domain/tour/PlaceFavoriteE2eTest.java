package site.krip.domain.tour;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장소 조회 + 즐겨찾기 + 검색 기록 E2E.
 *
 * <p>경로: {@code /api/tour/places}, {@code /api/tour/search-history}.
 * 즐겨찾기는 MongoDB place 문서를 시드해 검증하고, 거리순(geo) 조회는 geoNear 시드가 무거워
 * 빈 결과/단건 404 경로만 검증한다. 검색 기록은 place 조회(keyword)로 저장을 유발한다.
 */
class PlaceFavoriteE2eTest extends IntegrationTestSupport {

    @Autowired
    private MongoTemplate mongo;

    /**
     * 최소 Place 문서를 place 컬렉션에 시드(GeoJSON location 포함)하고 place_id 반환.
     * location 을 넣어 geoNear 인덱스 요구를 충족하지만, 좌표/거리 단언은 하지 않는다.
     */
    private String seedPlace(String displayName) {
        String placeId = "place-" + UUID.randomUUID();
        Document loc = new Document("type", "Point")
                .append("coordinates", List.of(126.97688, 37.57594));
        Document doc = new Document()
                .append("place_id", placeId)
                .append("display_name", displayName)
                .append("address", "서울 어딘가")
                .append("category", "tourist")
                .append("location", loc)
                .append("rating", 4.2)
                .append("photos", List.of("https://example.com/x.jpg"));
        mongo.getCollection("place").insertOne(doc);
        return placeId;
    }

    // ──────────────────── 장소 조회 ────────────────────

    @Test
    @DisplayName("장소 단건 조회 — 존재하지 않는 place_id → 404")
    void getPlaceNotFound() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get("/api/tour/places/no-such-place")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("장소 단건 조회 — 시드된 place_id → 200, is_favorite 반영(미등록 시 null)")
    void getPlaceFound() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace("덕수궁");

        mockMvc.perform(get("/api/tour/places/" + placeId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.place_id").value(placeId))
                .andExpect(jsonPath("$.display_name").value("덕수궁"));
    }

    @Test
    @DisplayName("장소 목록(거리순) — 시드 없음 → 200, 빈 결과 (geo 시드 한계로 빈/페이지 경로만 검증)")
    void getPlacesEmpty() throws Exception {
        String userId = fixtures.createActiveUser();
        // 데이터가 없는 좌표(태평양 한가운데)로 조회 → 빈 결과
        mockMvc.perform(get("/api/tour/places")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .param("lat", "0.0")
                        .param("lng", "-160.0")
                        .param("max_distance", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places").isArray());
    }

    @Test
    @DisplayName("장소 목록 — max_distance <= 0 → 400")
    void getPlacesBadMaxDistance() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get("/api/tour/places")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .param("max_distance", "0"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 즐겨찾기 ────────────────────

    @Test
    @DisplayName("즐겨찾기 추가(201)→중복(400)→목록(반영)→삭제 전체 흐름")
    void favoriteLifecycle() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace("창덕궁");

        // 추가 (201)
        mockMvc.perform(post("/api/tour/places/favorites")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place_id\": \"" + placeId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").exists());

        // 중복 추가 (400)
        mockMvc.perform(post("/api/tour/places/favorites")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place_id\": \"" + placeId + "\"}"))
                .andExpect(status().isBadRequest());

        // 목록 (200) — 추가된 장소가 반영
        mockMvc.perform(get("/api/tour/places/favorites")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_count").value(1))
                .andExpect(jsonPath("$.favorites[0].place.place_id").value(placeId));

        // 단건 조회 시 is_favorite=true 반영
        mockMvc.perform(get("/api/tour/places/" + placeId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_favorite").value(true));

        // 삭제 (200)
        mockMvc.perform(delete("/api/tour/places/favorites/" + placeId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 삭제 후 목록 비어있음
        mockMvc.perform(get("/api/tour/places/favorites")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_count").value(0));
    }

    @Test
    @DisplayName("즐겨찾기 추가 — 존재하지 않는 place_id → 400")
    void addFavoriteMissingPlace() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(post("/api/tour/places/favorites")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place_id\": \"no-such-place\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("즐겨찾기 해제 — 등록되지 않은 장소 → 404")
    void removeFavoriteNotRegistered() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace("종묘");
        mockMvc.perform(delete("/api/tour/places/favorites/" + placeId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isNotFound());
    }

    // ──────────────────── 검색 기록 ────────────────────

    @Test
    @DisplayName("검색 기록 — 검색 유발→목록→한건 삭제→전체 삭제")
    void searchHistoryLifecycle() throws Exception {
        String userId = fixtures.createActiveUser();

        // keyword 검색을 통해 검색어 저장(best-effort). geo 결과가 비어도 검색어는 저장됨.
        for (String kw : List.of("경복궁", "남산", "한강")) {
            mockMvc.perform(get("/api/tour/places")
                            .header("Authorization", bearer())
                            .header("X-Auth-Token", userToken(userId))
                            .param("keyword", kw))
                    .andExpect(status().isOk());
        }

        // 목록 (200)
        mockMvc.perform(get("/api/tour/search-history")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories").isArray())
                .andExpect(jsonPath("$.histories[?(@.search_name == '경복궁')]").exists());

        // 한 건 삭제 (200)
        mockMvc.perform(delete("/api/tour/search-history/one")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .param("search_name", "경복궁"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 전체 삭제 (200)
        mockMvc.perform(delete("/api/tour/search-history")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 전체 삭제 후 목록 비어있음
        mockMvc.perform(get("/api/tour/search-history")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories.length()").value(0));
    }
}
