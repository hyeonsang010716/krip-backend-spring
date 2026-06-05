package site.krip.domain.tour.service;

import org.springframework.stereotype.Service;
import site.krip.domain.tour.document.TourSearchHistory;
import site.krip.domain.tour.dto.response.TourSearchHistoryListResponse;
import site.krip.domain.tour.dto.response.TourSearchHistoryResponse;
import site.krip.domain.tour.repository.TourSearchHistoryRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;

/**
 * 관광 장소 검색 기록 (MongoDB).
 */
@Service
public class TourSearchHistoryService {

    private final TourSearchHistoryRepository searchRepo;

    public TourSearchHistoryService(TourSearchHistoryRepository searchRepo) {
        this.searchRepo = searchRepo;
    }

    /** 검색어 저장 — 동일 검색어는 시각만 갱신, 10개 초과 시 가장 오래된 것 삭제. */
    public void saveSearch(String userId, String searchName) {
        searchRepo.save(userId, searchName);
    }

    public TourSearchHistoryListResponse getSearchHistories(String userId) {
        List<TourSearchHistoryResponse> histories = searchRepo.findByUserId(userId).stream()
                .map(TourSearchHistoryResponse::from)
                .toList();
        return new TourSearchHistoryListResponse(histories);
    }

    public void deleteSearch(String userId, String searchName) {
        if (searchName == null || searchName.isBlank()) {
            throw new ApiException(400, "검색어를 입력해주세요.");
        }
        searchRepo.deleteOne(userId, searchName);
    }

    public void deleteAllSearches(String userId) {
        searchRepo.deleteAllByUserId(userId);
    }
}
