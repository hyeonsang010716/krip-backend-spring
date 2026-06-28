package site.krip.domain.notification.service;

import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import site.krip.domain.notification.document.InboxItem;
import site.krip.domain.notification.dto.response.InboxItemResponse;
import site.krip.domain.notification.dto.response.InboxListResponse;
import site.krip.domain.notification.exception.InboxItemNotFoundException;
import site.krip.domain.notification.repository.InboxRepository;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.IsoTimestamp;
import site.krip.global.support.TextPreview;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 인박스 fan-out + 조회/hide + cascade.
 *
 * <p>fan-out 은 caller 의 트랜잭션 밖 best-effort — dedup 충돌은 멱등 skip,
 * 그 외 예외는 로그만(사용자 응답 정상). 본인→본인 항목 skip.
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);
    private static final int COMMENT_PREVIEW_MAX_LENGTH = 100;

    private final InboxRepository repo;

    public InboxService(InboxRepository repo) {
        this.repo = repo;
    }

    // ──────────────────── fan-out ────────────────────

    public void notifyFeedLike(String recipientId, String actorId, String actorName,
                               @Nullable String actorProfileImageUrl, String postId, String postPreview) {
        if (recipientId.equals(actorId)) {
            return;
        }
        safeInsert(InboxItem.feedLike(recipientId, actorId, actorName, actorProfileImageUrl, postId, postPreview));
    }

    public void notifyFeedComment(String recipientId, String actorId, String actorName,
                                  @Nullable String actorProfileImageUrl, String postId, String postPreview,
                                  String commentId, String commentContent) {
        if (recipientId.equals(actorId)) {
            return;
        }
        safeInsert(InboxItem.feedComment(recipientId, actorId, actorName, actorProfileImageUrl,
                postId, postPreview, commentId, truncateComment(commentContent)));
    }

    public void notifyTripmateLike(String recipientId, String actorId, String actorName,
                                   @Nullable String actorProfileImageUrl, String postId, String postPreview) {
        if (recipientId.equals(actorId)) {
            return;
        }
        safeInsert(InboxItem.tripmateLike(recipientId, actorId, actorName, actorProfileImageUrl, postId, postPreview));
    }

    // ──────────────────── 조회 / hide ────────────────────

    public InboxListResponse listItems(String recipientId, String cursor, boolean markAsRead) {
        InboxCursor parsed = parseCursor(cursor);
        List<InboxItem> items = repo.findByRecipient(
                recipientId, parsed.ts(), parsed.id(), InboxRepository.PAGE_SIZE);
        boolean hasMore = items.size() > InboxRepository.PAGE_SIZE;
        if (hasMore) {
            items = items.subList(0, InboxRepository.PAGE_SIZE);
        }
        String nextCursor = (hasMore && !items.isEmpty())
                ? encodeCursor(items.get(items.size() - 1)) : null;
        // 읽음 처리 전 상태로 응답(클라가 "방금 본 항목" 강조 가능).
        InboxListResponse response = new InboxListResponse(
                items.stream().map(InboxItemResponse::from).toList(), nextCursor);

        // 실제로 반환한(=사용자가 본) 항목만 읽음 처리 — unread 가 본 것과 일치, 안 본 다음 페이지는 미읽음 유지.
        if (markAsRead && !items.isEmpty()) {
            try {
                List<ObjectId> ids = items.stream().map(item -> new ObjectId(item.getId())).toList();
                long modified = repo.markReadByIds(recipientId, ids);
                if (modified > 0) {
                    log.info("인박스 페이지 읽음 처리 (recipient_id={}, count={})", recipientId, modified);
                }
            } catch (Exception e) {
                log.warn("인박스 페이지 읽음 처리 실패 (recipient_id={})", recipientId, e);
            }
        }
        return response;
    }

    public int countUnread(String recipientId) {
        long raw = repo.countUnread(recipientId, InboxRepository.UNREAD_COUNT_CAP);
        return (int) Math.min(raw, InboxRepository.UNREAD_COUNT_CAP);
    }

    public void hideItem(String recipientId, String inboxItemId) {
        ObjectId oid;
        try {
            oid = new ObjectId(inboxItemId);
        } catch (IllegalArgumentException e) {
            throw new InboxItemNotFoundException("존재하지 않는 인박스 항목입니다.");
        }
        if (!repo.hide(oid, recipientId)) {
            throw new InboxItemNotFoundException("존재하지 않는 인박스 항목입니다.");
        }
        log.info("인박스 항목 hide (recipient_id={}, inbox_item_id={})", recipientId, inboxItemId);
    }

    // ──────────────────── cascade ────────────────────

    public void cascadePostDeleted(String targetTypeValue, String targetId) {
        try {
            long modified = repo.hideByTarget(targetTypeValue, targetId);
            if (modified > 0) {
                log.info("인박스 cascade post_deleted (target_type={}, target_id={}, modified={})",
                        targetTypeValue, targetId, modified);
            }
        } catch (Exception e) {
            log.warn("인박스 cascade post_deleted 실패 (target_id={})", targetId, e);
        }
    }

    public void cascadeUserWithdrawn(String userId) {
        try {
            long deleted = repo.deleteByUser(userId);
            if (deleted > 0) {
                log.info("인박스 cascade user_withdrawn (user_id={}, deleted={})", userId, deleted);
            }
        } catch (Exception e) {
            log.warn("인박스 cascade user_withdrawn 실패 (user_id={})", userId, e);
        }
    }

    // ──────────────────── 헬퍼 ────────────────────

    /** 커서 = {@code {isoCreatedAt}_{objectIdHex}}. created_at·_id 둘 다 ISO/hex 라 '_' 가 안전한 구분자. */
    private static String encodeCursor(InboxItem last) {
        return IsoTimestamp.format(last.getCreatedAt()) + "_" + last.getId();
    }

    /** null/blank=첫 페이지. '_' 없으면 timestamp-only 구 커서로 하위호환(created_at &lt; ts). 손상 시 400. */
    private static InboxCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new InboxCursor(null, null);
        }
        int sep = cursor.indexOf('_');
        try {
            if (sep < 0) {
                return new InboxCursor(Instant.parse(cursor), null);
            }
            Instant ts = Instant.parse(cursor.substring(0, sep));
            ObjectId id = new ObjectId(cursor.substring(sep + 1));
            return new InboxCursor(ts, id);
        } catch (Exception e) {
            throw ApiException.badRequest("cursor 형식이 올바르지 않습니다.");
        }
    }

    private record InboxCursor(@Nullable Instant ts, @Nullable ObjectId id) {
    }

    private void safeInsert(InboxItem item) {
        try {
            repo.upsert(item);
        } catch (DuplicateKeyException e) {
            // 동시 fan-out 경합 — 다른 스레드가 이미 insert/bump. 멱등 skip.
        } catch (Exception e) {
            log.warn("인박스 fan-out 실패", e);
        }
    }

    private static String truncateComment(String content) {
        // content 는 비-null(댓글은 항상 내용 있음)이라 truncate 결과도 비-null.
        return Objects.requireNonNull(TextPreview.truncate(content, COMMENT_PREVIEW_MAX_LENGTH, "…"));
    }
}
