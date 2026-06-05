package site.krip.domain.notification.document;

import com.fasterxml.jackson.annotation.JsonValue;

/** deep link 라우팅 + cascade 정리 키. Mongo/JSON 에는 value(소문자) 저장. */
public enum TargetType {
    FEED_POST("feed_post"),
    TRIPMATE_POST("tripmate_post");

    private final String value;

    TargetType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static TargetType from(String value) {
        for (TargetType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 대상 타입: " + value);
    }
}
