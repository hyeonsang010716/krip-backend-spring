-- 친구 목록 / 피드 댓글 페이지네이션 인덱스 정합화 (V10/V13 과 동일 패턴).
--
-- friendship: 친구목록(requester OR addressee + status) / 받은요청(addressee+status) / 보낸요청(requester+status)
--   은 모두 ORDER BY updated_at DESC, friendship_id DESC 로 keyset 페이지네이션한다. 기존
--   (requester_id, status) / (addressee_id, status) 인덱스는 updated_at 정렬을 인덱스로 태우지 못해
--   BitmapOr + full sort 로 퇴화한다(커서가 O(page) -> O(N log N)). 정렬 컬럼을 인덱스 후미에 더하면
--   받은/보낸은 단일 index range scan, 친구목록은 두 인덱스 MergeAppend 로 O(page) 가 된다.
--   기존 (col, status) 인덱스는 새 복합 인덱스의 leftmost prefix 로 흡수되고(findBetween/countAccepted 등 커버),
--   전역 (updated_at, friendship_id) 인덱스는 user/status 필터와 결합 불가라 어떤 쿼리도 쓰지 않아 dead -> 모두 제거.

DROP INDEX IF EXISTS ix_friendship_requester_status;
DROP INDEX IF EXISTS ix_friendship_addressee_status;
DROP INDEX IF EXISTS ix_friendship_updated;
CREATE INDEX ix_friendship_requester_status_updated
    ON friendship (requester_id, status, updated_at DESC, friendship_id DESC);
CREATE INDEX ix_friendship_addressee_status_updated
    ON friendship (addressee_id, status, updated_at DESC, friendship_id DESC);

-- feed_post_comment: 댓글 목록도 (post_id 필터 + created_at DESC, comment_id DESC) keyset 인데 인덱스가
--   (post_id, created_at) 라 comment_id tiebreak 가 인덱스 밖 -> 동률 정렬/경계에 sort·heap 필터가 붙는다.
--   후미에 comment_id 를 더해 정확히 받친다. (post_id, created_at) 단독 조회는 leftmost prefix 로 커버.

DROP INDEX IF EXISTS ix_feed_post_comment_post_created;
CREATE INDEX ix_feed_post_comment_post_created
    ON feed_post_comment (post_id, created_at DESC, comment_id DESC);
