package site.krip.global.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.config.StorageProperties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link S3ObjectStorage} 단위 테스트 (S3Client 목) — 키 스킴/URL 파생 및 파싱불가 URL 의 안전 처리 검증.
 * delete/deleteMany 가 perm 키가 아닌 URL 을 SDK 호출 없이 건너뛰는지 본다.
 */
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
    @DisplayName("deleteMany: 유효 키만 추려 deleteObjects 1회, 전부 무효면 호출 없음")
    void deleteManyFiltersInvalid() {
        storage.deleteMany(List.of(
                "https://s3.test/mybucket/uploads/perm/u1/post/a.jpg",
                "https://cdn.other.com/nope.jpg"));
        verify(s3, times(1)).deleteObjects(any(DeleteObjectsRequest.class));

        storage.deleteMany(List.of("https://cdn.other.com/nope.jpg"));
        // 여전히 1회 — 전부 무효인 두 번째 호출은 SDK 미접근
        verify(s3, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
