package site.krip.domain.tripmate.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.tripmate.dto.request.CreatePostRequest;
import site.krip.domain.tripmate.dto.request.SaveDraftRequest;
import site.krip.domain.tripmate.dto.request.UpdatePostRequest;
import site.krip.domain.tripmate.dto.response.DraftResponse;
import site.krip.domain.tripmate.dto.response.LikeResponse;
import site.krip.domain.tripmate.dto.response.LikedUsersResponse;
import site.krip.domain.tripmate.dto.response.PostCreateResponse;
import site.krip.domain.tripmate.dto.response.PostDetailResponse;
import site.krip.domain.tripmate.dto.response.PostListResponse;
import site.krip.domain.tripmate.dto.response.ToggleDisplayResponse;
import site.krip.domain.tripmate.service.TripmatePostDraftService;
import site.krip.domain.tripmate.service.TripmatePostLikeService;
import site.krip.domain.tripmate.service.TripmatePostService;
import site.krip.domain.tripmate.service.TripmateSearchHistoryService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

import java.util.Optional;

/**
 * 여행 메이트 게시글 CRUD + 임시저장 + 좋아요. 경로: {@code /api/tripmate/posts}.
 */
@RestController
@RequestMapping("/api/tripmate/posts")
@Validated
public class TripmatePostController {

    private static final Logger log = LoggerFactory.getLogger(TripmatePostController.class);

    private final TripmatePostService postService;
    private final TripmatePostLikeService likeService;
    private final TripmatePostDraftService draftService;
    private final TripmateSearchHistoryService searchHistoryService;

    public TripmatePostController(TripmatePostService postService,
                                  TripmatePostLikeService likeService,
                                  TripmatePostDraftService draftService,
                                  TripmateSearchHistoryService searchHistoryService) {
        this.postService = postService;
        this.likeService = likeService;
        this.draftService = draftService;
        this.searchHistoryService = searchHistoryService;
    }

    // ──────────────────── 게시글 CRUD ────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostCreateResponse createPost(@CurrentUserId String userId,
                                         @Valid @RequestBody CreatePostRequest body) {
        return postService.createPost(userId, body);
    }

    @GetMapping
    public PostListResponse getPosts(@CurrentUserId String userId,
                                     @RequestParam(value = "cursor", required = false) String cursor) {
        return postService.getPosts(cursor, userId);
    }

    @GetMapping("/search")
    public PostListResponse searchPosts(@CurrentUserId String userId,
                                        @RequestParam("keyword")
                                        @NotBlank(message = "검색어를 입력해주세요.")
                                        @Size(max = 100, message = "검색어는 100자 이하여야 합니다.") String keyword,
                                        @RequestParam(value = "cursor", required = false) String cursor) {
        // @NotBlank 로 공백·빈 검색어 거부 (LIKE '%%' 전체조회·빈 검색기록 저장 방지).
        try {
            searchHistoryService.saveSearch(userId, keyword);
        } catch (Exception e) {
            log.warn("검색 기록 저장 실패: user_id={}, keyword={}", userId, keyword);
        }
        return postService.searchPosts(keyword, cursor, userId);
    }

    // ──────────────────── 임시저장 ────────────────────

    @PutMapping("/draft")
    public DraftResponse saveDraft(@CurrentUserId String userId, @Valid @RequestBody SaveDraftRequest body) {
        return DraftResponse.from(draftService.saveDraft(userId, body));
    }

    @GetMapping("/draft")
    public Optional<DraftResponse> getDraft(@CurrentUserId String userId) {
        return draftService.getDraft(userId).map(DraftResponse::from);
    }

    @DeleteMapping("/draft")
    public MessageResponse deleteDraft(@CurrentUserId String userId) {
        draftService.deleteDraft(userId);
        return new MessageResponse("임시저장이 삭제되었습니다.");
    }

    // ──────────────────── 단건/수정/삭제 ────────────────────

    @GetMapping("/{post_id}")
    public PostDetailResponse getPost(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        return postService.getPost(postId, userId);
    }

    @PutMapping("/{post_id}")
    public PostDetailResponse updatePost(@CurrentUserId String userId, @PathVariable("post_id") String postId,
                                         @Valid @RequestBody UpdatePostRequest body) {
        return postService.updatePost(postId, userId, body);
    }

    @DeleteMapping("/{post_id}")
    public MessageResponse deletePost(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        postService.deletePost(postId, userId);
        return new MessageResponse("게시글이 삭제되었습니다.");
    }

    @PatchMapping("/{post_id}/display")
    public ToggleDisplayResponse toggleDisplay(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        boolean displayed = postService.toggleDisplay(postId, userId);
        return new ToggleDisplayResponse(postId, displayed);
    }

    // ──────────────────── 좋아요 ────────────────────

    @PostMapping("/{post_id}/like")
    @ResponseStatus(HttpStatus.CREATED)
    public LikeResponse addLike(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        long likeCount = likeService.addLike(userId, postId);
        return new LikeResponse(postId, likeCount);
    }

    @DeleteMapping("/{post_id}/like")
    public LikeResponse removeLike(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        long likeCount = likeService.removeLike(userId, postId);
        return new LikeResponse(postId, likeCount);
    }

    @GetMapping("/{post_id}/likes")
    public LikedUsersResponse getLikedUsers(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        return new LikedUsersResponse(postId, likeService.getLikedUserIds(userId, postId));
    }
}
