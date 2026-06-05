package site.krip.global.support;

import java.time.Instant;
import java.util.UUID;

/**
 * 도메인 엔티티 ID 생성기.
 *
 * <p>형식: {@code {PREFIX}_{unix_epoch_seconds}_{uuid_hex8}} — timestamp prefix 라
 * 문자열 정렬 = 시간순. snowflake/UUID 가 아닌 하이브리드 스킴이므로 JPA 자동 생성 대신
 * 엔티티 생성 시점에 직접 부여한다.
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    private static String generate(String prefix) {
        long timestamp = Instant.now().getEpochSecond();
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "_" + timestamp + "_" + unique;
    }

    public static String userId() {
        return generate("USER");
    }

    public static String travelStyleId() {
        return generate("TS");
    }

    public static String tripmatePostId() {
        return generate("TMP");
    }

    public static String tripmateImageId() {
        return generate("TMI");
    }

    public static String friendshipId() {
        return generate("FS");
    }

    public static String userBlockId() {
        return generate("BLK");
    }

    public static String tourPlanId() {
        return generate("TP");
    }

    public static String tourPlanItemId() {
        return generate("TPI");
    }

    public static String favoritePlaceId() {
        return generate("FP");
    }

    public static String chatRoomId() {
        return generate("CR");
    }

    public static String messageId() {
        return generate("MSG");
    }

    public static String sessionId() {
        return generate("WS");
    }

    public static String feedPostId() {
        return generate("FDP");
    }

    public static String feedPostCommentId() {
        return generate("FDC");
    }

    public static String fcmTokenId() {
        return generate("FCM");
    }
}
