package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import site.krip.domain.friend.repository.FriendUserSearchRepository;
import site.krip.support.IntegrationTestSupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 닉네임 선해석 상한이 메인 검색과 동일 정렬(최신 가입순)로 적용되는지 검증(회귀).
 * 구버그: 정렬 없이 N개를 뽑아 상한 초과 시 최신 유저가 비결정적 누락("흔한 이름 검색 안 됨"). 최신순으로 최근 매치 보존.
 */
class FriendSearchNameMatchOrderingIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private FriendUserSearchRepository searchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("매치가 상한을 넘으면 가장 최근 가입 유저만 보존되고 최신순으로 반환된다")
    void capKeepsNewestMatchesInOrder() {
        // 같은 이름 3명 생성 후 created_at 을 명시적 증가 값으로 고정 → sleep 없이 정렬 결정성 확보.
        String name = "namematchtest-" + UUID.randomUUID();
        String oldest = fixtures.createActiveUser(name);
        String mid = fixtures.createActiveUser(name);
        String newest = fixtures.createActiveUser(name);
        Instant base = Instant.parse("2020-01-01T00:00:00Z");
        setCreatedAt(oldest, base);
        setCreatedAt(mid, base.plusSeconds(1));
        setCreatedAt(newest, base.plusSeconds(2));

        // 상한(2) < 매치(3) — 최신순 정렬이라 가장 오래된 oldest 만 잘려야 한다.
        List<String> capped = searchRepository.findUserIdsByNameLike("%" + name + "%", PageRequest.of(0, 2));

        assertThat(capped).containsExactly(newest, mid);
        assertThat(capped).doesNotContain(oldest);
    }

    /** @CreationTimestamp(insert-only)라 엔티티로는 못 바꿔 native UPDATE 로 created_at 을 고정. */
    private void setCreatedAt(String userId, Instant at) {
        jdbcTemplate.update("update users set created_at = ? where user_id = ?", Timestamp.from(at), userId);
    }
}
