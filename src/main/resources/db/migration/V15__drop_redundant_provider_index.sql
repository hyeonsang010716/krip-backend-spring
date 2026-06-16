-- ix_provider_lookup 제거 — uq_provider_account UNIQUE(auth_provider, auth_provider_id)가
-- 동일 컬럼·순서의 인덱스를 이미 생성하므로 완전 중복(쓰기 증폭·저장공간 낭비)이었다.
DROP INDEX IF EXISTS ix_provider_lookup;
