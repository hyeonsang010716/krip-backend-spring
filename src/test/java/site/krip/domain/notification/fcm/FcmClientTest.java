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
}
