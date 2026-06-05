package site.krip.support;

import site.krip.global.storage.ObjectStorage;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 인메모리 {@link ObjectStorage} — 실제 S3 호출 없이 업로드/삭제 흐름을 검증한다.
 *
 * <p>업로드한 객체 URL 을 set 에 보관하고 삭제 시 제거하므로, 테스트가 "현재 남아있는 객체"를 직접
 * 확인해 누락/orphan 을 검증할 수 있다. 운영 키 스킴({@code uploads/perm/...})을 흉내내 URL 을 만든다.
 */
public class FakeObjectStorage implements ObjectStorage {

    private static final String BASE = "https://test.local/uploads/perm/";

    /** 현재 스토리지에 살아있는 객체 URL 들. */
    public final Set<String> stored = ConcurrentHashMap.newKeySet();

    @Override
    public String uploadPerm(InputStream content, long contentLength, String fileName,
                             String contentType, String prefix) {
        // uuid 대신 호출 순서를 흉내내는 단순 고유 키 — nanoTime 미사용(결정성), 충돌은 set 특성상 무해.
        String url = BASE + prefix + "/" + Integer.toHexString(System.identityHashCode(content))
                + "-" + fileName;
        stored.add(url);
        return url;
    }

    @Override
    public String uploadToKey(InputStream content, long contentLength, String fileName,
                              String contentType, String prefix) {
        String url = BASE + prefix + "/" + fileName;
        stored.add(url);
        return url;
    }

    @Override
    public void delete(String url) {
        stored.remove(url);
    }

    @Override
    public void deleteMany(List<String> urls) {
        urls.forEach(stored::remove);
    }

    @Override
    public void deleteByPrefix(String userId) {
        deleteByPathPrefix(userId);
    }

    @Override
    public void deleteByPathPrefix(String pathPrefix) {
        stored.removeIf(url -> url.contains("/uploads/perm/" + pathPrefix + "/"));
    }
}
