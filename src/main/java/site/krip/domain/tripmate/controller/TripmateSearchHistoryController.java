package site.krip.domain.tripmate.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.tripmate.dto.response.SearchHistoryListResponse;
import site.krip.domain.tripmate.dto.response.SearchHistoryResponse;
import site.krip.domain.tripmate.service.TripmateSearchHistoryService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

import java.util.List;

/**
 * 여행 메이트 검색 기록. 경로: {@code /api/tripmate/search-history}.
 */
@RestController
@RequestMapping("/api/tripmate/search-history")
@RequiredArgsConstructor
public class TripmateSearchHistoryController {

    private final TripmateSearchHistoryService searchService;

    @GetMapping
    public SearchHistoryListResponse getSearchHistories(@CurrentUserId String userId) {
        List<SearchHistoryResponse> histories = searchService.getSearchHistories(userId).stream()
                .map(h -> new SearchHistoryResponse(h.getSearchName(), h.getCreatedAt()))
                .toList();
        return new SearchHistoryListResponse(histories);
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
