package site.krip.domain.feed.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.feed.dto.response.FeedPostListResponse;
import site.krip.domain.feed.dto.response.FeedPostResponse;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.exception.FeedNotFoundException;
import site.krip.domain.feed.port.FeedInboxPort;
import site.krip.domain.feed.repository.FeedPostRepository;
import site.krip.domain.feed.repository.FeedPostRow;
import site.krip.domain.feed.service.image.FeedImageProcessor;
import site.krip.domain.feed.service.image.ProcessedFeedImage;
import site.krip.global.common.exception.ApiException;
import site.krip.global.storage.ObjectStorage;
import site.krip.global.storage.StoragePrefix;
import site.krip.global.support.IdGenerator;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 피드 게시물 서비스 — 본인 CRUD + 타 유저 조회.
 *
 * <p>업로드: 이미지 처리(트랜잭션 밖) → S3 업로드 3건(밖) → DB INSERT. 실패 시 prefix cleanup.
 * 삭제: DB 먼저 → S3 best-effort → 인박스 cascade(커밋 후, 롤백된 삭제의 알림 선숨김 race 회피).
 */
@Service
public class FeedPostService {

    private static final Logger log = LoggerFactory.getLogger(FeedPostService.class);

    private final FeedPostRepository feedPostRepo;
    private final FeedAccessService access;
    private final FeedImageProcessor imageProcessor;
    private final ObjectStorage storage;
    private final FeedInboxPort inboxPort;
    private final TransactionTemplate txTemplate;

