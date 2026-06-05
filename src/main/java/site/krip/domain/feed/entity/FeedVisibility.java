package site.krip.domain.feed.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 피드 공개 범위. DB 에는 NAME(PRIVATE/FRIENDS/PUBLIC), JSON 에는 value.
 */
public enum FeedVisibility {
    PRIVATE("private"),   // 본인만
    FRIENDS("friends"),   // ACCEPTED 친구 + 본인
    PUBLIC("public");     // 차단 외 누구나

    private final String value;

    FeedVisibility(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static FeedVisibility from(String value) {
        for (FeedVisibility v : values()) {
            if (v.value.equals(value) || v.name().equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("알 수 없는 공개 범위: " + value);
    }
}
