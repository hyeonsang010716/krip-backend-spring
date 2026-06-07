package site.krip.domain.notification.adapter;

import org.springframework.stereotype.Component;
import site.krip.domain.auth.port.InboxCascadePort;
import site.krip.domain.notification.service.InboxService;

/**
 * auth 의 {@link InboxCascadePort} 실제 구현 — 회원 탈퇴 시 인박스 hard delete (recipient/actor 매칭).
 */
@Component
public class InboxCascadeAdapter implements InboxCascadePort {

    private final InboxService inboxService;

    public InboxCascadeAdapter(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @Override
    public void cascadeUserWithdrawn(String userId) {
        inboxService.cascadeUserWithdrawn(userId);
    }
}
