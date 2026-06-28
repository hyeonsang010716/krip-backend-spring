package site.krip.domain.feed.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.feed.dto.response.FeedPopupResponse;
import site.krip.domain.feed.service.FeedPopupService;
import site.krip.global.auth.CurrentUserId;

/**
 * 피드 팝업 — 타 유저 프로필 미리보기 + 최근 9개 피드.
 * 경로: {@code /api/feed/popup/{user_id}}.
 */
@RestController
@RequestMapping("/api/feed/popup")
@RequiredArgsConstructor
public class FeedPopupController {

    private final FeedPopupService popupService;

    @GetMapping("/{user_id}")
    public FeedPopupResponse getPopup(@CurrentUserId String viewerId, @PathVariable("user_id") String userId) {
        return popupService.getPopup(viewerId, userId);
    }
}