    public FeedPostService(FeedPostRepository feedPostRepo, FeedAccessService access,
                           FeedImageProcessor imageProcessor, ObjectStorage storage,
                           FeedInboxPort inboxPort, PlatformTransactionManager txManager) {
        this.feedPostRepo = feedPostRepo;
        this.access = access;
        this.imageProcessor = imageProcessor;
        this.storage = storage;
        this.inboxPort = inboxPort;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ──────────────────── 업로드 ────────────────────

    public FeedPostResponse uploadPost(String userId, byte[] fileBytes,
                                       FeedVisibility visibility, String caption) {
        // 이미지 처리 — 트랜잭션/S3 전, 잘못된 이미지 fast-fail(400)
        ProcessedFeedImage processed = imageProcessor.process(fileBytes);

        String postId = IdGenerator.feedPostId();
        String prefix = StoragePrefix.feedPostPrefix(userId, postId);
        String normalizedCaption = normalizeCaption(caption);

        FeedPost saved;
        try {
            String originalUrl = upload(processed.original().data(), processed.original().contentType(),
                    "original." + processed.original().fileExt(), prefix);
            String smallUrl = upload(processed.small().data(), processed.small().contentType(),
                    "small." + processed.small().fileExt(), prefix);
            String mediumUrl = upload(processed.medium().data(), processed.medium().contentType(),
                    "medium." + processed.medium().fileExt(), prefix);
            String fOriginal = originalUrl, fSmall = smallUrl, fMedium = mediumUrl;
            saved = txTemplate.execute(s ->
                    insertPost(postId, userId, visibility, normalizedCaption, fOriginal, fSmall, fMedium));
        } catch (RuntimeException e) {
            safeCleanup(prefix);
            throw e;
        }

        // 신규 업로드 → 카운트 0
        return new FeedPostResponse(
                saved.getPostId(), saved.getUserId(), saved.getVisibility(), saved.getCaption(),
                saved.getOriginalUrl(), saved.getThumbnailSmallUrl(), saved.getThumbnailMediumUrl(),
                0L, 0L, false, saved.getCreatedAt(), saved.getUpdatedAt());
    }

    private FeedPost insertPost(String postId, String userId, FeedVisibility visibility, String caption,
                                String originalUrl, String smallUrl, String mediumUrl) {
        FeedPost post = new FeedPost(postId, userId, visibility, caption, originalUrl, smallUrl, mediumUrl);
        FeedPost saved = feedPostRepo.saveAndFlush(post);
        log.info("피드 게시물 업로드 완료 (user_id={}, post_id={})", userId, postId);
        return saved;
    }

    // ──────────────────── 조회 ────────────────────

    @Transactional(readOnly = true)
    public FeedPostListResponse getMyFeed(String userId, String cursor) {
        List<FeedPostRow> rows = pageRows(userId, List.of(FeedVisibility.values()), userId, cursor);
        return toListResponse(rows);
    }

    @Transactional(readOnly = true)
    public FeedPostResponse getMyPost(String userId, String postId) {
        return FeedPostResponse.from(loadOwnedPost(userId, postId));
    }

    @Transactional(readOnly = true)
    public FeedPostListResponse getUserFeed(String viewerId, String ownerId, String cursor) {
        List<FeedVisibility> visibilities = access.resolveViewerVisibilities(viewerId, ownerId);
        List<FeedPostRow> rows = pageRows(ownerId, visibilities, viewerId, cursor);
        return toListResponse(rows);
    }

    // ──────────────────── 변경 ────────────────────

    @Transactional
    public FeedPostResponse updateVisibility(String userId, String postId, FeedVisibility visibility) {
        FeedPostRow row = loadOwnedPost(userId, postId);
        row.post().changeVisibility(visibility);
        feedPostRepo.flush();
        return FeedPostResponse.from(row);
    }

    @Transactional
    public FeedPostResponse updateCaption(String userId, String postId, String caption) {
        FeedPostRow row = loadOwnedPost(userId, postId);
        row.post().changeCaption(normalizeCaption(caption));
        feedPostRepo.flush();
        return FeedPostResponse.from(row);
    }

    // ──────────────────── 삭제 ────────────────────

    public void deletePost(String userId, String postId) {
        String prefix = txTemplate.execute(s -> deletePostRow(userId, postId));
        try {
            storage.deleteByPathPrefix(prefix);
        } catch (Exception e) {
            log.warn("S3 prefix 삭제 실패 — orphan 잔존 (prefix={}): {}", prefix, e.toString());
        }
        inboxPort.cascadeFeedPostDeleted(postId);
    }

    private String deletePostRow(String userId, String postId) {
        FeedPostRow row = loadOwnedPost(userId, postId);
        FeedPost post = row.post();
        String prefix = StoragePrefix.feedPostPrefix(post.getUserId(), post.getPostId());
        feedPostRepo.delete(post);
        log.info("피드 게시물 삭제 완료 (user_id={}, post_id={})", userId, postId);
        return prefix;
    }

    // ──────────────────── 헬퍼 ────────────────────

    private FeedPostRow loadOwnedPost(String userId, String postId) {
        List<Object[]> rows = feedPostRepo.findRowByPostId(postId, userId);
        if (rows.isEmpty()) {
            throw new FeedNotFoundException("존재하지 않는 게시물입니다.");
        }
        FeedPostRow row = FeedPostRow.fromTuple(rows.get(0));
        if (!row.post().getUserId().equals(userId)) {
            throw new ApiException(403, "게시물에 대한 권한이 없습니다.");
        }
        return row;
    }

    private List<FeedPostRow> pageRows(String ownerId, List<FeedVisibility> visibilities,
                                       String viewerId, String cursor) {
        if (visibilities.isEmpty()) {
            return List.of();
        }
        PageRequest page = PageRequest.of(0, FeedPostRepository.PAGE_SIZE);
        List<Object[]> tuples = cursor == null
                ? feedPostRepo.findByOwnerFirstPage(ownerId, visibilities, viewerId, page)
                : feedPostRepo.findByOwnerAfterCursor(ownerId, visibilities, viewerId, cursor, page);
        return tuples.stream().map(FeedPostRow::fromTuple).toList();
    }

    private static FeedPostListResponse toListResponse(List<FeedPostRow> rows) {
        String nextCursor = rows.size() == FeedPostRepository.PAGE_SIZE
                ? rows.get(rows.size() - 1).post().getPostId() : null;
        return new FeedPostListResponse(rows.stream().map(FeedPostResponse::from).toList(), nextCursor);
    }

    private String upload(byte[] data, String contentType, String fileName, String prefix) {
        return storage.uploadToKey(new ByteArrayInputStream(data), data.length, fileName, contentType, prefix);
    }

    private void safeCleanup(String prefix) {
        try {
            storage.deleteByPathPrefix(prefix);
        } catch (Exception e) {
            log.warn("업로드 실패 cleanup 실패 — orphan 잔존 (prefix={}): {}", prefix, e.toString());
        }
    }

    /** 빈/공백만 → null. 비-빈 캡션의 양끝 공백은 보존. */
    private static String normalizeCaption(String caption) {
        if (caption == null || caption.strip().isEmpty()) {
            return null;
        }
        return caption;
    }
}
