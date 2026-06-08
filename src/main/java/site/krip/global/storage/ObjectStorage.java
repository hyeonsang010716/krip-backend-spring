package site.krip.global.storage;

import java.io.InputStream;

/**
 * Object Storage 추상화.
 * 영구 파일은 {@code uploads/perm/{user_id}/...} 하위에 저장되며, 탈퇴 시 prefix 단위로 정리된다.
 */
public interface ObjectStorage {

    /**
     * 영구 객체 업로드.
     *
     * @param prefix {@code {user_id}/profile} 같은 도메인 prefix (StoragePrefix 참고)
     * @return 저장된 객체의 접근 URL (DB 에 보관)
     */
    String uploadPerm(InputStream content, long contentLength, String fileName,
                      String contentType, String prefix);

    /**
     * 결정적 키로 업로드 — 키 = {@code uploads/perm/{prefix}/{fileName}} (uuid 미부여).
     * 피드처럼 한 게시물의 변형(original/small/medium)을 동일 prefix 아래 고정 파일명으로 두고
     * prefix 단위로 일괄 삭제하려는 경우 사용.
     *
     * @return 저장된 객체의 접근 URL
     */
    String uploadToKey(InputStream content, long contentLength, String fileName,
                       String contentType, String prefix);

    /** 단일 객체 삭제 (URL 기준). best-effort 호출처에서 예외 처리. */
    void delete(String url);

    /**
     * 여러 객체 일괄 삭제 (URL 목록 기준).
     *
     * @return 삭제 실패한 URL 목록(부분 실패 포함). 호출처는 이 URL 의 메타데이터를 남겨 다음 정리에서
     *         재시도해야 한다 — 먼저 지우면 S3 객체를 다시 못 찾아 영구 누수. 전부 성공이면 빈 목록.
     */
    java.util.List<String> deleteMany(java.util.List<String> urls);

    /** {@code uploads/perm/{user_id}/} 전체 삭제 — 탈퇴 영구 삭제 단계. */
    void deleteByPrefix(String userId);

    /** {@code uploads/perm/{prefix}/} 하위 전체 삭제 — 피드 게시물 단건 정리 등 임의 path prefix. */
    void deleteByPathPrefix(String prefix);
}
