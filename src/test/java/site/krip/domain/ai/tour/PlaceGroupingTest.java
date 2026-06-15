package site.krip.domain.ai.tour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlaceGrouping#computeCaps} — 합 ≤ HARD_CAP(80)·각 그룹 ≥ 1 불변식 검증.
 */
class PlaceGroupingTest {

    private static final int HARD_CAP = 80;

    private static final List<String> STYLES = List.of(
            "food_tour", "shopping", "culture_history", "photo_aesthetic",
            "healing", "famous_attractions", "activity", "festival_event");

    private static int sum(Map<String, Integer> caps) {
        return caps.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    @DisplayName("스타일 없으면 BASE_CAPS 합(67) 그대로 — 스케일 안 함")
    void baseCapsUnscaled() {
        Map<String, Integer> caps = PlaceGrouping.computeCaps(List.of());
        assertThat(sum(caps)).isEqualTo(67);
        assertThat(caps).containsEntry("meal", 25);
    }

    @Test
    @DisplayName("초과 케이스 [shopping, photo_aesthetic] → 합 ≤ 80 (round 올림 보정)")
    void overshootCaseTrimmedToCap() {
        Map<String, Integer> caps = PlaceGrouping.computeCaps(List.of("shopping", "photo_aesthetic"));
        assertThat(sum(caps)).isLessThanOrEqualTo(HARD_CAP);
        assertThat(caps.values()).allSatisfy(v -> assertThat(v).isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("모든 스타일 조합(2^8)에서 합 ≤ 80 이고 각 그룹 ≥ 1")
    void allCombinationsRespectCap() {
        int n = STYLES.size();
        for (int mask = 0; mask < (1 << n); mask++) {
            List<String> combo = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    combo.add(STYLES.get(i));
                }
            }
            Map<String, Integer> caps = PlaceGrouping.computeCaps(combo);
            assertThat(sum(caps)).as("styles=%s", combo).isLessThanOrEqualTo(HARD_CAP);
            assertThat(caps.values()).as("styles=%s", combo)
                    .allSatisfy(v -> assertThat(v).isGreaterThanOrEqualTo(1));
        }
    }
}
