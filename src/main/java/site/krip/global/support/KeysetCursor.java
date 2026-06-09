package site.krip.global.support;

import site.krip.global.common.exception.ApiException;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * keyset 페이지네이션 커서 코덱 — 정렬키(Instant)+id 를 단일 토큰 {@code "<instant>_<id>"} 으로 인코딩한다.
 *
 * <p>토큰에 정렬키를 담아 다음 페이지 조회 시 경계 행을 다시 읽지 않는다. 따라서 경계 행이 동시에 삭제돼도
 * 목록이 잘리지 않는다(재조회 방식의 "행 없음 → 빈 페이지 → 조기 종료" 버그 제거). Instant 표기에는 {@code '_'}
 * 가 없으므로 첫 {@code '_'} 로 안전하게 분리한다(id 에 {@code '_'} 가 있어도 무방).
 */
public final class KeysetCursor {

    private KeysetCursor() {
    }

    /** 정렬키(Instant)+id → 커서 토큰. */
    public static String encode(Instant sortKey, String id) {
        return sortKey.toString() + "_" + id;
    }

    public record Decoded(Instant sortKey, String id) {
    }

    /** 토큰 → (정렬키, id). 형식 오류는 400(InboxService 와 동일 계약). */
    public static Decoded decode(String cursor) {
        int sep = cursor == null ? -1 : cursor.indexOf('_');
        if (sep <= 0 || sep >= cursor.length() - 1) {
            throw ApiException.badRequest("cursor 형식이 올바르지 않습니다.");
        }
        try {
            return new Decoded(Instant.parse(cursor.substring(0, sep)), cursor.substring(sep + 1));
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("cursor 형식이 올바르지 않습니다.");
        }
    }
}
