package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import site.krip.domain.auth.dto.response.ProfileImageResponse;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.exception.ProfileImageAlreadyExistsException;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.auth.service.ProfileService;
import site.krip.support.FakeObjectStorage;
import site.krip.support.FakeStorageConfig;
import site.krip.support.IntegrationTestSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필 이미지 동시 추가 — 행 잠금(findByIdForUpdate)으로 lost-update 회귀 방지 검증.
 * 구버그: READ_COMMITTED 에서 둘 다 null 읽고 각자 저장 → 나중 것이 덮어써 고아 S3 + 409 없음. 잠금으로 하나만 성공·나머지 409.
 */
@Import(FakeStorageConfig.class)
@DisplayName("프로필 이미지 동시 추가 — 단일 승자·고아 S3 없음")
class ProfileImageConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ProfileService profileService;
    @Autowired
    private FakeObjectStorage storage;
    @Autowired
    private UserDetailInformRepository detailRepository;

    @Test
    @DisplayName("동시 추가 — 하나만 성공(나머지 409), DB 는 승자 URL, 고아 S3 없음")
    void concurrentAddProfileImage() throws Exception {
        // given
        String userId = fixtures.createActiveUser("프로필동시");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> a = () -> attempt(userId, start);
        Callable<Object> b = () -> attempt(userId, start);
        Future<Object> fa = pool.submit(a);
        Future<Object> fb = pool.submit(b);

        // when
        start.countDown();                      // 동시 출발

        List<Object> results = List.of(fa.get(), fb.get());
        pool.shutdown();

        // then
        long success = results.stream().filter(r -> r instanceof ProfileImageResponse).count();
        long conflict = results.stream().filter(r -> r instanceof ProfileImageAlreadyExistsException).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        String winnerUrl = results.stream()
                .filter(ProfileImageResponse.class::isInstance)
                .map(r -> ((ProfileImageResponse) r).profileImageUrl())
                .findFirst().orElseThrow();

        UserDetailInform detail = detailRepository.findById(userId).orElseThrow();
        assertThat(detail.getProfileImageUrl()).isEqualTo(winnerUrl);   // DB 엔 승자 URL

        // 이 유저 prefix 의 살아있는 객체는 승자 1개뿐(패자가 자기 업로드 정리). stored 는 공유 컨텍스트라 prefix 로 필터링.
        String userPrefix = userId + "/profile";
        assertThat(storage.stored.stream().filter(u -> u.contains(userPrefix)).count()).isEqualTo(1);
        assertThat(storage.stored).contains(winnerUrl);
    }

    private Object attempt(String userId, CountDownLatch start) throws Exception {
        start.await();
        try {
            return profileService.addProfileImage(userId, validPng());
        } catch (ProfileImageAlreadyExistsException e) {
            return e;
        }
    }

    private static byte[] validPng() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
