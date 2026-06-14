package site.krip.domain.ai.tour;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 후보 장소를 6개 카테고리 그룹으로 분류 + 그룹별 균형 cap 계산.
 *
 * <p>FastAPI {@code tour_planner.v2.category} 와 1:1 동기화. 후보 풀이 식당 편향에 빠지지 않도록
 * 식사·관광·카페·쇼핑·야간·체험을 고르게 채운다. 분류 우선순위:
 * nightlife &gt; cafe &gt; meal &gt; attraction &gt; activity &gt; shopping &gt; other.
 */
public final class PlaceGrouping {

    private PlaceGrouping() {
    }

    public static final String GROUP_MEAL = "meal";
    public static final String GROUP_CAFE = "cafe";
    public static final String GROUP_ATTRACTION = "attraction";
    public static final String GROUP_SHOPPING = "shopping";
    public static final String GROUP_NIGHTLIFE = "nightlife";
    public static final String GROUP_ACTIVITY = "activity";
    public static final String GROUP_OTHER = "other";

    /** cap 적용/분배 순서 = BASE_CAPS 삽입 순서와 동일(FastAPI GROUPS). */
    public static final List<String> GROUPS = List.of(
            GROUP_MEAL, GROUP_CAFE, GROUP_ATTRACTION, GROUP_SHOPPING, GROUP_NIGHTLIFE, GROUP_ACTIVITY);

    private static final Set<String> NIGHTLIFE_TYPES = Set.of(
            "bar", "night_club", "wine_bar", "cocktail_bar", "pub", "lounge_bar",
            "brewery", "gastropub", "irish_pub", "sports_bar", "beer_garden",
            "brewpub", "hookah_bar");

    private static final Set<String> CAFE_TYPES = Set.of(
            "cafe", "coffee_shop", "bakery", "dessert_shop", "ice_cream_shop",
            "cake_shop", "tea_house", "brunch_restaurant", "juice_shop", "donut_shop",
            "chocolate_shop", "candy_store", "confectionery", "pastry_shop",
            "coffee_roastery", "tea_store", "bagel_shop", "sandwich_shop",
            "salad_shop", "breakfast_restaurant", "dessert_restaurant");

    private static final Set<String> ATTRACTION_TYPES = Set.of(
            "tourist_attraction", "museum", "art_gallery", "art_museum", "history_museum",
            "cultural_center", "cultural_landmark", "historical_place", "historical_landmark",
            "observation_deck", "place_of_worship", "church", "buddhist_temple",
            "performing_arts_theater", "concert_hall", "auditorium",
            "park", "city_park", "botanical_garden", "hiking_area", "nature_preserve",
            "national_park", "amusement_park", "zoo", "aquarium", "convention_center");

    private static final Set<String> SHOPPING_TYPES = Set.of(
            "clothing_store", "shopping_mall", "cosmetics_store", "jewelry_store",
            "electronics_store", "book_store", "gift_shop", "market",
            "womens_clothing_store", "shoe_store", "sporting_goods_store",
            "sportswear_store", "department_store", "store", "food_store",
            "convenience_store", "supermarket", "grocery_store", "wholesaler",
            "home_goods_store", "liquor_store", "florist", "farmers_market");

    private static final Set<String> ACTIVITY_TYPES = Set.of(
            "karaoke", "amusement_center", "video_arcade", "indoor_playground",
            "playground", "bowling_alley", "sports_activity_location", "sports_complex",
            "sports_club", "live_music_venue", "movie_theater", "event_venue",
            "barbecue_area");

    /** 기본 cap (스타일 무관). */
    private static final Map<String, Integer> BASE_CAPS = Map.of(
            GROUP_MEAL, 25, GROUP_CAFE, 12, GROUP_ATTRACTION, 12,
            GROUP_SHOPPING, 8, GROUP_NIGHTLIFE, 6, GROUP_ACTIVITY, 4);

    /** 스타일별 boost — 다중 선택 시 합산. */
    private static final Map<String, Map<String, Integer>> STYLE_BOOSTS = Map.of(
            "food_tour", Map.of(GROUP_MEAL, 10, GROUP_CAFE, 3),
            "shopping", Map.of(GROUP_SHOPPING, 10),
            "culture_history", Map.of(GROUP_ATTRACTION, 10),
            "photo_aesthetic", Map.of(GROUP_CAFE, 5, GROUP_ATTRACTION, 5),
            "healing", Map.of(GROUP_CAFE, 3, GROUP_ATTRACTION, 5),
            "famous_attractions", Map.of(GROUP_ATTRACTION, 10, GROUP_SHOPPING, 3),
            "activity", Map.of(GROUP_NIGHTLIFE, 3, GROUP_ACTIVITY, 10),
            "festival_event", Map.of(GROUP_ATTRACTION, 5, GROUP_NIGHTLIFE, 3, GROUP_ACTIVITY, 5));

    /** 후보 풀 hard cap (LLM 토큰 부담 방지). */
    private static final int HARD_CAP = 80;

    /** Google Places types 배열 → 6개 그룹 중 하나(or other). 우선순위는 클래스 docstring 참고. */
    public static String classify(List<String> types) {
        Set<String> typeSet = Set.copyOf(types != null ? types : List.of());
        if (intersects(typeSet, NIGHTLIFE_TYPES)) {
            return GROUP_NIGHTLIFE;
        }
        if (intersects(typeSet, CAFE_TYPES)) {
            return GROUP_CAFE;
        }
        for (String t : typeSet) {
            if (t.equals("restaurant") || t.endsWith("_restaurant")) {
                return GROUP_MEAL;
            }
        }
        if (intersects(typeSet, ATTRACTION_TYPES)) {
            return GROUP_ATTRACTION;
        }
        if (intersects(typeSet, ACTIVITY_TYPES)) {
            return GROUP_ACTIVITY;
        }
        if (intersects(typeSet, SHOPPING_TYPES)) {
            return GROUP_SHOPPING;
        }
        return GROUP_OTHER;
    }

    /**
     * 선택된 styles 에 따라 그룹별 cap 계산. 기본 cap + 스타일 boost 합산 후
     * 합이 HARD_CAP 초과 시 비례 축소(각 그룹 최소 1). 반환 맵은 {@link #GROUPS} 순서.
     */
    public static Map<String, Integer> computeCaps(List<String> styles) {
        Map<String, Integer> caps = new LinkedHashMap<>();
        for (String g : GROUPS) {
            caps.put(g, BASE_CAPS.get(g));
        }
        for (String style : styles) {
            Map<String, Integer> boosts = STYLE_BOOSTS.get(style);
            if (boosts != null) {
                boosts.forEach((g, boost) -> caps.merge(g, boost, Integer::sum));
            }
        }

        int total = caps.values().stream().mapToInt(Integer::intValue).sum();
        if (total > HARD_CAP) {
            double scale = (double) HARD_CAP / total;
            caps.replaceAll((g, n) -> Math.max(1, (int) Math.round(n * scale)));
        }
        return caps;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String t : a) {
            if (b.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
