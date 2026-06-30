package site.krip.domain.ai.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import site.krip.domain.ai.exception.AiServiceException;
import site.krip.global.config.AiProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AiServiceClient} bulkhead 단위 테스트 — latch 로 블록되는 인메모리 HTTP 서버로
 * 상한 도달 시 503·슬롯 해제 후 재허용·에러 경로 permit 누수 없음을 sleep 없이 검증한다.
 */
@DisplayName("AI 서비스 클라이언트 — 동시 호출 상한·permit 반납")
class AiServiceClientTest {

    private HttpServer server;
    private ExecutorService serverPool;
    private ExecutorService clientPool;
    private RestClient restClient;

    private CountDownLatch entered;   // 핸들러 진입(=permit 점유) 신호
    private CountDownLatch release;   // 핸들러 블록 해제 신호

    @BeforeEach
    void setUp() throws IOException {
        entered = new CountDownLatch(2);
        release = new CountDownLatch(1);
        serverPool = Executors.newFixedThreadPool(4);
        clientPool = Executors.newFixedThreadPool(4);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverPool);
        // /ok: 진입 신호 후 release 까지 블록 → in-flight 상태를 결정적으로 유지.
        server.createContext("/ok", blockingOk());
        // /err: 즉시 500 → upstream 실패 경로 검증용.
        server.createContext("/err", ex -> send(ex, 500, "{\"detail\":\"boom\"}"));
        server.start();

        restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
        serverPool.shutdownNow();
        clientPool.shutdownNow();
    }

    @Test
    @DisplayName("동시 호출이 상한에 도달하면 추가 호출은 즉시 503, 슬롯 해제 후 재허용")
    void rejectsWhenSaturatedThenRecovers() throws Exception {
        // given
        AiServiceClient client = new AiServiceClient(restClient, props(2));

        // when
        // permit 2개를 점유한 채 핸들러에서 블록.
        Future<Object> f1 = clientPool.submit(() -> client.postJson("/ok", Map.of(), Object.class));
        Future<Object> f2 = clientPool.submit(() -> client.postJson("/ok", Map.of(), Object.class));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // then
        // 3번째 호출 — HTTP 도달 전에 세마포어에서 즉시 거부.
        assertThatThrownBy(() -> client.postJson("/ok", Map.of(), Object.class))
                .isInstanceOf(AiServiceException.class)
                .satisfies(e -> assertThat(((AiServiceException) e).getStatus()).isEqualTo(503));

        // 블록 해제 → 두 호출 정상 완료(permit 반납).
        release.countDown();
        assertThat(f1.get(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(f2.get(5, TimeUnit.SECONDS)).isNotNull();

        // 슬롯이 비었으므로 다시 통과.
        assertThat(client.postJson("/ok", Map.of(), Object.class)).isNotNull();
    }

    @Test
    @DisplayName("upstream 실패(500)로 예외가 나도 permit 은 반납되어 다음 호출이 막히지 않음")
    void releasesPermitOnFailure() {
        // given
        AiServiceClient client = new AiServiceClient(restClient, props(1));

        // 첫 호출: 500 → 502 매핑(혼잡 503 아님).
        assertThatThrownBy(() -> client.postJson("/err", Map.of(), Object.class))
                .isInstanceOf(AiServiceException.class)
                .satisfies(e -> assertThat(((AiServiceException) e).getStatus()).isEqualTo(502));

        // permit 누수가 없다면 두 번째도 502(누수 시엔 503 혼잡이 됨).
        assertThatThrownBy(() -> client.postJson("/err", Map.of(), Object.class))
                .isInstanceOf(AiServiceException.class)
                .satisfies(e -> assertThat(((AiServiceException) e).getStatus()).isEqualTo(502));
    }

    private HttpHandler blockingOk() {
        return ex -> {
            try {
                entered.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(ex, 200, "{\"ok\":true}");
        };
    }

    /** circuitFailureThreshold 는 테스트 중 서킷이 열리지 않게 충분히 크게(=세마포어 동작만 격리 검증). */
    private AiProperties props(int maxConcurrency) {
        // (enabled, serviceUrl, connectTimeoutMs, readTimeoutMs, circuitFailureThreshold, circuitOpenMs, maxConcurrency)
        return new AiProperties(true, "http://unused", 1000, 10000, 100, 30000, maxConcurrency);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
