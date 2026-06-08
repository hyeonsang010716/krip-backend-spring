-- 친구 검색 user_id 부분일치 가속.
-- 검색은 (lower(user_id) LIKE '%kw%' OR lower(user_name) LIKE '%kw%') 였는데, 두 분기가 서로 다른
-- 테이블(users vs user_detail_inform)이라 OR 가 단일 인덱스 스캔을 못 타 users 풀스캔이 됐다.
-- 서비스가 닉네임 분기를 user_name trigram(ix_user_detail_user_name_trgm)으로 user_id 로 먼저 해석(IN-list)해
-- OR 를 모두 users.user_id 한 컬럼으로 모으고, 그 user_id 의 leading wildcard LIKE 를 이 trigram GIN 으로
-- 인덱스화해 BitmapOr 인덱스 스캔이 가능해진다. pg_trgm 확장은 V11 에서 생성됨.
CREATE INDEX ix_users_user_id_trgm
    ON users USING gin (lower(user_id) gin_trgm_ops);
