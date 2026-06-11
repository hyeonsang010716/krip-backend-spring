package site.krip.domain.feed.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.feed.dto.response.LikeResponse;
import site.krip.domain.feed.dto.response.LikedUsersResponse;
import site.krip.domain.feed.service.FeedPostLikeService;
import site.krip.global.auth.CurrentUserId;

/**
 * 피드 좋아요. 경로: {@code /api/feed/posts/{post_id}/like[s]}.
 * 본인 글 본인 좋아요 허용(인스타와 동일).
 */
@RestController
@RequestMapping("/api/feed/posts")
public class FeedLikeController {

    private final FeedPostLikeService likeService;

    public FeedLikeController(FeedPostLikeService likeService) {
        this.likeService = likeService;
    }

    @PostMapping("/{post_id}/like")
    @ResponseStatus(HttpStatus.CREATED)
    public LikeResponse addLike(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        return new LikeResponse(postId, likeService.addLike(userId, postId));
    }

    @DeleteMapping("/{post_id}/like")
    public LikeResponse removeLike(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        return new LikeResponse(postId, likeService.removeLike(userId, postId));
    }

    @GetMapping("/{post_id}/likes")
    public LikedUsersResponse getLikedUsers(@CurrentUserId String viewerId, @PathVariable("post_id") String postId,
                                            @RequestParam(value = "cursor", required = false) String cursor) {
        return likeService.getLikedUsers(viewerId, postId, cursor);
    }
}
