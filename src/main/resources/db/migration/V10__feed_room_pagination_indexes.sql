-- 페이지네이션 인덱스 정합화.
--
-- feed_post: 기존 (user_id, visibility, created_at, post_id) 는 visibility IN(다중값) 조회 시
--   created_at 정렬을 인덱스로 만들 수 없어 bitmap scan + full sort 로 퇴화한다(커서가 O(page)→O(n log n)).
--   visibility 를 선두에서 빼 (user_id, created_at DESC, post_id DESC) 로 재정렬하면 단일/다중 가시성
--   모두에서 커서 페이지네이션이 인덱스 정렬을 그대로 타고, visibility 는 힙 필터로 처리된다.
--
-- chat_room: 방 리스트 정렬에 chat_room_id tie-breaker 를 더해 동률 effective_last_at 의 비결정 순서와
--   커서 도입 시의 행 skip/중복을 방지한다.

DROP INDEX IF EXISTS ix_feed_post_owner_visibility_created;
CREATE INDEX ix_feed_post_owner_created
    ON feed_post (user_id, created_at DESC, post_id DESC);

DROP INDEX IF EXISTS ix_chat_room_effective_last_at;
CREATE INDEX ix_chat_room_effective_last_at
    ON chat_room (effective_last_at DESC, chat_room_id DESC);
