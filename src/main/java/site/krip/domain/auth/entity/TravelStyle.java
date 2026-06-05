package site.krip.domain.auth.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 여행 스타일 — DB 에는 이름(ACTIVITY), JSON 에는 value(activity). */
public enum TravelStyle {
    ACTIVITY("activity"),
    FAMOUS_ATTRACTIONS("famous_attractions"),
    HEALING("healing"),
    CULTURE_HISTORY("culture_history"),
    SHOPPING("shopping"),
    FOOD_TOUR("food_tour"),
    PHOTO_AESTHETIC("photo_aesthetic"),
    FESTIVAL_EVENT("festival_event"),
    NATURE("nature"),
    TRADITIONAL("traditional"),
    TREKKING("trekking"),
    HIDDEN_GEMS("hidden_gems"),
    ART_EXHIBITION("art_exhibition"),
    THEME_PARK("theme_park"),

    FOOD_HALAL("food_halal"),
    FOOD_VEGETARIAN("food_vegetarian"),
    FOODIE("foodie"),
    CAFE_LOVER("cafe_lover"),

    DENSITY_RELAXED("density_relaxed"),
    DENSITY_PACKED("density_packed"),

    BUDGET_SAVING("budget_saving"),
    BUDGET_MODERATE("budget_moderate"),
    BUDGET_PREMIUM("budget_premium"),

    WALKING_LOW("walking_low"),
    WALKING_MEDIUM("walking_medium"),
    WALKING_HIGH("walking_high"),

    TRANSPORT_PUBLIC("transport_public"),
    TRANSPORT_CAR("transport_car"),
    TRANSPORT_TAXI("transport_taxi"),

    COMPANION_INDEPENDENT("companion_independent"),
    COMPANION_TOGETHER("companion_together"),
    COMPANION_FLEXIBLE("companion_flexible"),

    DAYTIME("daytime"),
    NIGHTLIFE("nightlife"),
    NIGHT_VIEW("night_view"),

    COMMUNICATION_HIGH("communication_high"),
    COMMUNICATION_LOW("communication_low"),

    PLANNER("planner"),
    SPONTANEOUS("spontaneous"),
    FOLLOWER("follower");

    private final String value;

    TravelStyle(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TravelStyle fromValue(String value) {
        for (TravelStyle s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("알 수 없는 여행 스타일: " + value);
    }
}
