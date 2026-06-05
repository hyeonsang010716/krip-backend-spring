package site.krip.domain.chat.entity;

/** 채팅 메시지 종류 — MongoDB 에는 value(소문자)로 저장. */
public enum MessageType {
    TEXT("text"),
    IMAGE("image"),
    FILE("file"),
    SYSTEM("system");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MessageType from(String value) {
        if (value == null) {
            return TEXT;
        }
        for (MessageType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 메시지 종류: " + value);
    }
}
