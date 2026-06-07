-- 낙관적 락(@Version) 컬럼 추가.
-- friendship: 동시 수락/차단 lost-update 차단. tour_plan_item: 같은 카드 동시 교체/이동 차단.
-- 기존 행은 DEFAULT 0 으로 채우고, 이후 JPA 가 UPDATE/DELETE 시 version 을 증분·검증한다.

ALTER TABLE friendship ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE tour_plan_item ADD COLUMN version bigint NOT NULL DEFAULT 0;
