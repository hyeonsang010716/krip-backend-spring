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
 * 전송에는 명시 타임아웃 + 서킷 브레이커를 적용해 FCM 장애가 push 워커 풀을 고갈시키지 않게 한다.
 */
@Component
public class FcmClient {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);

    /** {@code sendEachForMulticast} 의 호출당 토큰 상한(Firebase Admin SDK 하드 제한). */
    static final int MAX_MULTICAST_BATCH = 500;

    private final FcmProperties props;
    private final FcmCircuitBreaker circuit;
    private volatile FirebaseMessaging messaging;

    public FcmClient(FcmProperties props) {
        this.props = props;
        this.circuit = new FcmCircuitBreaker(props.circuitFailureThreshold(), props.circuitOpenMs());
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
            // 명시 타임아웃 — 미설정 시 SDK 기본(사실상 무한)이라 FCM 행 시 워커 스레드가 묶인다.
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .setConnectTimeout(props.connectTimeoutMs())
                    .setReadTimeout(props.readTimeoutMs())
                    .setWriteTimeout(props.readTimeoutMs())
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options) : FirebaseApp.getInstance();
            this.messaging = FirebaseMessaging.getInstance(app);
            log.info("FCM 초기화 완료 (project_id={})", app.getOptions().getProjectId());
        } catch (Exception e) {
            log.warn("FCM 초기화 실패 — 푸시 발송 비활성", e);
        }
    }

    public boolean isEnabled() {
        return messaging != null;
    }

    /**
     * multicast 발송 — 500 개 단위로 분할(SDK 한계) 발송 후 성공 수 + 무효 토큰 합산.
     * 비활성 시 빈 결과, 배치 실패 시 해당 배치만 건너뛴다.
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

    /** 단일 배치(≤ {@link #MAX_MULTICAST_BATCH}) 발송. 성공 수 반환, 무효 토큰은 {@code invalidOut} 에 누적. */
    private int sendBatch(List<String> tokens, Notification notification, Map<String, String> data,
                          List<String> invalidOut) {
        // build 는 acquire 보다 먼저 — 페이로드 빌드 실패(우리 버그)는 FCM 장애가 아니고,
        // probe 선점 후 여기서 throw 하면 probeInFlight 가 영구 고착되기 때문이다.
        MulticastMessage message;
        try {
            message = MulticastMessage.builder()
                    .addAllTokens(tokens)
                    .setNotification(notification)
                    .putAllData(data)
                    .build();
        } catch (RuntimeException e) {
            log.warn("FCM 메시지 빌드 실패 — 서킷 미반영 (count={})", tokens.size(), e);
            return 0;
        }
        if (!circuit.tryAcquire()) {
            // FCM 장애로 단락 중 — 워커 스레드를 타임아웃에 묶지 않고 즉시 포기(다음 cooldown 후 probe).
            log.debug("FCM 서킷 open — 배치 단락 (count={})", tokens.size());
            return 0;
        }
        BatchResponse batch;
        try {
            batch = messaging.sendEachForMulticast(message);
        } catch (FirebaseMessagingException | RuntimeException e) {
            // 글로벌 실패(인증·쿼터·네트워크)는 이 배치만 포기하고 서킷에 기록 — 연속 실패 시 단락된다.
            circuit.recordFailure();
            log.warn("FCM multicast 배치 실패 (count={})", tokens.size(), e);
            return 0;
        }
        List<SendResponse> responses = batch.getResponses();
        int successCount = batch.getSuccessCount();
        boolean batchHadSuccess = successCount > 0;
        boolean hasNonTokenFailure = false;
        int invalidArgHeld = 0;
        for (int i = 0; i < responses.size(); i++) {
            SendResponse r = responses.get(i);
            if (r.isSuccessful()) {
                continue;
            }
            FirebaseMessagingException ex = r.getException();
            MessagingErrorCode code = ex != null ? ex.getMessagingErrorCode() : null;
            if (!isTokenLevelError(code)) {
                hasNonTokenFailure = true; // 서버/전송 계열(UNAVAILABLE 등)·미상 코드 → FCM 열화 신호
            }
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
            log.warn("FCM INVALID_ARGUMENT 전건 실패 — 페이로드 결함 의심, 토큰 삭제 보류 (count={})", invalidArgHeld);
        }
        // 서킷 반영 — 루프 뒤에서 (성공 수 + FCM 열화 여부)로 판정. probe 는 여기서 해제된다.
        recordBatchOutcome(circuit, successCount, !responses.isEmpty(), hasNonTokenFailure);
        return successCount;
    }

    /**
     * 배치 결과를 서킷에 반영 — 호출이 성공해도 건별 전건 실패할 수 있으므로(FCM 열화) 성공 0건이면
     * 실패로 본다. 단 전건이 토큰 무효(UNREGISTERED 등)면 FCM 은 정상 응답한 것이라 실패로 치지 않는다
     * (전부 stale 한 방 push 가 멀쩡한 FCM 의 서킷을 여는 오작동 방지).
     */
    static void recordBatchOutcome(FcmCircuitBreaker circuit, int successCount,
                                   boolean hasResponses, boolean hasNonTokenFailure) {
        if (successCount > 0) {
            circuit.recordSuccess();
        } else if (!hasResponses) {
            // 응답 0건(이론상) — FCM 건강과 무관하므로 카운트하지 않되 probe 는 반드시 해제한다.
            circuit.release();
        } else if (hasNonTokenFailure) {
            circuit.recordFailure();
        } else {
            // 전건 토큰 무효 — FCM 은 정상 응답. probe 성공 처리(half-open 이면 close).
            circuit.recordSuccess();
        }
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
     * 영구 무효(삭제 대상) 토큰 여부. UNREGISTERED·SENDER_ID_MISMATCH 는 즉시 삭제,
     * INVALID_ARGUMENT 는 배치에 성공이 있을 때만 삭제(페이로드 결함으로 인한 대량 삭제 방지).
     */
    static boolean isPermanentlyInvalid(MessagingErrorCode code, boolean batchHadSuccess) {
        if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
            return true;
        }
        return code == MessagingErrorCode.INVALID_ARGUMENT && batchHadSuccess;
    }

    /**
     * 토큰/클라이언트 계열 오류 여부 — FCM 건강과 무관(서킷에 실패로 반영하지 않는다).
     * UNREGISTERED·SENDER_ID_MISMATCH 는 죽은 토큰, INVALID_ARGUMENT 는 페이로드 결함(우리 버그)이라 모두 FCM 정상.
     */
    static boolean isTokenLevelError(MessagingErrorCode code) {
        return code == MessagingErrorCode.UNREGISTERED
                || code == MessagingErrorCode.SENDER_ID_MISMATCH
                || code == MessagingErrorCode.INVALID_ARGUMENT;
    }

    /** 발송 결과 — 성공 수 + 무효 토큰(즉시 정리 대상). */
    public record SendResult(int successCount, List<String> invalidTokens) {
    }
}
