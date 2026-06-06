package site.krip.domain.friend.service;

import org.springframework.stereotype.Service;
import site.krip.domain.friend.document.FriendSearchHistory;
import site.krip.domain.friend.repository.FriendSearchHistoryRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;

/**
 * 친구 추가 화면 검색 기록.
 */
@Service
public class FriendSearchHistoryService {

    private final FriendSearchHistoryRepository searchRepository;

    public FriendSearchHistoryService(FriendSearchHistoryRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    public FriendSearchHistory saveSearch(String userId, String searchName) {
        return searchRepository.save(userId, searchName);
    }

    public List<FriendSearchHistory> getSearchHistories(String userId) {
        return searchRepository.findByUserId(userId);
    }

    public void deleteSearch(String userId, String searchName) {
        if (searchName == null || searchName.isBlank()) {
            throw ApiException.badRequest("검색어를 입력해주세요.");
        }
        searchRepository.deleteOne(userId, searchName);
    }

    public void deleteAllSearches(String userId) {
        searchRepository.deleteAllByUserId(userId);
    }
}
