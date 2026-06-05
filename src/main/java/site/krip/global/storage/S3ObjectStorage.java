package site.krip.global.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.krip.global.config.StorageProperties;
import site.krip.global.support.IdGenerator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * S3 호환 Object Storage 구현. 키 스킴: {@code uploads/perm/{prefix}/{uuid}.{ext}}.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

    private static final String PERM_ROOT = "uploads/perm";

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client s3;
    private final String bucket;
    private final String publicBase;

    public S3ObjectStorage(S3Client s3, StorageProperties props) {
        this.s3 = s3;
        this.bucket = props.bucketName();
        // 접근 URL base: {endpoint}/{bucket}
        String endpoint = props.endpointUrl() == null ? "" : props.endpointUrl().replaceAll("/+$", "");
        this.publicBase = endpoint + "/" + props.bucketName();
    }

    @Override
    public String uploadPerm(InputStream content, long contentLength, String fileName,
                             String contentType, String prefix) {
        String key = PERM_ROOT + "/" + prefix + "/" + UUID.randomUUID().toString().replace("-", "")
                + extension(fileName);

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build(),
                RequestBody.fromInputStream(content, contentLength));

        return publicBase + "/" + key;
    }

    @Override
    public String uploadToKey(InputStream content, long contentLength, String fileName,
                              String contentType, String prefix) {
        String key = PERM_ROOT + "/" + prefix + "/" + fileName;
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build(),
                RequestBody.fromInputStream(content, contentLength));
        return publicBase + "/" + key;
    }

    @Override
    public void delete(String url) {
        String key = keyFromUrl(url);
        if (key == null) {
            // 파싱 불가 URL — 조용히 넘기면 orphan 객체가 쌓이므로 경고만 남긴다.
            log.warn("S3 delete 건너뜀 — perm 키 형식이 아닌 URL: {}", url);
            return;
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deleteMany(List<String> urls) {
        List<ObjectIdentifier> ids = urls.stream()
                .map(url -> {
                    String k = keyFromUrl(url);
                    if (k == null) {
                        log.warn("S3 deleteMany 건너뜀 — perm 키 형식이 아닌 URL: {}", url);
                    }
                    return k;
                })
                .filter(java.util.Objects::nonNull)
                .map(k -> ObjectIdentifier.builder().key(k).build())
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(ids).build())
                .build());
    }

    @Override
    public void deleteByPrefix(String userId) {
        deleteByPathPrefix(userId);
    }

    @Override
    public void deleteByPathPrefix(String pathPrefix) {
        String prefix = PERM_ROOT + "/" + pathPrefix + "/";
        String continuationToken = null;
        do {
            ListObjectsV2Response listing = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());

            List<ObjectIdentifier> ids = listing.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                    .toList();

            if (!ids.isEmpty()) {
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(ids).build())
                        .build());
            }
            continuationToken = Boolean.TRUE.equals(listing.isTruncated())
                    ? listing.nextContinuationToken() : null;
        } while (continuationToken != null);
    }

    private String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0) ? fileName.substring(dot) : "";
    }

    private String keyFromUrl(String url) {
        if (url == null) {
            return null;
        }
        String marker = "/" + PERM_ROOT + "/";
        int idx = url.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        return url.substring(idx + 1); // PERM_ROOT 부터의 key
    }
}
