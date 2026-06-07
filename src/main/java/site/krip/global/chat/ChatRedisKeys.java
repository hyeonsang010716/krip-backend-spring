package site.krip.global.chat;

/**
 * 채팅 도메인 Redis 키 / TTL 상수.
 *
 * <p>키 문자열의 단일 출처. DB 0(hot)과 DB 1(dedupe)을 분리해 사용한다.
 */
public final class ChatRedisKeys {

    private ChatRedisKeys() {
    }

    // ──────────────────── TTL (seconds) ────────────────────
    public static final long SESSION_TTL = 90;          // sess / ws_route / sessions ZSET 갱신 주기와 동일
    public static final long ROOM_MEMBERS_TTL = 600;    // RDB fallback 전제 — 짧아도 안전
    public static final long ROOM_BLOCKS_TTL = 600;     // friend hook 미호출 시 stale 상한
    public static final long RATE_LIMIT_TTL = 1;        // 1초 윈도우
    public static final long DEDUPE_TTL = 600;          // 클라 재전송 최대 갭보다 충분히 길게
    public static final long NODE_TTL = 90;             // chat:nodes ZSET — SESSION_TTL 과 동일 주기로 갱신
    public static final long UNREAD_TTL = 604800;       // unread 캐시 backstop(7일) — 진실에서 재계산되므로 만료 무해

    // ──────────────────── 임계값 ────────────────────
    public static final int RATE_LIMIT_THRESHOLD = 10;  // 초당 메시지 상한
    public static final int MAX_SESSIONS_PER_USER = 10; // 유저당 동시 세션 상한

    // force_jump 와 recover 의 base gap — 같게 맞춰 간섭 최소화.
    public static final int SEQ_FORCE_JUMP_GAP = 1000;
    public static final int SEQ_FORCE_JUMP_JITTER_MAX = 10000;
    public static final int SEQ_RECOVER_GAP = 1000;

    // ──────────────────── 키 빌더 — DB 0 (hot) ────────────────────
    public static String sess(String sessionId) {
        return "sess:" + sessionId;
    }

    public static String sessions(String userId) {
        return "sessions:" + userId;
    }

    public static String wsRoute(String sessionId) {
        return "ws_route:" + sessionId;
    }

    public static String unread(String userId) {
        return "unread:" + userId;
    }

    public static String roomSeq(String roomId) {
        return "room:seq:" + roomId;
    }

    public static String roomMembers(String roomId) {
        return "room:members:" + roomId;
    }

    public static String roomBlocks(String roomId) {
        return "room:blocks:" + roomId;
    }

    public static String rateMsg(String userId) {
        return "rate:msg:" + userId;
    }

    /** 노드별 Pub/Sub 채널 — node_channel 모드에서 각 노드가 자기 채널만 구독. */
    public static String nodeChannel(String nodeId) {
        return "node:" + nodeId;
    }

    public static final String DIRTY_CHAT_ROOM_KEY = "dirty:chat_room"; // reconcile worker 가 소비하는 SET
    public static final String NODES_ZSET_KEY = "chat:nodes";          // ZSET: score=만료시각ms, member=node_id

    // ──────────────────── 키 빌더 — DB 1 (dedupe 격리) ────────────────────
    public static String dedupe(String userId, String clientMsgId) {
        return "dedupe:" + userId + ":" + clientMsgId;
    }
}
