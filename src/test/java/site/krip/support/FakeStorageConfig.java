package site.krip.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 업로드 성공 경로를 검증하려는 테스트가 {@code @Import(FakeStorageConfig.class)} 로 끌어쓰는 설정.
 * 실제 {@code S3ObjectStorage} 빈 대신 {@link FakeObjectStorage} 를 {@code @Primary} 로 주입한다.
 */
@TestConfiguration
public class FakeStorageConfig {

    @Bean
    @Primary
    public FakeObjectStorage fakeObjectStorage() {
        return new FakeObjectStorage();
    }
}
