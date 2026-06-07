package site.krip.domain.tripmate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.tripmate.dto.request.CreatePostRequest;
import site.krip.domain.tripmate.dto.request.UpdatePostRequest;
import site.krip.domain.tripmate.dto.response.AuthorResponse;
import site.krip.domain.tripmate.dto.response.PostCreateResponse;
import site.krip.domain.tripmate.dto.response.PostDetailResponse;
import site.krip.domain.tripmate.dto.response.PostListResponse;
import site.krip.domain.tripmate.entity.TripmatePost;
import site.krip.domain.tripmate.entity.TripmatePostImage;
import site.krip.domain.tripmate.exception.PostAccessDeniedException;
import site.krip.domain.tripmate.exception.PostNotFoundException;
import site.krip.domain.tripmate.port.TripmateNotificationPort;
import site.krip.domain.tripmate.repository.TripmateImageRepository;
import site.krip.domain.tripmate.repository.TripmatePostImageRepository;
import site.krip.domain.tripmate.repository.TripmatePostLikeRepository;
import site.krip.domain.tripmate.repository.TripmatePostRepository;
import site.krip.global.storage.ObjectStorage;
import site.krip.global.support.AfterCommit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 여행 메이트 게시글.
 *
 * <p>목록/검색은 (created_at, post_id) 커서 페이지네이션 30개. like_count·is_liked 는
 * 페이지 단위로 일괄 집계해 N+1 을 피한다. 스토리지 정리·인박스 cascade 는 커밋 후 수행한다.
 */
@Service
public class TripmatePostService {

