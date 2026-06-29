package site.krip.domain.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.notification.dto.response.FcmTokenResponse;
import site.krip.domain.notification.entity.FcmToken;
import site.krip.domain.notification.fcm.FcmClient;
import site.krip.domain.notification.repository.FcmTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FcmService#registerToken} 동시성 — reassign UPDATE 가 0행(동시 삭제)이면 insert 로 폴백해
 * 토큰이 무음 유실되지 않는지 검증.
 */
class FcmServiceTest {

    private FcmTokenRepository tokenRepo;
    private TransactionTemplate txTemplate;
    private FcmService service;

    @BeforeEach
    void setUp() {
        tokenRepo = mock(FcmTokenRepository.class);
        txTemplate = mock(TransactionTemplate.class);
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        service = new FcmService(tokenRepo, mock(ChatRoomMemberRepository.class),
                mock(UserQueryPort.class), mock(FcmClient.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), txTemplate);
    }

    @Test
    @DisplayName("reassign 0행(동시 삭제) → insert 폴백, 토큰 무음 유실 없음")
    void zeroRowReassignFallsBackToInsert() {
        FcmToken existing = mock(FcmToken.class);
        when(tokenRepo.findByToken("T")).thenReturn(Optional.of(existing));
        when(tokenRepo.reassignOwner(eq("T"), eq("B"), any())).thenReturn(0);
        FcmToken inserted = mock(FcmToken.class);
        when(inserted.getFcmTokenId()).thenReturn("NEW");
        when(inserted.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(tokenRepo.saveAndFlush(any())).thenReturn(inserted);

        FcmTokenResponse res = service.registerToken("B", "T");

        verify(tokenRepo).saveAndFlush(any(FcmToken.class));
        assertThat(res.fcmTokenId()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("reassign 1행 → 기존 토큰 갱신, insert 안 함")
    void oneRowReassignReusesExisting() {
        FcmToken existing = mock(FcmToken.class);
        when(existing.getFcmTokenId()).thenReturn("OLD");
        when(existing.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(tokenRepo.findByToken("T")).thenReturn(Optional.of(existing));
        when(tokenRepo.reassignOwner(eq("T"), eq("B"), any())).thenReturn(1);

        FcmTokenResponse res = service.registerToken("B", "T");

        verify(tokenRepo, never()).saveAndFlush(any());
        assertThat(res.fcmTokenId()).isEqualTo("OLD");
    }

    @Test
    @DisplayName("기존 토큰 없으면 reassign 없이 insert")
    void noExistingInserts() {
        when(tokenRepo.findByToken("T")).thenReturn(Optional.empty());
        FcmToken inserted = mock(FcmToken.class);
        when(inserted.getFcmTokenId()).thenReturn("NEW");
        when(tokenRepo.saveAndFlush(any())).thenReturn(inserted);

        FcmTokenResponse res = service.registerToken("B", "T");

        verify(tokenRepo, never()).reassignOwner(any(), any(), any());
        verify(tokenRepo).saveAndFlush(any(FcmToken.class));
        assertThat(res.fcmTokenId()).isEqualTo("NEW");
    }
}
