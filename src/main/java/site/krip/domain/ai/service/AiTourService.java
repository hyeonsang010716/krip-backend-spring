package site.krip.domain.ai.service;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import site.krip.domain.ai.client.AiServiceClient;
import site.krip.domain.ai.dto.request.TourDayRequest;
import site.krip.domain.ai.dto.request.TourRecommendRequest;
import site.krip.domain.ai.dto.response.TourRecommendResponse;
import site.krip.domain.ai.tour.PlaceGrouping;
import site.krip.domain.ai.tour.TourClusters;
import site.krip.domain.tour.document.Place;
import site.krip.domain.tour.repository.PlaceRepository;
import site.krip.global.common.exception.ApiException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 여행 추천 오케스트레이션.
 *
 * <p>DB·데이터 가공은 전부 여기서: 입력 검증 → cluster 좌표 → $geoNear 후보 검색(음식 필터) →
 * 6그룹 균형 후보 풀 구성 → FastAPI {@code /api/tour/build-plan}(LLM 일정 생성)에 위임 → 응답 반환.
 * FastAPI 는 후보/추가 장소를 받아 추론만 하므로 DB 를 모른다.
 */
@Service
@RequiredArgsConstructor
public class AiTourService {

    /** 검색점당 반경(m)과 raw 후보 수 — 그룹 분류·필터·중복 제거 후에도 cap 을 채울 만큼 넉넉히. */
    private static final double SEARCH_RADIUS_METERS = 2500;
    private static final int SEARCH_LIMIT_PER_POINT = 200;

    /** 좌표 중복 제거 정밀도(소수 4자리 ≈ 11m). */
    private static final double COORD_DEDUP_SCALE = 1e4;

    /** 출발/도착이 모두 cluster 표에 없는 비정상 케이스 fallback — 서울시청. */
    private static final double[] SEOUL_CITY_HALL = {37.5665, 126.9780};

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private static final Set<String> COMPANIONS = Set.of(
            "solo", "couple", "spouse", "friends_colleagues", "family_parents", "family_with_kids");
    private static final Set<String> STYLES = Set.of(
            "activity", "famous_attractions", "healing", "culture_history",
            "shopping", "food_tour", "photo_aesthetic", "festival_event");
    private static final Set<String> DENSITIES = Set.of("relaxed", "packed");
    private static final Set<String> TRANSPORTS = Set.of("public_transport");

    private final PlaceRepository placeRepo;
    private final AiServiceClient ai;

    public TourRecommendResponse recommend(TourRecommendRequest body) {
        validate(body);

        List<DayPayload> dayPayloads = new ArrayList<>();
        for (TourDayRequest day : body.days()) {
            Place fixedPlace = loadFixedPlace(day.additionalPlaceId());
            List<double[]> searchPoints = buildSearchPoints(day, fixedPlace);

            List<List<Place>> searchResults = new ArrayList<>();
            for (double[] point : searchPoints) {
                searchResults.add(placeRepo.findNearbyForPlanner(
                        point[0], point[1], body.foodPreference(), SEARCH_RADIUS_METERS, SEARCH_LIMIT_PER_POINT));
            }

            String additionalPid = fixedPlace != null ? fixedPlace.getPlaceId() : null;
            List<Map<String, Object>> candidates = buildBalancedPool(searchResults, additionalPid, day.styles());

            dayPayloads.add(new DayPayload(
                    toDayInput(day),
                    fixedPlace != null ? placeToMap(fixedPlace, null) : null,
                    candidates));
        }

        BuildPlanPayload payload = new BuildPlanPayload(body.foodPreference(), dayPayloads);
        return ai.postJson("/api/tour/build-plan", payload, TourRecommendResponse.class);
    }

    // ──────────────────── 검증 ────────────────────

    private void validate(TourRecommendRequest body) {
        if (body.days().size() != body.travelDays()) {
            throw ApiException.badRequest(
                    "days 길이(" + body.days().size() + ")가 travel_days(" + body.travelDays() + ")와 다릅니다.");
        }
        for (TourDayRequest day : body.days()) {
            if (!TourClusters.contains(day.departureCluster())) {
                throw ApiException.badRequest("알 수 없는 departure_cluster: " + day.departureCluster());
            }
            if (!TourClusters.contains(day.arrivalCluster())) {
                throw ApiException.badRequest("알 수 없는 arrival_cluster: " + day.arrivalCluster());
            }
            if (!TRANSPORTS.contains(day.transport())) {
                throw ApiException.badRequest("지원하지 않는 transport: " + day.transport());
            }
            if (!COMPANIONS.contains(day.companion())) {
                throw ApiException.badRequest("지원하지 않는 companion: " + day.companion());
            }
            if (!DENSITIES.contains(day.scheduleDensity())) {
                throw ApiException.badRequest("지원하지 않는 schedule_density: " + day.scheduleDensity());
            }
            for (String style : day.styles()) {
                if (!STYLES.contains(style)) {
                    throw ApiException.badRequest("지원하지 않는 style: " + style);
                }
            }
            int start = parseTime(day.startTime());
            int end = parseTime(day.endTime());
            if (start < 0 || end < 0) {
                throw ApiException.badRequest("시간 형식이 올바르지 않습니다 (HH:MM 24h).");
            }
            if (start >= end) {
                throw ApiException.badRequest(
                        "start_time(" + day.startTime() + ")은 end_time(" + day.endTime() + ")보다 빨라야 합니다.");
            }
        }
    }

