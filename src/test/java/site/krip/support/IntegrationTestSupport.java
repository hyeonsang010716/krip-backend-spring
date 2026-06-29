package site.krip.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.auth.jwt.JwtProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    /** 글로벌 Bearer 필터 통과용 토큰 — application.yml 엔 기본값이 없어 {@code @DynamicPropertySource} 로 주입. */
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
        // access-token/JWT/공유 토큰 — application.yml 에 기본값이 없으므로 테스트에서 명시(≥32자).
        registry.add("krip.auth.access-token", () -> ACCESS_TOKEN);
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

    @Autowired
    protected FriendshipRepository friendshipRepository;

    @Autowired
    protected UserBlockRepository userBlockRepository;

    @Autowired
    protected ObjectMapper objectMapper;

    /** 글로벌 Bearer 토큰 헤더 값 ({@code Authorization: Bearer ...} 에 그대로 사용). */
    protected String bearer() {
        return "Bearer " + ACCESS_TOKEN;
    }

    /** 주어진 user_id 로 발급한 유저 로그인 JWT ({@code X-Auth-Token} 헤더에 사용). */
    protected String userToken(String userId) {
        return jwtProvider.issue(userId);
    }

    /** 글로벌 Bearer + 해당 user_id 의 로그인 JWT 를 함께 실어 모든 인증 필터를 통과시킨다. */
    protected RequestPostProcessor auth(String userId) {
        return request -> {
            request.addHeader("Authorization", bearer());
            request.addHeader("X-Auth-Token", userToken(userId));
            return request;
        };
    }

    /** 글로벌 Bearer 만 — 유저 JWT 가 필요 없는 공개 엔드포인트(OAuth 로그인/콜백 등)용. */
    protected RequestPostProcessor bearerOnly() {
        return request -> {
            request.addHeader("Authorization", bearer());
            return request;
        };
    }

    /** 두 유저를 ACCEPTED 친구로 직접 시드(API 우회, 방향 무관) — 친구 관계가 precondition 일 때 사용. */
    protected void makeFriends(String a, String b) {
        Friendship friendship = new Friendship(a, b);
        friendship.accept();
        friendshipRepository.save(friendship);
    }

    /** blocker 가 blocked 를 단방향 차단 — 직접 시드(API 우회). */
    protected void block(String blocker, String blocked) {
        userBlockRepository.save(new UserBlock(blocker, blocked));
    }

    /** 친구 요청→수락을 실제 API 로 수행 — 친구 플로우 자체의 부수효과(알림 등)까지 거쳐야 할 때 사용. */
    protected void befriendViaApi(String a, String b) throws Exception {
        String res = mockMvc.perform(post("/api/friend/friendships/requests")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("addressee_id", b)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String friendshipId = JsonPath.read(res, "$.friendship_id");
        mockMvc.perform(patch("/api/friend/friendships/requests/{id}/accept", friendshipId)
                        .with(auth(b)))
                .andExpect(status().isOk());
    }

    /** blocker 가 blocked 를 실제 차단 API 로 차단 — 차단 플로우의 부수효과까지 거쳐야 할 때 사용. */
    protected void blockViaApi(String blocker, String blocked) throws Exception {
        mockMvc.perform(post("/api/friend/blocks")
                        .with(auth(blocker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_user_id", blocked)))
                .andExpect(status().isCreated());
    }

    /**
     * key-value 쌍으로 JSON 요청 본문을 만든다 — Jackson 이 이스케이프/타입 변환을 처리하므로
     * 손으로 따옴표를 붙이지 않는다. 값은 String/Number/Boolean/List/Map/null 모두 가능.
     * 예: {@code json("title", "방", "member_ids", List.of(a, b))}.
     */
    protected String json(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("test json build failed", e);
        }
    }

    /** 응답 본문을 JsonNode 로 파싱. */
    protected JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 응답 본문에서 지정 필드를 문자열로 추출 (생성 응답의 id 추출용). */
    protected String idFrom(MvcResult result, String field) throws Exception {
        return readJson(result).get(field).asText();
    }
}
