package site.krip.domain.tour;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.tour.service.FavoritePlaceService;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장소 목록 <b>비어있지 않은</b> $geoNear 결과 E2E — 기존 {@code PlaceFavoriteE2eTest} 가 geo 시드를 생략해
 * 검증하지 못하던 거리정렬·is_favorite 병합·커서 페이지네이션을 실제 데이터로 검증한다.
 *
 * <p>테스트 간 간섭을 피하려 keyword 정규식(고유 prefix)으로 자기 시드만 조회하고, 서울에서 먼 좌표(10,10)에
 * place 를 심는다. coordinates 는 [lng, lat] 순(GeoJSON).
 */
class TourPlaceListE2eTest extends IntegrationTestSupport {

    private static final String PLACES = "/api/tour/places";
    private static final double LAT = 10.0;
    private static final double LNG = 10.0;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private FavoritePlaceService favoriteService;

    @Autowired
    private ObjectMapper objectMapper;

    /** lng 를 i*0.001 씩 키워 center(10,10)에서 거리순이 i 순서가 되도록 심는다. */
    private void seedPlace(String placeId, String displayName, int i) {
        mongo.getCollection("place").insertOne(new Document()
                .append("_id", placeId)
                .append("place_id", placeId)
                .append("display_name", displayName)
                .append("category", "zzcat")
                .append("location", new Document("type", "Point")
                        .append("coordinates", List.of(LNG + i * 0.001, LAT)))
                .append("rating", 4.5));
    }

    @Test
    @DisplayName("거리순 정렬: 가까운 장소가 먼저, 전부 반환")
    void nearbyOrderedByDistance() throws Exception {
        String userId = fixtures.createActiveUser("place정렬");
        seedPlace("ZZORDER0", "ZZORDER place 0", 0);
        seedPlace("ZZORDER1", "ZZORDER place 1", 1);
        seedPlace("ZZORDER2", "ZZORDER place 2", 2);

        mockMvc.perform(get(PLACES).param("keyword", "ZZORDER")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places.length()").value(3))
                .andExpect(jsonPath("$.places[0].place_id").value("ZZORDER0"))
                .andExpect(jsonPath("$.places[2].place_id").value("ZZORDER2"))
                .andExpect(jsonPath("$.places[0].distance").exists());
    }

    @Test
    @DisplayName("is_favorite 병합: 즐겨찾기한 장소만 true")
    void favoriteMergedIntoList() throws Exception {
        String userId = fixtures.createActiveUser("place즐찾");
        seedPlace("ZZFAV0", "ZZFAV place 0", 0);
        seedPlace("ZZFAV1", "ZZFAV place 1", 1);
        favoriteService.addFavorite(userId, "ZZFAV0"); // 가장 가까운(=[0]) 장소를 즐겨찾기

        mockMvc.perform(get(PLACES).param("keyword", "ZZFAV")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places[0].place_id").value("ZZFAV0"))
                .andExpect(jsonPath("$.places[0].is_favorite").value(true));
    }

    @Test
    @DisplayName("커서 페이지네이션: 첫 페이지 30 + next_cursor, 둘째 페이지 나머지 1 + cursor 없음")
    void cursorPagination() throws Exception {
        String userId = fixtures.createActiveUser("place커서");
        for (int i = 0; i <= 30; i++) { // 31개
            seedPlace(String.format("ZZCUR%02d", i), "ZZCUR place " + i, i);
        }

        MvcResult first = mockMvc.perform(get(PLACES).param("keyword", "ZZCUR")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places.length()").value(30))
                .andExpect(jsonPath("$.next_cursor").exists())
                .andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("next_cursor").asText();

        mockMvc.perform(get(PLACES).param("keyword", "ZZCUR").param("cursor", cursor)
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places.length()").value(1))
                .andExpect(jsonPath("$.places[0].place_id").value("ZZCUR30"))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    @DisplayName("마지막 페이지가 정확히 30개면 next_cursor 없음 (phantom 커서 회귀)")
    void exactlyFullLastPageHasNoCursor() throws Exception {
        String userId = fixtures.createActiveUser("place딱30");
        for (int i = 0; i < 30; i++) { // 정확히 30개
            seedPlace(String.format("ZZEXACT%02d", i), "ZZEXACT place " + i, i);
        }

        mockMvc.perform(get(PLACES).param("keyword", "ZZEXACT")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places.length()").value(30))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    @DisplayName("100자 초과 keyword → 400 (Mongo $regex+geoNear 직행 방지 바운드)")
    void overLongKeywordRejected() throws Exception {
        String userId = fixtures.createActiveUser("place길이");
        String tooLong = "가".repeat(101);

        mockMvc.perform(get(PLACES).param("keyword", tooLong)
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isBadRequest());
    }
}
