package site.krip.domain.feed.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import site.krip.domain.feed.dto.request.UpdateCaptionRequest;
import site.krip.domain.feed.dto.request.UpdateVisibilityRequest;
import site.krip.domain.feed.dto.response.FeedPostListResponse;
import site.krip.domain.feed.dto.response.FeedPostResponse;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.service.FeedPostService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;
import site.krip.global.common.exception.ApiException;
import site.krip.global.common.validation.CodePointSize;
import site.krip.global.common.validation.ImageUploadValidator;

import java.io.IOException;
import java.util.List;

/**
 * 피드 게시물 CRUD + 타 유저 피드. 경로: {@code /api/feed}.
 */
@RestController
@RequestMapping("/api/feed")
@Validated
public class FeedPostController {

    // GIF 제외 (정지 이미지 전용). thumbnail 화이트리스트와 일치 — 라우터 fast-fail.
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private final FeedPostService feedPostService;
    private final ImageUploadValidator imageValidator;

    public FeedPostController(FeedPostService feedPostService, ImageUploadValidator imageValidator) {
        this.feedPostService = feedPostService;
        this.imageValidator = imageValidator;
    }

    @PostMapping(value = "/posts", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedPostResponse upload(@CurrentUserId String userId,
                                   @RequestPart("file") MultipartFile file,
                                   @RequestParam(value = "visibility", defaultValue = "public") FeedVisibility visibility,
                                   @RequestParam(value = "caption", required = false)
                                   @CodePointSize(max = FeedPost.CAPTION_MAX_LENGTH,
                                           message = "캡션은 최대 {max}자까지 가능합니다.") String caption) {
        imageValidator.validate(file, ALLOWED_CONTENT_TYPES, MAX_FILE_SIZE);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("파일을 읽을 수 없습니다.");
        }
        return feedPostService.uploadPost(userId, bytes, visibility, caption);
    }

    @GetMapping("/me")
    public FeedPostListResponse getMyFeed(@CurrentUserId String userId,
                                          @RequestParam(required = false) String cursor) {
        return feedPostService.getMyFeed(userId, cursor);
    }

    @GetMapping("/posts/{post_id}")
    public FeedPostResponse getPost(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        return feedPostService.getMyPost(userId, postId);
    }

    @PatchMapping("/posts/{post_id}/visibility")
    public FeedPostResponse updateVisibility(@CurrentUserId String userId, @PathVariable("post_id") String postId,
                                             @Valid @RequestBody UpdateVisibilityRequest body) {
        return feedPostService.updateVisibility(userId, postId, body.visibility());
    }

    @PatchMapping("/posts/{post_id}/caption")
    public FeedPostResponse updateCaption(@CurrentUserId String userId, @PathVariable("post_id") String postId,
                                          @Valid @RequestBody UpdateCaptionRequest body) {
        return feedPostService.updateCaption(userId, postId, body.caption());
    }

    @DeleteMapping("/posts/{post_id}")
    public MessageResponse deletePost(@CurrentUserId String userId, @PathVariable("post_id") String postId) {
        feedPostService.deletePost(userId, postId);
        return new MessageResponse("피드 게시물이 삭제되었습니다.");
    }

    @GetMapping("/users/{user_id}")
    public FeedPostListResponse getUserFeed(@CurrentUserId String viewerId, @PathVariable("user_id") String userId,
                                            @RequestParam(required = false) String cursor) {
        return feedPostService.getUserFeed(viewerId, userId, cursor);
    }
}
