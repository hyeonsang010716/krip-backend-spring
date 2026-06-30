package site.krip.domain.notification.fcm;

import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FcmClient 순수 헬퍼 단위 테스트 — 실제 발송(Firebase 자격증명 필요)은 통합 테스트에서 비활성이므로,
 * multicast 분할 경계와 무효 토큰 판정만 순수 함수로 검증한다.
 */
@DisplayName("FCM 클라이언트 — 배치 분할·토큰 무효 판정·서킷 반영")
class FcmClientTest {

    @Nested
    @DisplayName("partition — 500 개 분할 (sendEachForMulticast 하드 제한)")
    class Partition {

        private static List<String> tokens(int n) {
            return IntStream.range(0, n).mapToObj(i -> "t" + i).toList();
        }

        @Test
        @DisplayName("500 이하면 단일 배치")
        void singleBatchWhenUnderLimit() {
            assertThat(FcmClient.partition(tokens(1), FcmClient.MAX_MULTICAST_BATCH)).hasSize(1);
            assertThat(FcmClient.partition(tokens(500), FcmClient.MAX_MULTICAST_BATCH)).hasSize(1);
        }

        @Test
        @DisplayName("501 개는 500 + 1 두 배치로 분할되고 모든 배치가 500 이하")
        void splitsJustOverLimit() {
            List<List<String>> chunks = FcmClient.partition(tokens(501), FcmClient.MAX_MULTICAST_BATCH);
            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0)).hasSize(500);
            assertThat(chunks.get(1)).hasSize(1);
            assertThat(chunks).allSatisfy(c -> assertThat(c.size()).isLessThanOrEqualTo(500));
        }

        @Test
        @DisplayName("1250 개 분할: 모든 배치 ≤500, 합쳐서 원본과 순서·내용 동일")
        void splitsLargeListLosslessly() {
            List<String> all = tokens(1250);
            List<List<String>> chunks = FcmClient.partition(all, FcmClient.MAX_MULTICAST_BATCH);

            assertThat(chunks).hasSize(3);
            assertThat(chunks).allSatisfy(c -> assertThat(c.size()).isLessThanOrEqualTo(500));

            List<String> flattened = new ArrayList<>();
            chunks.forEach(flattened::addAll);
            assertThat(flattened).isEqualTo(all);
        }

        @Test
        @DisplayName("빈 목록은 배치 없음")
        void emptyListNoChunks() {
            assertThat(FcmClient.partition(List.of(), FcmClient.MAX_MULTICAST_BATCH)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isPermanentlyInvalid — 영구 무효 토큰 판정")
    class InvalidClassification {

        @Test
        @DisplayName("UNREGISTERED·SENDER_ID_MISMATCH 는 배치 성공 여부와 무관하게 삭제 대상")
        void unambiguousAlwaysDeletable() {
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.UNREGISTERED, true)).isTrue();
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.UNREGISTERED, false)).isTrue();
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.SENDER_ID_MISMATCH, true)).isTrue();
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.SENDER_ID_MISMATCH, false)).isTrue();
        }

        @Test
        @DisplayName("INVALID_ARGUMENT 는 배치에 성공이 있을 때만 삭제(전건 실패면 페이로드 결함 의심 → 보존)")
        void invalidArgumentGuardedBySuccess() {
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.INVALID_ARGUMENT, true)).isTrue();
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.INVALID_ARGUMENT, false)).isFalse();
        }

        @Test
        @DisplayName("일시적/그 외 오류는 보존")
        void transientCodesNotDeletable() {
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.QUOTA_EXCEEDED, true)).isFalse();
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.UNAVAILABLE, true)).isFalse();
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.INTERNAL, true)).isFalse();
            assertThat(FcmClient.isPermanentlyInvalid(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR, true)).isFalse();
            assertThat(FcmClient.isPermanentlyInvalid(null, true)).isFalse();
        }
    }

    @Nested
    @DisplayName("isTokenLevelError — FCM 건강과 무관한 토큰/클라이언트 오류 판정")
    class TokenLevelClassification {

        @Test
        @DisplayName("UNREGISTERED·SENDER_ID_MISMATCH·INVALID_ARGUMENT 는 토큰 계열(서킷 미반영)")
        void tokenLevelCodes() {
            assertThat(FcmClient.isTokenLevelError(MessagingErrorCode.UNREGISTERED)).isTrue();
            assertThat(FcmClient.isTokenLevelError(MessagingErrorCode.SENDER_ID_MISMATCH)).isTrue();
            assertThat(FcmClient.isTokenLevelError(MessagingErrorCode.INVALID_ARGUMENT)).isTrue();
        }

        @Test
        @DisplayName("서버/전송 계열·미상 코드는 FCM 열화 신호(토큰 계열 아님)")
        void serverAndUnknownCodes() {
            assertThat(FcmClient.isTokenLevelError(MessagingErrorCode.UNAVAILABLE)).isFalse();
            assertThat(FcmClient.isTokenLevelError(MessagingErrorCode.INTERNAL)).isFalse();
            assertThat(FcmClient.isTokenLevelError(MessagingErrorCode.QUOTA_EXCEEDED)).isFalse();
            assertThat(FcmClient.isTokenLevelError(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)).isFalse();
            assertThat(FcmClient.isTokenLevelError(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("recordBatchOutcome — 건별 결과로 서킷 판정 (호출 성공 ≠ 전송 성공)")
    class BatchOutcome {

        @Test
        @DisplayName("성공 0건 + 서버/전송 계열 실패가 임계치만큼 반복되면 서킷 open")
        void serverFailuresOpenCircuit() {
            FcmCircuitBreaker cb = new FcmCircuitBreaker(3, 60_000);
            FcmClient.recordBatchOutcome(cb, 0, true, true);
            FcmClient.recordBatchOutcome(cb, 0, true, true);
            assertThat(cb.tryAcquire()).isTrue(); // 아직 임계치 미만
            FcmClient.recordBatchOutcome(cb, 0, true, true);
            assertThat(cb.tryAcquire()).isFalse(); // 3연속 FCM 열화 → open
        }

        @Test
        @DisplayName("성공 0건이라도 전건 토큰 무효(UNREGISTERED 등)면 서킷에 실패로 반영 안 함 — 멀쩡한 FCM 보호")
        void allTokenInvalidNeverOpensCircuit() {
            FcmCircuitBreaker cb = new FcmCircuitBreaker(3, 60_000);
            for (int i = 0; i < 10; i++) {
                FcmClient.recordBatchOutcome(cb, 0, true, false); // 응답은 있으나 전건 토큰 무효
            }
            assertThat(cb.tryAcquire()).isTrue(); // 아무리 반복해도 open 되지 않음
        }

        @Test
        @DisplayName("성공 1건이라도 있으면 close + 실패 카운트 리셋")
        void anySuccessResetsCircuit() {
            FcmCircuitBreaker cb = new FcmCircuitBreaker(3, 60_000);
            FcmClient.recordBatchOutcome(cb, 0, true, true);
            FcmClient.recordBatchOutcome(cb, 0, true, true);
            FcmClient.recordBatchOutcome(cb, 3, true, true); // 일부 성공 → 리셋
            FcmClient.recordBatchOutcome(cb, 0, true, true);
            FcmClient.recordBatchOutcome(cb, 0, true, true);
            assertThat(cb.tryAcquire()).isTrue(); // 리셋 덕분에 아직 3연속 아님
        }

        @Test
        @DisplayName("응답이 없는 배치는 실패로 기록하지 않는다(no-op)")
        void emptyBatchDoesNotRecordFailure() {
            FcmCircuitBreaker cb = new FcmCircuitBreaker(1, 60_000);
            FcmClient.recordBatchOutcome(cb, 0, false, false);
            assertThat(cb.tryAcquire()).isTrue();
        }
    }
}
