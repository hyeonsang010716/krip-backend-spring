package site.krip.domain.auth.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 회원 탈퇴 요청 — 30일 유예 후 영구 삭제 대상. 유저당 1건({@code user_id} unique),
 * {@code scheduled_purge_at} 도달 시 KST 04:00 스케줄러가 hard-delete.
 */
@Document(collection = "withdrawal_request")
public class WithdrawalRequest {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("user_id")
    private String userId;

    @Field("requested_at")
    private Instant requestedAt;

    @Indexed
    @Field("scheduled_purge_at")
    private Instant scheduledPurgeAt;

    protected WithdrawalRequest() {
    }

    public WithdrawalRequest(String userId, Instant requestedAt, Instant scheduledPurgeAt) {
        this.userId = userId;
        this.requestedAt = requestedAt;
        this.scheduledPurgeAt = scheduledPurgeAt;
    }

    public String getUserId() {
        return userId;
    }
}
