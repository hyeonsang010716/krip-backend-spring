package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object Storage (NaverCloud S3 호환) 설정.
 */
@ConfigurationProperties(prefix = "krip.storage")
public record StorageProperties(
        String accessKeyId,
        String secretAccessKey,
        String region,
        String bucketName,
        String endpointUrl
) {
}
