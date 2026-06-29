package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import site.krip.global.storage.ObjectStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 외부 리소스(PII) 정리 실패 시 작업 큐(doc) 보존 + 다음 사이클 재시도 검증 — Object Storage 삭제를
 * 첫 호출만 실패시켜, RDB 유저는 hard delete 되더라도 doc 이 남아 영구 누수를 막는지 확인한다.
 */
class WithdrawPurgeRetentionIntegrationTest extends WithdrawPurgeTestSupport {

    @MockitoBean
    private ObjectStorage storage;

    @Test
    @DisplayName("외부 삭제 실패 → 유저는 삭제되나 doc 보존(재시도), 이후 성공 시 doc 제거")
    void retainsWorkItemWhenExternalPurgeFailsThenRetries() {
        String userId = fixtures.createActiveUser("재시도대상");
        withdrawService.requestWithdraw(userId);
        makeDue(userId);

        // 첫 purge 의 Object Storage 삭제만 실패시키고, 두 번째부터는 성공시킨다.
        doThrow(new RuntimeException("S3 down")).doNothing().when(storage).deleteByPrefix(anyString());

        // 1차: RDB 유저는 삭제되지만 외부 실패로 doc 은 보존돼야 한다.
        withdrawService.purge(userId);
        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(withdrawalDocExists(userId)).isTrue();

        // 2차(재시도): NO_USER 경로로 외부 정리만 재수행 → 성공하므로 doc 이 제거된다.
        withdrawService.purge(userId);
        assertThat(withdrawalDocExists(userId)).isFalse();
    }
}
