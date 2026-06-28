package site.krip.domain.feed.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.feed.dto.request.CreateCommentRequest;
import site.krip.domain.feed.dto.response.CommentListResponse;
import site.krip.domain.feed.dto.response.CommentResponse;
import site.krip.domain.feed.service.FeedPostCommentService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 피드 댓글. 경로: {@code /api/feed/posts/{post_id}/comments}.
 */
@RestController
@RequestMapping("/api/feed/posts")
@RequiredArgsConstructor
public class FeedCommentController {

    private final FeedPostCommentService commentService;

    @PostMapping("/{post_id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse createComment(@CurrentUserId String userId, @PathVariable("post_id") String postId,
                                         @Valid @RequestBody CreateCommentRequest body) {
        return commentService.createComment(userId, postId, body.content());
    }

    @GetMapping("/{post_id}/comments")
    public CommentListResponse listComments(@CurrentUserId String userId, @PathVariable("post_id") String postId,
                                            @RequestParam(required = false) String cursor) {
        return commentService.listComments(userId, postId, cursor);
    }

    @DeleteMapping("/{post_id}/comments/{comment_id}")
    public MessageResponse deleteComment(@CurrentUserId String userId, @PathVariable("post_id") String postId,
                                         @PathVariable("comment_id") String commentId) {
        commentService.deleteComment(userId, postId, commentId);
        return new MessageResponse("댓글이 삭제되었습니다.");
    }
}
