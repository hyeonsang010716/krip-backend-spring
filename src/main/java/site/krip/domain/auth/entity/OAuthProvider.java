package site.krip.domain.auth.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** OAuth 제공자 — DB 에는 enum 이름(GOOGLE), JSON 에는 value(google). */
public enum OAuthProvider {
    GOOGLE("google");

    private final String value;

    OAuthProvider(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static OAuthProvider fromValue(String value) {
        for (OAuthProvider p : values()) {
            if (p.value.equals(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 OAuth 제공자: " + value);
    }
}
