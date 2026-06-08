-- tripmate 검색(제목/내용/작성자 닉네임)의 선행 와일드카드 LIKE '%kw%' 를 인덱스로 가속.
-- B-tree 는 leading wildcard 를 못 타므로 pg_trgm GIN 으로 substring ILIKE 를 인덱스화한다.
-- 인덱스 식은 쿼리의 lower(col) LIKE lower(:pattern) 과 일치시킨다.
-- 작성자 닉네임 매칭은 서비스가 이 인덱스로 user_id 를 먼저 해석해, 게시글 쿼리의 OR 분기를
-- 모두 tripmate_post 컬럼(title/content/user_id)으로 모아 BitmapOr 인덱스 스캔이 가능하게 한다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_tripmate_post_title_trgm
    ON tripmate_post USING gin (lower(title) gin_trgm_ops);

CREATE INDEX ix_tripmate_post_content_trgm
    ON tripmate_post USING gin (lower(content) gin_trgm_ops);

CREATE INDEX ix_user_detail_user_name_trgm
    ON user_detail_inform USING gin (lower(user_name) gin_trgm_ops);
