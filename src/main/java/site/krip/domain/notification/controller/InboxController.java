package site.krip.domain.notification.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.notification.dto.response.InboxListResponse;
import site.krip.domain.notification.dto.response.UnreadCountResponse;
import site.krip.domain.notification.service.InboxService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 인박스. 경로: {@code /api/notification/inbox}.
 * 각 페이지 진입 시 반환한(=사용자가 본) 항목만 자동 read 처리(응답 isRead 는 read 전 상태 유지).
 */
@RestController
@RequestMapping("/api/notification/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InboxService inboxService;

    @GetMapping
    public InboxListResponse list(@CurrentUserId String userId,
                                  @RequestParam(required = false) String cursor) {
        // 매 페이지가 자기 항목만 read 처리 — unread 가 실제로 본 것과 일치(호출당 ≤ PAGE_SIZE 쓰기).
        return inboxService.listItems(userId, cursor, true);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@CurrentUserId String userId) {
        return new UnreadCountResponse(inboxService.countUnread(userId));
    }

    @PatchMapping("/{inbox_item_id}/hide")
    public MessageResponse hide(@CurrentUserId String userId, @PathVariable("inbox_item_id") String inboxItemId) {
        inboxService.hideItem(userId, inboxItemId);
        return new MessageResponse("인박스 항목이 숨겨졌습니다.");
    }
}
