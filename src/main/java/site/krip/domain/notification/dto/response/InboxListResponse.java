package site.krip.domain.notification.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** 인박스 목록 응답. nextCursor 는 마지막 항목 createdAt 의 ISO 문자열(마지막 페이지면 null). */
public record InboxListResponse(
        List<InboxItemResponse> items,
        @Nullable String nextCursor
) {
}
