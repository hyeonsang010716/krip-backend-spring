package site.krip.domain.notification.dto.response;

import java.util.List;

/** 인박스 목록 응답. nextCursor 는 마지막 항목 createdAt 의 ISO 문자열. */
public record InboxListResponse(
        List<InboxItemResponse> items,
        String nextCursor
) {
}