    /** HH:MM → 분. 형식 위반이면 -1. */
    private static int parseTime(String hhmm) {
        if (hhmm == null || !TIME_PATTERN.matcher(hhmm).matches()) {
            return -1;
        }
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    // ──────────────────── 후보 조회/풀 구성 ────────────────────

    private @Nullable Place loadFixedPlace(String additionalPlaceId) {
        if (additionalPlaceId == null || additionalPlaceId.isBlank()) {
            return null;
        }
        return placeRepo.findByPlaceId(additionalPlaceId)
                .orElseThrow(() -> ApiException.badRequest("additional_place_id not found: " + additionalPlaceId));
    }

    /** 출발/도착 권역 좌표 + 추가 장소 좌표를 검색점으로 합치고 중복 제거. */
    private List<double[]> buildSearchPoints(TourDayRequest day, @Nullable Place fixedPlace) {
        List<double[]> points = new ArrayList<>();
        for (String cluster : List.of(day.departureCluster(), day.arrivalCluster())) {
            double[] coord = TourClusters.get(cluster);
            if (coord != null) {
                points.add(coord);
            }
        }
        if (fixedPlace != null && fixedPlace.getLocation() != null) {
            points.add(new double[]{fixedPlace.getLocation().getLat(), fixedPlace.getLocation().getLng()});
        }
        if (points.isEmpty()) {
            points.add(SEOUL_CITY_HALL);
        }

        Set<Long> seen = new java.util.HashSet<>();
        List<double[]> unique = new ArrayList<>();
        for (double[] p : points) {
            long key = Math.round(p[0] * COORD_DEDUP_SCALE) * 1_000_000L + Math.round(p[1] * COORD_DEDUP_SCALE);
            if (seen.add(key)) {
                unique.add(p);
            }
        }
        return unique;
    }

    /**
     * raw 검색 결과 → 추가 장소 제외 + 중복 제거 + 6그룹 분류 → 그룹별 rating 정렬 → 스타일 가중 cap 적용.
     * 각 후보 map 에 {@code _group} 라벨을 부여한다.
     */
    private List<Map<String, Object>> buildBalancedPool(List<List<Place>> searchResults,
                                                        @Nullable String additionalPid, List<String> styles) {
        Map<String, List<Place>> groups = new LinkedHashMap<>();
        for (String g : PlaceGrouping.GROUPS) {
            groups.put(g, new ArrayList<>());
        }
        Set<String> seenIds = new java.util.HashSet<>();

        for (List<Place> results : searchResults) {
            for (Place place : results) {
                String pid = place.getPlaceId();
                if (pid.equals(additionalPid) || !seenIds.add(pid)) {
                    continue;
                }
                String group = PlaceGrouping.classify(place.getTypes());
                if (group.equals(PlaceGrouping.GROUP_OTHER)) {
                    continue;
                }
                // group 은 OTHER 제외 후라 GROUPS 키 — groups 가 위에서 전부 사전 채움.
                Objects.requireNonNull(groups.get(group)).add(place);
            }
        }

        Comparator<Place> byRating = Comparator
                .comparingDouble((Place p) -> p.getRating() != null ? p.getRating() : 0.0)
                .thenComparingInt(p -> p.getRatingCount() != null ? p.getRatingCount() : 0)
                .reversed();
        groups.values().forEach(list -> list.sort(byRating));

        Map<String, Integer> caps = PlaceGrouping.computeCaps(styles);
        List<Map<String, Object>> pool = new ArrayList<>();
        caps.forEach((group, cap) -> {
            List<Place> list = Objects.requireNonNull(groups.get(group)); // caps 키 ⊆ GROUPS
            for (int i = 0; i < Math.min(cap, list.size()); i++) {
                pool.add(placeToMap(list.get(i), group));
            }
        });
        return pool;
    }

    // ──────────────────── payload 변환 ────────────────────

    /** Place → FastAPI 포맷터가 읽는 dict 형태. {@code group} 이 있으면 후보용으로 {@code _group} 부여. */
    private static Map<String, Object> placeToMap(Place p, @Nullable String group) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("place_id", p.getPlaceId());
        m.put("display_name", p.getDisplayName());
        m.put("category", p.getCategory());
        m.put("address", p.getAddress());
        m.put("short_address", p.getShortAddress());
        m.put("types", p.getTypes());
        m.put("rating", p.getRating());
        m.put("rating_count", p.getRatingCount());
        m.put("price_level", p.getPriceLevel());
        m.put("editorial_summary", p.getEditorialSummary());
        m.put("generative_summary", p.getGenerativeSummary());
        m.put("review_summary", p.getReviewSummary());
        m.put("opening_hours", p.getOpeningHours());
        m.put("photos", p.getPhotos());

        double lng = p.getLocation() != null ? p.getLocation().getLng() : 0.0;
        double lat = p.getLocation() != null ? p.getLocation().getLat() : 0.0;
        m.put("location", Map.of("coordinates", List.of(lng, lat)));

        if (group != null) {
            m.put("_group", group);
        }
        return m;
    }

    private static DayInputPayload toDayInput(TourDayRequest d) {
        return new DayInputPayload(
                d.departureCluster(), d.arrivalCluster(), d.additionalPlaceId(), d.transport(),
                d.startTime(), d.endTime(), d.companion(), d.budgetPerPersonKrw(),
                d.styles(), d.scheduleDensity());
    }

    // ──────────────────── FastAPI 요청 payload (Jackson SNAKE_CASE) ────────────────────

    private record BuildPlanPayload(String foodPreference, List<DayPayload> days) {
    }

    private record DayPayload(DayInputPayload dayInput, @Nullable Map<String, Object> fixedPlace,
                              List<Map<String, Object>> candidatePlaces) {
    }

    private record DayInputPayload(String departureCluster, String arrivalCluster, String additionalPlaceId,
                                   String transport, String startTime, String endTime, String companion,
                                   int budgetPerPersonKrw, List<String> styles, String scheduleDensity) {
    }
}
