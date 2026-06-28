package site.krip.domain.feed.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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
import site.krip.global.common.image.ImageProcessor;
import site.krip.global.common.image.ImageUploadExecutor;
import site.krip.global.common.image.ProcessedImageSet;
import site.krip.global.support.KeysetCursor;
import site.krip.global.storage.ObjectStorage;
import site.krip.global.storage.StoragePrefix;
import site.krip.global.support.AfterCommit;
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
@Slf4j
@RequiredArgsConstructor
public class FeedPostService {

    private final FeedPostRepository feedPostRepo;
    private final FeedAccessService access;
    private final ImageProcessor imageProcessor;
    private final ImageUploadExecutor imageUploadExecutor;
    private final ObjectStorage storage;
    private final FeedInboxPort inboxPort;
    private final TransactionTemplate txTemplate;

    // ──────────────────── 업로드 ────────────────────

    public FeedPostResponse uploadPost(String userId, byte[] fileBytes,
                                       FeedVisibility visibility, String caption) {
        String postId = IdGenerator.feedPostId();
        String prefix = StoragePrefix.feedPostPrefix(userId, postId);
        String normalizedCaption = normalizeCaption(caption);

        // 이미지 decode/resize + S3 업로드를 전용 풀에서 처리(요청 스레드 분리·포화 시 429).
        Variants variants = imageUploadExecutor.process(() -> processAndUpload(fileBytes, prefix));

        FeedPost saved;
        try {
            saved = txTemplate.execute(s -> insertPost(postId, userId, visibility, normalizedCaption,
                    variants.original(), variants.small(), variants.medium()));
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

    /** decode(잘못된 이미지 → 400) 후 3개 variant 를 병렬 업로드. 업로드 도중 실패 시 prefix cleanup. */
    private Variants processAndUpload(byte[] fileBytes, String prefix) {
        ProcessedImageSet processed = imageProcessor.process(fileBytes);
        try {
            List<String> urls = imageUploadExecutor.uploadInParallel(List.of(
                    () -> upload(processed.original().data(), processed.original().contentType(),
                            "original." + processed.original().fileExt(), prefix),
                    () -> upload(processed.small().data(), processed.small().contentType(),
                            "small." + processed.small().fileExt(), prefix),
                    () -> upload(processed.medium().data(), processed.medium().contentType(),
                            "medium." + processed.medium().fileExt(), prefix)));
            return new Variants(urls.get(0), urls.get(1), urls.get(2));
        } catch (RuntimeException e) {
            safeCleanup(prefix);
            throw e;
        }
    }

    private record Variants(String original, String small, String medium) {
    }

    private FeedPost insertPost(String postId, String userId, FeedVisibility visibility, @Nullable String caption,
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
        txTemplate.executeWithoutResult(s -> deletePostRow(userId, postId));
    }

    private void deletePostRow(String userId, String postId) {
        FeedPostRow row = loadOwnedPost(userId, postId);
        FeedPost post = row.post();
        String prefix = StoragePrefix.feedPostPrefix(post.getUserId(), post.getPostId());
        feedPostRepo.delete(post);
        log.info("피드 게시물 삭제 완료 (user_id={}, post_id={})", userId, postId);

        AfterCommit.run(() -> {
            try {
                storage.deleteByPathPrefix(prefix);
            } catch (Exception e) {
                log.warn("S3 prefix 삭제 실패 — orphan 잔존 (prefix={})", prefix, e);
            }
            inboxPort.cascadeFeedPostDeleted(postId);
        });
    }

    // ──────────────────── 헬퍼 ────────────────────

    private FeedPostRow loadOwnedPost(String userId, String postId) {
        FeedPostRow row = feedPostRepo.findRowByPostId(postId, userId)
                .orElseThrow(() -> new FeedNotFoundException("존재하지 않는 게시물입니다."));
        if (!row.post().getUserId().equals(userId)) {
            // 비소유자엔 존재를 숨긴다(404 일원화) — FeedAccessService 의 가시성 정책과 동일. 403 은 존재 오라클.
            throw new FeedNotFoundException("존재하지 않는 게시물입니다.");
        }
        return row;
    }

    private List<FeedPostRow> pageRows(String ownerId, List<FeedVisibility> visibilities,
                                       String viewerId, String cursor) {
        if (visibilities.isEmpty()) {
            return List.of();
        }
        // PAGE_SIZE+1 fetch — 총 개수가 PAGE_SIZE 배수일 때 빈 다음 페이지를 가리키는 phantom 커서 방지.
        PageRequest page = PageRequest.of(0, FeedPostRepository.PAGE_SIZE + 1);
        if (cursor == null || cursor.isBlank()) {
            return feedPostRepo.findByOwnerFirstPage(ownerId, visibilities, viewerId, page);
        }
        KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
        return feedPostRepo.findByOwnerAfterCursor(ownerId, visibilities, viewerId, c.sortKey(), c.id(), page);
    }

    private static FeedPostListResponse toListResponse(List<FeedPostRow> fetched) {
        boolean hasMore = fetched.size() > FeedPostRepository.PAGE_SIZE;
        List<FeedPostRow> rows = hasMore ? fetched.subList(0, FeedPostRepository.PAGE_SIZE) : fetched;
        FeedPostRow last = hasMore ? rows.get(rows.size() - 1) : null;
        String nextCursor = last == null ? null
                : KeysetCursor.encode(last.post().getCreatedAt(), last.post().getPostId());
        return new FeedPostListResponse(rows.stream().map(FeedPostResponse::from).toList(), nextCursor);
    }

    private String upload(byte[] data, String contentType, String fileName, String prefix) {
        return storage.uploadToKey(new ByteArrayInputStream(data), data.length, fileName, contentType, prefix);
    }

    private void safeCleanup(String prefix) {
        try {
            storage.deleteByPathPrefix(prefix);
        } catch (Exception e) {
            log.warn("업로드 실패 cleanup 실패 — orphan 잔존 (prefix={})", prefix, e);
        }
    }

    /** 빈/공백만 → null. 비-빈 캡션의 양끝 공백은 보존. */
    private static @Nullable String normalizeCaption(@Nullable String caption) {
        if (caption == null || caption.strip().isEmpty()) {
            return null;
        }
        return caption;
    }
}
