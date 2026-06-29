package site.krip.domain.tour.controller;

import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.tour.dto.response.TourSearchHistoryListResponse;
import site.krip.domain.tour.service.TourSearchHistoryService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 관광 장소 검색 기록. 경로: {@code /api/tour/search-history}.
 */
@RestController
@RequestMapping("/api/tour/search-history")
@RequiredArgsConstructor
@Validated
public class TourSearchHistoryController {

    private final TourSearchHistoryService searchService;

    @GetMapping
    public TourSearchHistoryListResponse getSearchHistories(@CurrentUserId String userId) {
        return searchService.getSearchHistories(userId);
    }

    @DeleteMapping("/one")
    public MessageResponse deleteSearch(@CurrentUserId String userId,
                                        @RequestParam("search_name")
                                        @Size(max = 100, message = "검색어는 100자 이하여야 합니다.") String searchName) {
        searchService.deleteSearch(userId, searchName);
        return new MessageResponse("검색어가 삭제되었습니다.");
    }

    @DeleteMapping
    public MessageResponse deleteAllSearches(@CurrentUserId String userId) {
        searchService.deleteAllSearches(userId);
        return new MessageResponse("검색 기록이 모두 삭제되었습니다.");
    }
}
