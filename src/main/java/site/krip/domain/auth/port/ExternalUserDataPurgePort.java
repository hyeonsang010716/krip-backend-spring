package site.krip.domain.auth.port;

/**
 * tripmate/tour/friend 등 타 도메인 MongoDB 유저 데이터 정리 (각 도메인 소유).
 * 탈퇴 영구 삭제 시 호출.
 */
public interface ExternalUserDataPurgePort {

    void purgeUserMongoData(String userId);
}
