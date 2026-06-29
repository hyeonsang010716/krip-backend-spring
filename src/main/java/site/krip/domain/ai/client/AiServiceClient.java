package site.krip.domain.ai.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import site.krip.domain.ai.exception.AiServiceException;
import site.krip.global.config.AiProperties;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * FastAPI AI 서비스 호출 게이트웨이.
 *
 * <p>모든 AI 도메인 서비스는 이 클라이언트만 거친다. 인증(서비스 토큰)·타임아웃은 {@code aiRestClient}
 * 빈에 박혀 있고, 여기서는 서킷 브레이커와 upstream 상태 → 사용자 HTTP 코드 매핑만 담당한다.
 * upstream 5xx/네트워크 실패만 서킷 실패로 집계하고, 4xx(클라이언트 입력 문제)는 서킷에 반영하지 않는다.
 */
@Component
@Slf4j
public class AiServiceClient {

    private final RestClient ai;
    private final boolean enabled;
    private final AiCircuitBreaker circuit;
    private final Semaphore inFlight;

    public AiServiceClient(@Qualifier("aiRestClient") RestClient ai, AiProperties props) {
        this.ai = ai;
        this.enabled = props.enabled();
        this.circuit = new AiCircuitBreaker(props.circuitFailureThreshold(), props.circuitOpenMs());
        this.inFlight = new Semaphore(props.maxConcurrency());
    }

    /** JSON 본문 POST 후 응답을 {@code responseType} 으로 역직렬화. */
    public <T> T postJson(String path, Object body, Class<T> responseType) {
        return guarded(path, () -> ai.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw mapStatus(res.getStatusCode().value());
                })
                .body(responseType));
    }

    /** multipart/form-data POST(파일 업로드 전달) 후 응답을 {@code responseType} 으로 역직렬화. */
    public <T> T postMultipart(String path, MultiValueMap<String, HttpEntity<?>> parts, Class<T> responseType) {
        return guarded(path, () -> ai.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw mapStatus(res.getStatusCode().value());
                })
                .body(responseType));
    }

    /** enabled 체크 + 서킷 브레이커 + 실패 분류를 호출에 공통 적용. 실패는 {@link AiServiceException} 으로 통일. */
    private <T> T guarded(String path, Supplier<T> call) {
        if (!enabled) {
            throw new AiServiceException(503, "AI 기능이 비활성화되어 있습니다.");
        }
        // 동시 호출 상한(bulkhead) — 느린 LLM 호출이 Tomcat 풀을 고갈시키지 않게 격리. 초과 시 즉시 503.
        if (!inFlight.tryAcquire()) {
            throw new AiServiceException(503, "AI 요청이 혼잡합니다. 잠시 후 다시 시도해주세요.");
        }
        try {
            if (!circuit.tryAcquire()) {
                throw new AiServiceException(503, "AI 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.");
            }
            try {
                T result = call.get();
                circuit.recordSuccess();
                return result;
            } catch (AiServiceException e) {
                // upstream 4xx/5xx 매핑됨 — 5xx 만 서킷 실패, 4xx 는 입력 문제라 미반영.
                if (e.getStatus() >= 500) {
                    circuit.recordFailure();
                } else {
                    circuit.release();
                }
                throw e;
            } catch (ResourceAccessException e) {
                // connect/read timeout, connection refused → upstream 장애.
                circuit.recordFailure();
                log.warn("AI 서비스 연결 실패 (path={})", path, e);
                throw new AiServiceException(502, "AI 서비스에 연결할 수 없습니다.");
            } catch (RuntimeException e) {
                // 직렬화/변환 등 우리측 결함 — AI 건강과 무관하므로 probe 만 해제.
                circuit.release();
                log.warn("AI 서비스 호출 실패 (path={})", path, e);
                throw new AiServiceException(502, "AI 서비스 호출에 실패했습니다.");
            }
        } finally {
            inFlight.release();
        }
    }

    /** upstream HTTP 상태 → 클라이언트로 내려줄 코드/메시지 매핑. */
    private static AiServiceException mapStatus(int upstream) {
        return switch (upstream) {
            case 400 -> new AiServiceException(400, "AI 요청이 올바르지 않습니다.");
            case 429 -> new AiServiceException(429, "AI 요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.");
            case 503 -> new AiServiceException(503, "AI 서비스를 일시적으로 사용할 수 없습니다.");
            default -> new AiServiceException(502, "AI 서비스 처리에 실패했습니다.");
        };
    }
}
