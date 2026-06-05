package site.krip.global.storage;

/**
 * 도메인 Object Storage prefix.
 *
 * <p>모든 prefix 의 첫 segment 는 {@code user_id} — 탈퇴 시 {@code deleteByPrefix(user_id)} 가
 * {@code uploads/perm/{user_id}/*} 전체를 한 번에 정리한다.
 */
public final class StoragePrefix {

    private StoragePrefix() {
    }

    /** 프로필 이미지 prefix (유저당 1장 정책). */
    public static String profilePrefix(String userId) {
        return userId + "/profile";
    }

    /** 여행 메이트 게시글 이미지 prefix. */
    public static String postPrefix(String userId) {
        return userId + "/posts";
    }

    /** 피드 게시물 이미지 prefix — 게시물당 변형 3종(original/small/medium)을 이 prefix 아래 고정 파일명으로. */
    public static String feedPostPrefix(String userId, String postId) {
        return userId + "/feed/" + postId;
    }
}
