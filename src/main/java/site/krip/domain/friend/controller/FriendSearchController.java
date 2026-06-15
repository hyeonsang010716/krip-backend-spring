package site.krip.domain.friend.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.friend.dto.response.FriendSearchListResponse;
import site.krip.domain.friend.service.FriendSearchHistoryService;
import site.krip.domain.friend.service.FriendSearchService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.support.LogSafe;

/**
 * 친구 추가 화면 유저 검색.
 * 경로: {@code /api/friend/search}. 첫 페이지(cursor 없음)에서만 검색 기록 저장(best-effort).
 */
@RestController
@RequestMapping("/api/friend/search")
@Validated
public class FriendSearchController {

    private static final Logger log = LoggerFactory.getLogger(FriendSearchController.class);

    private final FriendSearchService searchService;
    private final FriendSearchHistoryService searchHistoryService;

    public FriendSearchController(FriendSearchService searchService,
                                  FriendSearchHistoryService searchHistoryService) {
        this.searchService = searchService;
        this.searchHistoryService = searchHistoryService;
    }

    @GetMapping
    public FriendSearchListResponse searchUsers(@CurrentUserId String viewerId,
                                                @RequestParam("keyword")
                                                @NotBlank(message = "검색어를 입력해주세요.")
                                                @Size(max = 100, message = "검색어는 100자 이하여야 합니다.") String keyword,
                                                @RequestParam(value = "cursor", required = false) String cursor) {
        String normalized = keyword.strip();

        // 검색 기록 저장은 첫 페이지에서만 — 페이지네이션 중복 upsert 방지 (best-effort)
        if (cursor == null || cursor.isBlank()) {
            try {
                searchHistoryService.saveSearch(viewerId, normalized);
            } catch (Exception e) {
                log.warn("검색 기록 저장 실패: user_id={}, keyword={}", viewerId, LogSafe.clean(normalized));
            }
        }

        return searchService.search(viewerId, normalized, cursor);
    }
}
