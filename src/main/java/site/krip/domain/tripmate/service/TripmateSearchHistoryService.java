package site.krip.domain.tripmate.service;

import org.springframework.stereotype.Service;
import site.krip.domain.tripmate.document.TripmateSearchHistory;
import site.krip.domain.tripmate.repository.TripmateSearchHistoryRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;

/**
 * 여행 메이트 검색 기록.
 */
@Service
public class TripmateSearchHistoryService {

    private final TripmateSearchHistoryRepository searchRepository;

    public TripmateSearchHistoryService(TripmateSearchHistoryRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    /** 검색어 저장 — 동일어는 시간 갱신, 10개 초과 시 가장 오래된 것 자동 삭제. */
    public TripmateSearchHistory saveSearch(String userId, String searchName) {
        return searchRepository.save(userId, searchName);
    }

    public List<TripmateSearchHistory> getSearchHistories(String userId) {
        return searchRepository.findByUserId(userId);
    }

    public void deleteSearch(String userId, String searchName) {
        if (searchName == null || searchName.isBlank()) {
            throw new ApiException(400, "검색어를 입력해주세요.");
        }
        searchRepository.deleteOne(userId, searchName);
    }

    public void deleteAllSearches(String userId) {
        searchRepository.deleteAllByUserId(userId);
    }
}
