package site.krip.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * NaverCloud Object Storage(S3 호환) 클라이언트.
 * endpoint override + path-style 접근으로 연결한다.
 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(StorageProperties props) {
        // AWS SDK v2 는 빈 accessKeyId 를 빌드 시점에 거부한다. 미설정 환경(dev/CI/test)에서도
        // 부팅되도록 placeholder 로 대체 — S3 접근만 런타임에 실패한다(best-effort).
        String accessKey = props.accessKeyId();
        String secretKey = props.secretAccessKey();
        if (accessKey == null || accessKey.isBlank()) {
            accessKey = "not-configured";
            secretKey = "not-configured";
        }

        var builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());

        if (props.endpointUrl() != null && !props.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(props.endpointUrl()));
        }
        return builder.build();
    }
}
