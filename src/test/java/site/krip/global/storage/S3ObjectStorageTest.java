package site.krip.global.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.config.StorageProperties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link S3ObjectStorage} 단위 테스트 (S3Client 목) — 키 스킴/URL 파생 및 파싱불가 URL 의 안전 처리 검증.
 * delete/deleteMany 가 perm 키가 아닌 URL 을 SDK 호출 없이 건너뛰는지 본다.
 */
@DisplayName("S3 스토리지 — 업로드 키·삭제·배치 부분 실패 환원")
class S3ObjectStorageTest {

    private S3Client s3;
    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        StorageProperties props = new StorageProperties(
                "ak", "sk", "kr", "mybucket", "https://s3.test/");
        storage = new S3ObjectStorage(s3, props);
    }

    private ByteArrayInputStream bytes() {
        return new ByteArrayInputStream(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("uploadToKey: 결정적 키로 URL 생성 + putObject 호출")
    void uploadToKeyDeterministicUrl() {
        String url = storage.uploadToKey(bytes(), 3, "small.jpg", "image/jpeg", "u1/post");

        assertThat(url).isEqualTo("https://s3.test/mybucket/uploads/perm/u1/post/small.jpg");
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("uploadPerm: prefix 하위 uuid 키 + 확장자 보존")
    void uploadPermUrlUnderPrefix() {
        String url = storage.uploadPerm(bytes(), 3, "photo.png", "image/png", "u1/profile");

        assertThat(url).startsWith("https://s3.test/mybucket/uploads/perm/u1/profile/");
        assertThat(url).endsWith(".png");
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("delete: perm 키 URL → 해당 key 로 deleteObject 호출")
    void deleteValidUrl() {
        storage.delete("https://s3.test/mybucket/uploads/perm/u1/post/small.jpg");

        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("delete: perm 키 형식이 아닌 URL → SDK 호출 없이 건너뜀(orphan 은폐 방지 로그)")
    void deleteUnparseableUrlSkipped() {
        storage.delete("https://cdn.other.com/whatever/x.jpg");
        storage.delete(null);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("deleteMany: 유효/무효 혼재 시 유효 키만 추려 deleteObjects 1회, 무효 URL 은 실패 미보고")
    void deleteManyFiltersInvalidFromMixed() {
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build()); // 에러 없음

        List<String> failed = storage.deleteMany(List.of(
                "https://s3.test/mybucket/uploads/perm/u1/post/a.jpg",
                "https://cdn.other.com/nope.jpg"));

        verify(s3, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
        // 파싱 불가 URL 은 S3 대상이 아니므로 실패로 보고하지 않는다(메타데이터 정리 진행).
        assertThat(failed).isEmpty();
    }

    @Test
    @DisplayName("deleteMany: 전부 무효 URL 이면 SDK 미접근, 빈 결과")
    void deleteManyAllInvalidSkipsSdk() {
        assertThat(storage.deleteMany(List.of("https://cdn.other.com/nope.jpg"))).isEmpty();

        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("deleteMany: 배치 부분 실패 시 실패한 키를 URL 로 환원해 반환(메타데이터 보존용)")
    void deleteManyReturnsPartialFailureUrls() {
        String okUrl = "https://s3.test/mybucket/uploads/perm/u1/post/a.jpg";
        String failUrl = "https://s3.test/mybucket/uploads/perm/u1/post/b.jpg";
        // b.jpg 키만 실패로 응답 (deleteObjects 는 예외 대신 errors() 로 보고)
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .errors(S3Error.builder()
                                .key("uploads/perm/u1/post/b.jpg")
                                .code("InternalError")
                                .build())
                        .build());

        List<String> failed = storage.deleteMany(List.of(okUrl, failUrl));

        assertThat(failed).containsExactly(failUrl);
    }
}
