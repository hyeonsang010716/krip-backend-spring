package site.krip.domain.notification.service;

import org.bson.types.ObjectId;
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

import java.time.Instant;
import java.util.List;

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
                               String actorProfileImageUrl, String postId, String postPreview) {
        if (recipientId.equals(actorId)) {
            return;
        }
        safeInsert(InboxItem.feedLike(recipientId, actorId, actorName, actorProfileImageUrl, postId, postPreview));
    }

    public void notifyFeedComment(String recipientId, String actorId, String actorName,
                                  String actorProfileImageUrl, String postId, String postPreview,
                                  String commentId, String commentContent) {
        if (recipientId.equals(actorId)) {
            return;
        }
        safeInsert(InboxItem.feedComment(recipientId, actorId, actorName, actorProfileImageUrl,
                postId, postPreview, commentId, truncateComment(commentContent)));
    }

    public void notifyTripmateLike(String recipientId, String actorId, String actorName,
                                   String actorProfileImageUrl, String postId, String postPreview) {
        if (recipientId.equals(actorId)) {
            return;
        }
        safeInsert(InboxItem.tripmateLike(recipientId, actorId, actorName, actorProfileImageUrl, postId, postPreview));
    }

    // ──────────────────── 조회 / hide ────────────────────

    public InboxListResponse listItems(String recipientId, String cursor, boolean markAsRead) {
        Instant cursorDt = null;
        if (cursor != null) {
            try {
                cursorDt = Instant.parse(cursor);
            } catch (Exception e) {
                throw new ApiException(400, "cursor 형식이 올바르지 않습니다.");
            }
        }
        List<InboxItem> items = repo.findByRecipient(recipientId, cursorDt, InboxRepository.PAGE_SIZE);
        boolean hasMore = items.size() > InboxRepository.PAGE_SIZE;
        if (hasMore) {
            items = items.subList(0, InboxRepository.PAGE_SIZE);
        }
        String nextCursor = (hasMore && !items.isEmpty())
                ? IsoTimestamp.format(items.get(items.size() - 1).getCreatedAt()) : null;
        // 읽음 처리 전 상태로 응답(클라가 "방금 본 항목" 강조 가능).
        InboxListResponse response = new InboxListResponse(
                items.stream().map(InboxItemResponse::from).toList(), nextCursor);

        if (markAsRead) {
            try {
                long modified = repo.markAllRead(recipientId);
                if (modified > 0) {
                    log.info("인박스 자동 읽음 처리 (recipient_id={}, count={})", recipientId, modified);
                }
            } catch (Exception e) {
                log.warn("인박스 자동 읽음 처리 실패 (recipient_id={}): {}", recipientId, e.toString());
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
            log.warn("인박스 cascade post_deleted 실패 (target_id={}): {}", targetId, e.toString());
        }
    }

    public void cascadeUserWithdrawn(String userId) {
        try {
            long deleted = repo.deleteByUser(userId);
            if (deleted > 0) {
                log.info("인박스 cascade user_withdrawn (user_id={}, deleted={})", userId, deleted);
            }
        } catch (Exception e) {
            log.warn("인박스 cascade user_withdrawn 실패 (user_id={}): {}", userId, e.toString());
        }
    }

    // ──────────────────── 헬퍼 ────────────────────

    private void safeInsert(InboxItem item) {
        try {
            repo.insert(item);
        } catch (DuplicateKeyException e) {
            // uq_inbox_dedup — display=true 중복은 멱등 skip.
        } catch (Exception e) {
            log.warn("인박스 fan-out 실패: {}", e.toString());
        }
    }

    private static String truncateComment(String content) {
        if (content.length() <= COMMENT_PREVIEW_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, COMMENT_PREVIEW_MAX_LENGTH) + "…";
    }
}
