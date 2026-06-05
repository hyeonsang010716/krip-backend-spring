package site.krip.domain.friend.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 친구 관계 상태. DB 이름(PENDING), JSON value(pending).
 */
public enum FriendshipStatus {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    private final String value;

    FriendshipStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static FriendshipStatus fromValue(String value) {
        for (FriendshipStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("알 수 없는 친구 관계 상태: " + value);
    }
}
