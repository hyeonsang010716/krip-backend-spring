package site.krip.global.storage;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import site.krip.global.config.StorageProperties;
import site.krip.global.support.IdGenerator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S3 호환 Object Storage 구현. 키 스킴: {@code uploads/perm/{prefix}/{uuid}.{ext}}.
 */
@Component
@Slf4j
public class S3ObjectStorage implements ObjectStorage {

    private static final String PERM_ROOT = "uploads/perm";

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
        String key = PERM_ROOT + "/" + prefix + "/" + safeObjectName(fileName);
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
    public List<String> deleteMany(List<String> urls) {
        // key → url 역매핑. 파싱 불가 URL 은 삭제 대상이 아니므로 실패로 보고하지 않고 건너뛴다.
        Map<String, String> keyToUrl = new HashMap<>();
        for (String url : urls) {
            String k = keyFromUrl(url);
            if (k == null) {
                log.warn("S3 deleteMany 건너뜀 — perm 키 형식이 아닌 URL: {}", url);
            } else {
                keyToUrl.put(k, url);
            }
        }
        if (keyToUrl.isEmpty()) {
            return List.of();
        }

        List<ObjectIdentifier> ids = keyToUrl.keySet().stream()
                .map(k -> ObjectIdentifier.builder().key(k).build())
                .toList();
        DeleteObjectsResponse resp = s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(ids).build())
                .build());

        // deleteObjects 는 일부 키 실패를 예외 없이 errors() 로 돌려준다 → URL 로 환원해 실패 목록에 담는다.
        if (resp.errors().isEmpty()) {
            return List.of();
        }
        List<String> failed = new ArrayList<>(resp.errors().size());
        for (S3Error err : resp.errors()) {
            log.warn("S3 deleteMany 부분 실패 (key={}, code={}, msg={})", err.key(), err.code(), err.message());
            String url = keyToUrl.get(err.key());
            if (url != null) {
                failed.add(url);
            }
        }
        return failed;
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
                DeleteObjectsResponse resp = s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(ids).build())
                        .build());
                // deleteObjects 는 일부 키 실패를 예외 없이 errors() 로 돌려준다 → purge 잔존 신호를 로깅.
                for (S3Error err : resp.errors()) {
                    log.warn("S3 deleteByPathPrefix 부분 실패 (prefix={}, key={}, code={}, msg={})",
                            prefix, err.key(), err.code(), err.message());
                }
            }
            continuationToken = Boolean.TRUE.equals(listing.isTruncated())
                    ? listing.nextContinuationToken() : null;
        } while (continuationToken != null);
    }

    /**
     * 파일명에서 안전한 확장자만 추출 — 영숫자 1~10자만 허용, 그 외(경로 traversal·특수문자·길이남용)는 무시.
     * 현재 호출부는 모두 서버 생성 파일명({@code "image."+감지포맷})을 넘기지만, 키에 들어가는 값이라 방어한다.
     */
    private String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = fileName.substring(dot + 1);
        return ext.matches("[A-Za-z0-9]{1,10}") ? "." + ext.toLowerCase() : "";
    }

    /**
     * 키에 직접 들어가는 오브젝트명 방어 — 영숫자·{@code . _ -} 만 허용해 경로 구분자/traversal 주입을 차단한다.
     * 현재 호출부는 모두 서버 생성명({@code "image."+감지포맷})이라 위반은 곧 서버 결함이므로 fail-fast.
     */
    private static String safeObjectName(String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]{1,100}") || fileName.contains("..")) {
            throw new IllegalArgumentException("허용되지 않는 오브젝트 파일명입니다.");
        }
        return fileName;
    }

    private @Nullable String keyFromUrl(@Nullable String url) {
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
