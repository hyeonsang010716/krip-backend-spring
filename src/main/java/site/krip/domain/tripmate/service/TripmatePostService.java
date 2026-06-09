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
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
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
import site.krip.global.support.KeysetCursor;

import java.util.ArrayList;
import java.util.Collection;
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
    /** 작성자 닉네임 부분일치 상한 — 동명 다수 시 검색 작성자 분기를 이 수로 제한. */
    private static final int AUTHOR_NAME_MATCH_LIMIT = 500;
    /** 닉네임 매칭 0건일 때 IN 무매칭용 sentinel (실제 user_id 가 될 수 없는 값). */
    private static final Collection<String> NO_AUTHOR_MATCH = List.of("__no_author_match__");

    private final TripmatePostRepository postRepository;
    private final TripmatePostImageRepository postImageRepository;
    private final TripmatePostLikeRepository likeRepository;
    private final UserQueryPort userQuery;
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
                               UserQueryPort userQuery,
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
        this.userQuery = userQuery;
        this.draftService = draftService;
        this.mongoImageRepository = mongoImageRepository;
        this.imageOwnershipValidator = imageOwnershipValidator;
        this.accessGuard = accessGuard;
        this.storage = storage;
        this.notificationPort = notificationPort;
        this.txTemplate = txTemplate;
    }

    // ──────────────────── 생성 ────────────────────

    public PostCreateResponse createPost(String userId, CreatePostRequest req) {
        List<String> savedUrls = req.imageUrls() == null ? List.of() : req.imageUrls();
        // 소유권 검증(Mongo) 은 RDB 트랜잭션 밖 — 네트워크 왕복 동안 커넥션을 점유하지 않는다.
        imageOwnershipValidator.verify(userId, savedUrls);

        TripmatePost post = txTemplate.execute(s -> {
            // ID 를 직접 부여하므로 save() 는 merge 로 동작 → 반환된 managed 인스턴스를 사용해야
            // @PrePersist 로 채워진 시각이 생성 응답에 반영된다.
            TripmatePost p = postRepository.saveAndFlush(new TripmatePost(
                    userId, req.title(), req.content(), req.preferredAgeMin(), req.preferredAgeMax(),
                    req.preferredGender(), req.region(), req.travelStartDate(), req.travelEndDate(),
                    req.companionType()));
            if (!savedUrls.isEmpty()) {
                postImageRepository.saveAll(toImageEntities(p.getPostId(), savedUrls));
            }
            return p;
        });

        // 발행 성공 → 임시저장 삭제(Mongo) 도 트랜잭션 밖, best-effort (실패해도 생성은 유지)
        try {
            draftService.deleteDraft(userId);
        } catch (Exception e) {
            log.warn("임시저장 삭제 실패 (user_id={})", userId, e);
        }

        UserProfileView author = userQuery.findProfile(userId).orElse(null);
        return new PostCreateResponse(
                post.getPostId(), post.getUserId(), post.getTitle(), post.getContent(),
                post.getPreferredAgeMin(), post.getPreferredAgeMax(), post.getPreferredGender(),
                post.getRegion(), post.getTravelStartDate(), post.getTravelEndDate(),
                post.getCompanionType(), post.isDisplayed(), post.getCreatedAt(), post.getUpdatedAt(),
                savedUrls, author != null ? author.profileImageUrl() : null);
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
        // 작성자 닉네임 분기는 trigram 인덱스로 user_id 를 먼저 해석 → 게시글 쿼리의 OR 를 단일 테이블로 모은다.
        // 동명 부분일치가 한도를 넘으면 일부 작성자는 제외(검색 한정 동작). 빈 결과는 sentinel 로 IN 무매칭 처리.
        List<String> authorIds = userQuery.findUserIdsByNameLike(pattern, AUTHOR_NAME_MATCH_LIMIT);
        Collection<String> authorIdParam = authorIds.isEmpty() ? NO_AUTHOR_MATCH : authorIds;
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);
        List<TripmatePost> posts;
        if (cursor == null || cursor.isBlank()) {
            posts = postRepository.searchFirstPage(pattern, authorIdParam, userId, pageable);
        } else {
            KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
            posts = postRepository.searchAfterCursor(pattern, authorIdParam, c.sortKey(), c.id(), userId, pageable);
        }
        return buildListResponse(posts, userId);
    }

    // ──────────────────── 수정 ────────────────────

    public PostDetailResponse updatePost(String postId, String userId, UpdatePostRequest req) {
        List<String> newUrls = req.imageUrls() == null ? List.of() : req.imageUrls();
        // 소유권 검증(Mongo) 은 RDB 트랜잭션 밖 — 네트워크 왕복 동안 커넥션을 점유하지 않는다.
        // verify 와 작성자 권한 검사 모두 PostAccessDeniedException(403) 이라 순서를 바꿔도 결과는 403 동일.
        imageOwnershipValidator.verify(userId, newUrls);

        TripmatePost post = txTemplate.execute(s -> {
            TripmatePost p = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
            if (!p.getUserId().equals(userId)) {
                throw new PostAccessDeniedException("게시글 수정 권한이 없습니다.");
            }

            p.update(req.title(), req.content(), req.preferredAgeMin(), req.preferredAgeMax(),
                    req.preferredGender(), req.region(), req.travelStartDate(), req.travelEndDate(),
                    req.companionType());

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
                        // S3 삭제 실패분은 메타데이터를 남겨 cleanup 이 재시도(영구 누수 방지).
                        List<String> failed = storage.deleteMany(removedList);
                        List<String> deletable = removedList.stream()
                                .filter(u -> !failed.contains(u))
                                .toList();
                        mongoImageRepository.deleteByUserIdAndUrls(userId, deletable);
                    } catch (Exception e) {
                        log.warn("수정 시 이미지 정리 실패 (post_id={})", postId, e);
                    }
                });
            }

            // flush → @PreUpdate 가 updated_at 을 즉시 갱신해 응답에 반영.
            postRepository.flush();
            return p;
        });

        UserProfileView author = userQuery.findProfile(userId).orElse(null);
        long likeCount = likeRepository.countByPostId(postId);
        boolean liked = likeRepository.existsByUserIdAndPostId(userId, postId);
        return new PostDetailResponse(
                post.getPostId(), post.getUserId(), AuthorResponse.from(author),
                post.getTitle(), post.getContent(), post.getPreferredAgeMin(), post.getPreferredAgeMax(),
                post.getPreferredGender(), post.getRegion(), post.getTravelStartDate(),
                post.getTravelEndDate(), post.getCompanionType(), post.isDisplayed(),
                post.getCreatedAt(), post.getUpdatedAt(), likeCount, liked, newUrls,
                author != null ? author.profileImageUrl() : null);
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
                    // S3 삭제 실패분은 메타데이터를 남겨 cleanup 이 재시도(영구 누수 방지).
                    List<String> failed = storage.deleteMany(imageUrls);
                    List<String> deletable = imageUrls.stream()
                            .filter(u -> !failed.contains(u))
                            .toList();
                    mongoImageRepository.deleteByUserIdAndUrls(userId, deletable);
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
        KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
        return postRepository.findDisplayedAfterCursor(c.sortKey(), c.id(), viewerId, pageable);
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

        TripmatePost last = posts.size() == PAGE_SIZE ? posts.get(posts.size() - 1) : null;
        String nextCursor = last == null ? null : KeysetCursor.encode(last.getCreatedAt(), last.getPostId());
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
