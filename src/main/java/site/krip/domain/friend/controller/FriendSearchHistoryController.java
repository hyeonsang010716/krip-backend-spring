package site.krip.domain.friend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.friend.dto.response.FriendSearchHistoryListResponse;
import site.krip.domain.friend.dto.response.FriendSearchHistoryResponse;
import site.krip.domain.friend.service.FriendSearchHistoryService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

import java.util.List;

/**
 * 친구 추가 화면 검색 기록.
 * 경로: {@code /api/friend/search/history}.
 */
@RestController
@RequestMapping("/api/friend/search/history")
@RequiredArgsConstructor
public class FriendSearchHistoryController {

    private final FriendSearchHistoryService searchService;

    @GetMapping
    public FriendSearchHistoryListResponse getSearchHistories(@CurrentUserId String userId) {
        List<FriendSearchHistoryResponse> histories = searchService.getSearchHistories(userId).stream()
                .map(h -> new FriendSearchHistoryResponse(h.getSearchName(), h.getCreatedAt()))
                .toList();
        return new FriendSearchHistoryListResponse(histories);
    }

    @DeleteMapping("/one")
    public MessageResponse deleteSearch(@CurrentUserId String userId,
                                        @RequestParam("search_name") String searchName) {
        searchService.deleteSearch(userId, searchName);
        return new MessageResponse("검색어가 삭제되었습니다.");
    }

    @DeleteMapping
    public MessageResponse deleteAllSearches(@CurrentUserId String userId) {
        searchService.deleteAllSearches(userId);
        return new MessageResponse("검색 기록이 모두 삭제되었습니다.");
    }
}
