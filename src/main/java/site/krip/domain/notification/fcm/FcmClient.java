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
     * multicast 발송 — 성공 수 + 만료(UNREGISTERED) 토큰 목록 반환. 비활성/글로벌 실패 시 빈 결과.
     */
    public SendResult sendMulticast(List<String> tokens, String title, String body, Map<String, String> data) {
        if (messaging == null || tokens.isEmpty()) {
            return new SendResult(0, List.of());
        }
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data)
                .build();
        BatchResponse batch;
        try {
            batch = messaging.sendEachForMulticast(message);
        } catch (FirebaseMessagingException e) {
            log.warn("FCM multicast 실패 (count={}): {}", tokens.size(), e.toString());
            return new SendResult(0, List.of());
        }

        List<SendResponse> responses = batch.getResponses();
        List<String> invalid = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse r = responses.get(i);
            if (r.isSuccessful()) {
                continue;
            }
            FirebaseMessagingException ex = r.getException();
            if (ex != null && ex.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                invalid.add(tokens.get(i));
            } else {
                log.warn("FCM 발송 실패 token_prefix={} error={}",
                        tokens.get(i).substring(0, Math.min(16, tokens.get(i).length())),
                        ex != null ? ex.getMessage() : "unknown");
            }
        }
        return new SendResult(batch.getSuccessCount(), invalid);
    }

    /** 발송 결과 — 성공 수 + 만료 토큰(즉시 정리 대상). */
    public record SendResult(int successCount, List<String> invalidTokens) {
    }
}
