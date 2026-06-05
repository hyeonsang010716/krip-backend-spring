package site.krip.domain.tripmate.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 동행 타입. DB 이름(FRIEND), JSON value(friend).
 */
public enum CompanionType {
    FRIEND("friend"),
    FAMILY("family"),
    COUPLE("couple"),
    SOLE("sole");

    private final String value;

    CompanionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CompanionType fromValue(String value) {
        for (CompanionType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 동행 타입: " + value);
    }
}
