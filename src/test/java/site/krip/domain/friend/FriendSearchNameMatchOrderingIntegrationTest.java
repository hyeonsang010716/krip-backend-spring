package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import site.krip.domain.friend.repository.FriendUserSearchRepository;
import site.krip.support.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 닉네임 선해석 상한이 메인 검색과 동일 정렬(최신 가입순)로 적용되는지 검증(회귀).
 *
 * <p>구버그: 정렬 없이 임의의 N개를 뽑아, 매치가 상한을 넘으면 최신 유저가 비결정적으로 누락 →
 * "흔한 이름은 검색이 안 돼요"(재현 불가). 최신순 정렬로 상한을 넘어도 가장 최근 가입 매치를 보존한다.
 */
class FriendSearchNameMatchOrderingIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private FriendUserSearchRepository searchRepository;

    @Test
    @DisplayName("매치가 상한을 넘으면 가장 최근 가입 유저만 보존되고 최신순으로 반환된다")
    void capKeepsNewestMatchesInOrder() throws InterruptedException {
        // 같은 이름 3명을 시차를 두고 생성 → created_at 이 oldest < mid < newest.
        String name = "namematchtest-" + UUID.randomUUID();
        String oldest = fixtures.createActiveUser(name);
        Thread.sleep(10);
        String mid = fixtures.createActiveUser(name);
        Thread.sleep(10);
        String newest = fixtures.createActiveUser(name);

        // 상한(2) < 매치(3) — 최신순 정렬이라 가장 오래된 oldest 만 잘려야 한다.
        List<String> capped = searchRepository.findUserIdsByNameLike("%" + name + "%", PageRequest.of(0, 2));

        assertThat(capped).containsExactly(newest, mid);
        assertThat(capped).doesNotContain(oldest);
    }
}