    private static final Logger log = LoggerFactory.getLogger(TripmatePostService.class);
    private static final int PAGE_SIZE = 30;
    private static final Sort PAGE_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("postId"));

    private final TripmatePostRepository postRepository;
    private final TripmatePostImageRepository postImageRepository;
    private final TripmatePostLikeRepository likeRepository;
    private final UserDetailInformRepository detailRepository;
    private final TripmatePostDraftService draftService;
    private final TripmateImageRepository mongoImageRepository;
    private final TripmateImageOwnershipValidator imageOwnershipValidator;
    private final TripmatePostAccessGuard accessGuard;
    private final ObjectStorage storage;
    private final TripmateNotificationPort notificationPort;
    private final TransactionTemplate txTemplate;

    public TripmatePostService(TripmatePostRepository postRepository,
                               TripmatePostImageRepository postImageRepository,
                               TripmatePostLikeRepository likeRepository,
                               UserDetailInformRepository detailRepository,
                               TripmatePostDraftService draftService,
                               TripmateImageRepository mongoImageRepository,
                               TripmateImageOwnershipValidator imageOwnershipValidator,
                               TripmatePostAccessGuard accessGuard,
                               ObjectStorage storage,
                               TripmateNotificationPort notificationPort,
                               TransactionTemplate txTemplate) {
        this.postRepository = postRepository;
        this.postImageRepository = postImageRepository;
        this.likeRepository = likeRepository;
        this.detailRepository = detailRepository;
        this.draftService = draftService;
        this.mongoImageRepository = mongoImageRepository;
        this.imageOwnershipValidator = imageOwnershipValidator;
        this.accessGuard = accessGuard;
        this.storage = storage;
        this.notificationPort = notificationPort;
        this.txTemplate = txTemplate;
    }

    // ──────────────────── 생성 ────────────────────

    @Transactional
    public PostCreateResponse createPost(String userId, CreatePostRequest req) {
        List<String> savedUrls = req.imageUrls() == null ? List.of() : req.imageUrls();
        imageOwnershipValidator.verify(userId, savedUrls);

        TripmatePost post = new TripmatePost(
                userId, req.title(), req.content(), req.preferredAgeMin(), req.preferredAgeMax(),
                req.preferredGender(), req.region(), req.travelStartDate(), req.travelEndDate(),
                req.companionType());
        // ID 를 직접 부여하므로 save() 는 merge 로 동작 → 반환된 managed 인스턴스를 사용해야
        // @PrePersist 로 채워진 시각이 생성 응답에 반영된다.
        post = postRepository.saveAndFlush(post);

        if (!savedUrls.isEmpty()) {
            postImageRepository.saveAll(toImageEntities(post.getPostId(), savedUrls));
        }

        // 발행 성공 → 임시저장 삭제 (실패해도 생성은 유지)
        try {
            draftService.deleteDraft(userId);
        } catch (Exception e) {
            log.warn("임시저장 삭제 실패 (user_id={})", userId, e);
        }

        UserDetailInform detail = detailRepository.findById(userId).orElse(null);
        return new PostCreateResponse(
                post.getPostId(), post.getUserId(), post.getTitle(), post.getContent(),
                post.getPreferredAgeMin(), post.getPreferredAgeMax(), post.getPreferredGender(),
                post.getRegion(), post.getTravelStartDate(), post.getTravelEndDate(),
                post.getCompanionType(), post.isDisplayed(), post.getCreatedAt(), post.getUpdatedAt(),
                savedUrls, detail != null ? detail.getProfileImageUrl() : null);
    }

    // ──────────────────── 단건/목록/검색 조회 ────────────────────

    @Transactional(readOnly = true)
    public PostDetailResponse getPost(String postId, String userId) {
        TripmatePost post = postRepository.findByIdWithUserDetail(postId)
                .orElseThrow(PostNotFoundException::new);
        accessGuard.verifyViewable(post, userId);
        long likeCount = likeRepository.countByPostId(postId);
        boolean liked = userId != null && likeRepository.existsByUserIdAndPostId(userId, postId);
        return toDetailResponse(post, likeCount, liked);
    }

    @Transactional(readOnly = true)
    public PostListResponse getPosts(String cursor, String userId) {
        List<TripmatePost> posts = fetchDisplayedPage(cursor, userId);
        return buildListResponse(posts, userId);
    }

    @Transactional(readOnly = true)
    public PostListResponse searchPosts(String keyword, String cursor, String userId) {
        String pattern = escapeLike(keyword);
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);
        List<TripmatePost> posts;
        if (cursor == null || cursor.isBlank()) {
            posts = postRepository.searchFirstPage(pattern, userId, pageable);
        } else {
            Instant cursorAt = postRepository.findCreatedAt(cursor).orElse(null);
            posts = (cursorAt == null) ? List.of()
                    : postRepository.searchAfterCursor(pattern, cursorAt, cursor, userId, pageable);
        }
        return buildListResponse(posts, userId);
    }

    // ──────────────────── 수정 ────────────────────

    @Transactional
    public PostDetailResponse updatePost(String postId, String userId, UpdatePostRequest req) {
        TripmatePost post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
        if (!post.getUserId().equals(userId)) {
            throw new PostAccessDeniedException("게시글 수정 권한이 없습니다.");
        }

        post.update(req.title(), req.content(), req.preferredAgeMin(), req.preferredAgeMax(),
                req.preferredGender(), req.region(), req.travelStartDate(), req.travelEndDate(),
                req.companionType());

        List<String> newUrls = req.imageUrls() == null ? List.of() : req.imageUrls();
        imageOwnershipValidator.verify(userId, newUrls);

        Set<String> oldUrls = new HashSet<>();
        postImageRepository.findByPostIdOrderByImageOrderAsc(postId)
                .forEach(img -> oldUrls.add(img.getImageUrl()));

        postImageRepository.deleteByPostId(postId);
        if (!newUrls.isEmpty()) {
            postImageRepository.saveAll(toImageEntities(postId, newUrls));
        }

        // 제거된 이미지 → 커밋 이후 Object Storage + MongoDB 정리 (롤백 시 참조 중인 객체 삭제 방지).
        Set<String> removed = new HashSet<>(oldUrls);
        removed.removeAll(new HashSet<>(newUrls));
        if (!removed.isEmpty()) {
            List<String> removedList = new ArrayList<>(removed);
            AfterCommit.run(() -> {
                try {
                    storage.deleteMany(removedList);
                    mongoImageRepository.deleteByUserIdAndUrls(userId, removedList);
                } catch (Exception e) {
                    log.warn("수정 시 이미지 정리 실패 (post_id={})", postId, e);
                }
            });
        }

        // flush → @PreUpdate 가 updated_at 을 즉시 갱신해 응답에 반영.
        postRepository.flush();

        UserDetailInform detail = detailRepository.findById(userId).orElse(null);
        long likeCount = likeRepository.countByPostId(postId);
        boolean liked = likeRepository.existsByUserIdAndPostId(userId, postId);
        return new PostDetailResponse(
                post.getPostId(), post.getUserId(), AuthorResponse.from(detail),
                post.getTitle(), post.getContent(), post.getPreferredAgeMin(), post.getPreferredAgeMax(),
                post.getPreferredGender(), post.getRegion(), post.getTravelStartDate(),
                post.getTravelEndDate(), post.getCompanionType(), post.isDisplayed(),
                post.getCreatedAt(), post.getUpdatedAt(), likeCount, liked, newUrls,
                detail != null ? detail.getProfileImageUrl() : null);
    }

    // ──────────────────── 삭제 ────────────────────

    public void deletePost(String postId, String userId) {
        txTemplate.executeWithoutResult(status -> deletePostTx(postId, userId));
    }

    private void deletePostTx(String postId, String userId) {
        TripmatePost post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
        if (!post.getUserId().equals(userId)) {
            throw new PostAccessDeniedException("게시글 삭제 권한이 없습니다.");
        }

        List<String> imageUrls = postImageRepository.findByPostIdOrderByImageOrderAsc(postId)
                .stream().map(TripmatePostImage::getImageUrl).toList();

        postRepository.delete(post); // DB CASCADE → 이미지·좋아요 자동 삭제

        AfterCommit.run(() -> {
            if (!imageUrls.isEmpty()) {
                try {
                    storage.deleteMany(imageUrls);
                    mongoImageRepository.deleteByUserIdAndUrls(userId, imageUrls);
                } catch (Exception e) {
                    log.warn("삭제 시 이미지 정리 실패 (post_id={})", postId, e);
                }
            }
            notificationPort.cascadePostDeleted(postId);
        });
    }

    // ──────────────────── Display 토글 ────────────────────

    @Transactional
    public boolean toggleDisplay(String postId, String userId) {
        TripmatePost post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
        if (!post.getUserId().equals(userId)) {
            throw new PostAccessDeniedException("게시글 표시 상태 변경 권한이 없습니다.");
        }
        return post.toggleDisplay();
    }

    // ──────────────────── 내부 유틸 ────────────────────

    private List<TripmatePostImage> toImageEntities(String postId, List<String> urls) {
        List<TripmatePostImage> images = new ArrayList<>(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            images.add(new TripmatePostImage(postId, urls.get(i), i));
        }
        return images;
    }

    private List<TripmatePost> fetchDisplayedPage(String cursor, String viewerId) {
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);
        if (cursor == null || cursor.isBlank()) {
            return postRepository.findDisplayedFirstPage(viewerId, pageable);
        }
        Instant cursorAt = postRepository.findCreatedAt(cursor).orElse(null);
        if (cursorAt == null) {
            return List.of();
        }
        return postRepository.findDisplayedAfterCursor(cursorAt, cursor, viewerId, pageable);
    }

    private PostListResponse buildListResponse(List<TripmatePost> posts, String userId) {
        if (posts.isEmpty()) {
            return new PostListResponse(List.of(), null);
        }
        List<String> ids = posts.stream().map(TripmatePost::getPostId).toList();

        Map<String, Long> counts = new HashMap<>();
        likeRepository.countByPostIds(ids).forEach(c -> counts.put(c.getPostId(), c.getCnt()));

        Set<String> liked = (userId == null) ? Set.of()
                : new HashSet<>(likeRepository.findLikedPostIds(userId, ids));

        List<PostDetailResponse> dtos = posts.stream()
                .map(p -> toDetailResponse(p, counts.getOrDefault(p.getPostId(), 0L),
                        liked.contains(p.getPostId())))
                .toList();

        String nextCursor = posts.size() == PAGE_SIZE ? posts.get(posts.size() - 1).getPostId() : null;
        return new PostListResponse(dtos, nextCursor);
    }

    private PostDetailResponse toDetailResponse(TripmatePost post, long likeCount, boolean liked) {
        UserDetailInform detail = (post.getUser() != null) ? post.getUser().getDetail() : null;
        List<String> imageUrls = post.getImages().stream()
                .map(TripmatePostImage::getImageUrl).toList();
        return new PostDetailResponse(
                post.getPostId(), post.getUserId(), AuthorResponse.from(detail),
                post.getTitle(), post.getContent(), post.getPreferredAgeMin(), post.getPreferredAgeMax(),
                post.getPreferredGender(), post.getRegion(), post.getTravelStartDate(),
                post.getTravelEndDate(), post.getCompanionType(), post.isDisplayed(),
                post.getCreatedAt(), post.getUpdatedAt(), likeCount, liked, imageUrls,
                detail != null ? detail.getProfileImageUrl() : null);
    }

    /** SQL LIKE 메타문자 이스케이프 (escape '!'). */
    private String escapeLike(String keyword) {
        String escaped = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
