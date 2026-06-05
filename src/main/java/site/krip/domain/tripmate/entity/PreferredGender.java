package site.krip.domain.tripmate.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 선호 성별. DB 이름(MALE), JSON value(male).
 */
public enum PreferredGender {
    MALE("male"),
    FEMALE("female"),
    ANY("any");

    private final String value;

    PreferredGender(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PreferredGender fromValue(String value) {
        for (PreferredGender g : values()) {
            if (g.value.equals(value)) {
                return g;
            }
        }
        throw new IllegalArgumentException("알 수 없는 선호 성별: " + value);
    }
}
