package site.krip.domain.notification.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.krip.global.config.FcmProperties;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Firebase Admin SDK 래퍼.
 *
 * <p>자격증명 JSON 이 있으면 초기화하고, 없으면 비활성(no-op)으로 부팅한다 — 푸시는 best-effort 라
 * 미설정 dev 환경에서도 토큰/뮤트/인박스가 정상 동작. 동기 SDK 라 호출측이 트랜잭션 밖 fire-and-forget 로 호출.
 */
@Component
public class FcmClient {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);

    /** {@code sendEachForMulticast} 의 호출당 토큰 상한(Firebase Admin SDK 하드 제한). */
    static final int MAX_MULTICAST_BATCH = 500;

    private final FcmProperties props;
    private volatile FirebaseMessaging messaging;

    public FcmClient(FcmProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (!props.enabled() || props.credentialsPath() == null || props.credentialsPath().isBlank()) {
            log.info("FCM 비활성 (credentials 미설정) — 토큰/뮤트/인박스는 정상, 푸시 발송만 생략");
            return;
        }
        Path path = Path.of(props.credentialsPath());
        if (!Files.exists(path)) {
            log.warn("FCM 자격증명 파일 없음({}) — 푸시 발송 비활성", path);
            return;
        }
        try (FileInputStream in = new FileInputStream(path.toFile())) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options) : FirebaseApp.getInstance();
            this.messaging = FirebaseMessaging.getInstance(app);
            log.info("FCM 초기화 완료 (project_id={})", app.getOptions().getProjectId());
        } catch (Exception e) {
            log.warn("FCM 초기화 실패 — 푸시 발송 비활성: {}", e.toString());
        }
    }

    public boolean isEnabled() {
        return messaging != null;
    }

    /**
     * multicast 발송 — 성공 수 + 무효 토큰 목록 반환. 비활성/글로벌 실패 시 빈 결과.
     *
     * <p>토큰이 {@link #MAX_MULTICAST_BATCH} 를 넘으면 SDK 가 예외를 던지므로 500 개 단위로 분할 발송하고
     * 결과를 합산한다. 배치 단위로 예외를 격리해 한 배치 실패가 나머지 배치 발송을 막지 않게 한다.
     */
    public SendResult sendMulticast(List<String> tokens, String title, String body, Map<String, String> data) {
        if (messaging == null || tokens.isEmpty()) {
            return new SendResult(0, List.of());
        }
        Notification notification = Notification.builder().setTitle(title).setBody(body).build();
        int successCount = 0;
        List<String> invalid = new ArrayList<>();
        for (List<String> batch : partition(tokens, MAX_MULTICAST_BATCH)) {
            successCount += sendBatch(batch, notification, data, invalid);
        }
        return new SendResult(successCount, invalid);
    }

    /** 단일 배치(≤ {@link #MAX_MULTICAST_BATCH}) 발송. 성공 수 반환, 만료 토큰은 {@code invalidOut} 에 누적. */
    private int sendBatch(List<String> tokens, Notification notification, Map<String, String> data,
                          List<String> invalidOut) {
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(notification)
                .putAllData(data)
                .build();
        BatchResponse batch;
        try {
            batch = messaging.sendEachForMulticast(message);
        } catch (FirebaseMessagingException | RuntimeException e) {
            // 글로벌 실패(인증·쿼터 등)는 이 배치만 포기하고 다음 배치를 계속 시도한다.
            log.warn("FCM multicast 배치 실패 (count={}): {}", tokens.size(), e.toString());
            return 0;
        }

        List<SendResponse> responses = batch.getResponses();
        boolean batchHadSuccess = batch.getSuccessCount() > 0;
        int invalidArgHeld = 0;
        for (int i = 0; i < responses.size(); i++) {
            SendResponse r = responses.get(i);
            if (r.isSuccessful()) {
                continue;
            }
            FirebaseMessagingException ex = r.getException();
            MessagingErrorCode code = ex != null ? ex.getMessagingErrorCode() : null;
            if (isPermanentlyInvalid(code, batchHadSuccess)) {
                invalidOut.add(tokens.get(i));
            } else {
                if (code == MessagingErrorCode.INVALID_ARGUMENT) {
                    invalidArgHeld++;
                }
                log.warn("FCM 발송 실패 token_prefix={} error={}",
                        tokens.get(i).substring(0, Math.min(16, tokens.get(i).length())),
                        ex != null ? ex.getMessage() : "unknown");
            }
        }
        if (invalidArgHeld > 0) {
            // 배치 전건이 INVALID_ARGUMENT — 토큰이 아니라 페이로드 결함일 가능성이 커 삭제를 보류한다.
            log.warn("FCM INVALID_ARGUMENT 전건 실패 — 페이로드 결함 의심, 토큰 삭제 보류 (count={})", invalidArgHeld);
        }
        return batch.getSuccessCount();
    }

    /** {@code list} 를 최대 {@code size} 개씩 끊어 부분 리스트(원본 뷰) 목록으로 반환. */
    static List<List<String>> partition(List<String> list, int size) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < list.size(); start += size) {
            chunks.add(list.subList(start, Math.min(start + size, list.size())));
        }
        return chunks;
    }

    /**
     * 영구 무효(삭제 대상) 토큰 여부.
     *
     * <p>{@code UNREGISTERED}(앱 삭제/토큰 만료)·{@code SENDER_ID_MISMATCH}(타 프로젝트 토큰)는 토큰 자체가
     * 무효이므로 즉시 삭제. {@code INVALID_ARGUMENT}는 토큰 형식 문제일 수도, 페이로드 결함일 수도 있어 —
     * 배치에 성공이 하나라도 있을 때만(=페이로드 정상, 토큰 문제로 확정) 삭제해, 페이로드 버그로 인한
     * 전체 토큰 대량 삭제를 막는다. 그 외(쿼터·서버 일시오류 등)는 일시적이므로 보존.
     */
    static boolean isPermanentlyInvalid(MessagingErrorCode code, boolean batchHadSuccess) {
        if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
            return true;
        }
        return code == MessagingErrorCode.INVALID_ARGUMENT && batchHadSuccess;
    }

    /** 발송 결과 — 성공 수 + 무효 토큰(즉시 정리 대상). */
    public record SendResult(int successCount, List<String> invalidTokens) {
    }
}
