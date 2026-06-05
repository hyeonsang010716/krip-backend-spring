package site.krip.domain.auth.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 성별 — DB 에는 이름(MALE), JSON 에는 value(male). */
public enum Gender {
    MALE("male"),
    FEMALE("female");

    private final String value;

    Gender(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Gender fromValue(String value) {
        for (Gender g : values()) {
            if (g.value.equals(value)) {
                return g;
            }
        }
        throw new IllegalArgumentException("알 수 없는 성별: " + value);
    }
}
