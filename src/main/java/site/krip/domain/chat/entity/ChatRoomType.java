package site.krip.domain.chat.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 채팅방 종류 — DB 에는 NAME(DIRECT/GROUP), JSON 에는 value(direct/group). */
public enum ChatRoomType {
    DIRECT("direct"),
    GROUP("group");

    private final String value;

    ChatRoomType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ChatRoomType from(String value) {
        for (ChatRoomType t : values()) {
            if (t.value.equals(value) || t.name().equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 방 종류: " + value);
    }
}
