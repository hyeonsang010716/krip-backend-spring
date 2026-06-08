package site.krip.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Autowired;
import site.krip.global.auth.jwt.JwtProvider;

/**
 * 모든 통합 테스트의 베이스 — 실 PostgreSQL/MongoDB/Redis 를 Testcontainers 로 띄운다.
 *
 * <p>운영과 동일 이미지({@code postgres:16-alpine}, {@code mongo:7}, {@code redis:7-alpine})를
 * <b>JVM 당 1세트</b>(싱글톤 컨테이너 패턴)만 띄워 모든 하위 테스트가 공유한다 — static 초기화 블록에서
 * 직접 {@code start()} 하므로 클래스마다 재기동하지 않고, JVM 종료 시 Ryuk 가 정리한다.
 *
 * <p>연결 정보는 {@link DynamicPropertySource} 로 주입해 {@code application.yml} 의 로컬 기본값을 덮어쓴다.
 * Flyway(V1~V8)+{@code ddl-auto=validate}+Mongo 인덱스 생성이 실제 컨테이너에서 수행되므로,
 * 컨텍스트 로딩만으로도 스키마 정합성과 빈 와이어링 전체가 검증된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestFixtures.class)
public abstract class IntegrationTestSupport {

    /** application.yml 의 {@code krip.auth.access-token} 로컬 기본값 — 글로벌 Bearer 필터 통과용. */
    protected static final String ACCESS_TOKEN = "dev-access-token";

    static final PostgreSQLContainer<?> POSTGRES;
    static final MongoDBContainer MONGO;
    static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("krip")
                .withUsername("krip")
                .withPassword("test-pass");
        MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7"));
        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        POSTGRES.start();
        MONGO.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideConnections(DynamicPropertyRegistry registry) {
        // PostgreSQL — datasource(=Flyway 공유) 를 컨테이너로.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // MongoDB — authSource 포함 운영 URI 대신 컨테이너 replicaset URI 로 통째 교체.
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        // Redis — hot(DB0)/dedupe(DB1) 모두 같은 컨테이너의 host/port 사용.
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // JWT/공유 토큰 secret — application.yml 에 기본값이 없으므로 테스트에서 명시(≥32자).
        registry.add("krip.auth.jwt.secret", () -> "test-login-jwt-secret-value-1234567890");
        registry.add("krip.share.secret", () -> "test-share-jwt-secret-value-1234567890");
        // 테스트는 여러 Spring 컨텍스트(@MockitoBean/@TestPropertySource 등)를 캐시하며 각자 Hikari 풀을 잡는다.
        // 운영 기본값(20)이면 컨텍스트 수×20 이 PG max_connections(100)를 초과(too many clients) → 풀을 축소한다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected TestFixtures fixtures;

    @Autowired
    protected JwtProvider jwtProvider;

    /** 글로벌 Bearer 토큰 헤더 값 ({@code Authorization: Bearer ...} 에 그대로 사용). */
    protected String bearer() {
        return "Bearer " + ACCESS_TOKEN;
    }

    /** 주어진 user_id 로 발급한 유저 로그인 JWT ({@code X-Auth-Token} 헤더에 사용). */
    protected String userToken(String userId) {
        return jwtProvider.issue(userId);
    }
}
