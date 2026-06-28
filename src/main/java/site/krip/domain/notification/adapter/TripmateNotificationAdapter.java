package site.krip.domain.notification.adapter;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import site.krip.domain.notification.document.TargetType;
import site.krip.domain.notification.service.InboxService;
import site.krip.domain.tripmate.port.TripmateNotificationPort;

/**
 * tripmate 의 {@link TripmateNotificationPort} 실제 구현 — 좋아요 알림 fan-out + 게시글 삭제 cascade.
 */
@Component
@RequiredArgsConstructor
public class TripmateNotificationAdapter implements TripmateNotificationPort {

    private final InboxService inboxService;

    @Override
    public void notifyTripmateLike(String recipientId, String actorId, String actorName,
                                   @Nullable String actorProfileImageUrl, String postId, String postPreview) {
        inboxService.notifyTripmateLike(recipientId, actorId, actorName, actorProfileImageUrl, postId, postPreview);
    }

    @Override
    public void cascadePostDeleted(String postId) {
        inboxService.cascadePostDeleted(TargetType.TRIPMATE_POST.getValue(), postId);
    }
}
