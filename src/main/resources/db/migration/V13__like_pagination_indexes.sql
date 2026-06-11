-- 좋아요 목록 키셋 페이지네이션 인덱스.
--
-- getLikedUsers 는 (post_id 필터 + created_at DESC, user_id DESC 정렬)로 keyset 페이지네이션한다.
-- 단일 컬럼 (post_id) 인덱스로는 정렬을 인덱스로 태우지 못해 post 의 전체 좋아요를 스캔 + full sort 로
-- 퇴화한다(커서가 O(page) -> O(N)). 복합 인덱스로 정렬을 그대로 태워 진짜 O(page) range scan 으로 만든다.
-- (post_id) 단독 필터·count 는 복합 인덱스의 leftmost prefix 로 커버되므로 기존 단일 인덱스는 중복 -> 제거.
-- (V10 이 feed_post 페이지네이션에 적용한 패턴과 동일.)

DROP INDEX IF EXISTS ix_feed_post_like_post_id;
CREATE INDEX ix_feed_post_like_post_created
    ON feed_post_like (post_id, created_at DESC, user_id DESC);

DROP INDEX IF EXISTS ix_tripmate_post_like_post_id;
CREATE INDEX ix_tripmate_post_like_post_created
    ON tripmate_post_like (post_id, created_at DESC, user_id DESC);
