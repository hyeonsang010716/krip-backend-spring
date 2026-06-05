package site.krip.domain.notification.document;

import com.fasterxml.jackson.annotation.JsonValue;

/** 인박스 항목 종류. Mongo/JSON 에는 value(소문자) 저장. */
public enum InboxItemType {
    FEED_LIKE("feed_like"),
    FEED_COMMENT("feed_comment"),
    TRIPMATE_LIKE("tripmate_like");

    private final String value;

    InboxItemType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static InboxItemType from(String value) {
        for (InboxItemType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 인박스 종류: " + value);
    }
}
