-- friend 도메인 RDB 스키마 (friendship / user_block).
-- 친구 관계는 방향 무관 유일 — canonical unique index (LEAST, GREATEST) 로 A→B / B→A 동시 INSERT 차단.
-- 자기 자신 요청/차단 불가 (CHECK). 양 FK 는 users CASCADE.

CREATE TABLE friendship (
    friendship_id varchar(50)               NOT NULL,
    requester_id  varchar(50)               NOT NULL,
    addressee_id  varchar(50)               NOT NULL,
    status        varchar(255)              NOT NULL,
    created_at    timestamp(6) with time zone NOT NULL,
    updated_at    timestamp(6) with time zone NOT NULL,
    CONSTRAINT friendship_pkey PRIMARY KEY (friendship_id),
    CONSTRAINT fk_friendship_requester FOREIGN KEY (requester_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_friendship_addressee FOREIGN KEY (addressee_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT friendship_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_friendship_not_self CHECK (requester_id <> addressee_id)
);

-- 방향 무관 중복 친구 관계 차단 (동시 INSERT 경합 DB 레벨 방어)
CREATE UNIQUE INDEX uq_friendship_canonical_pair
    ON friendship (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id));
CREATE INDEX ix_friendship_requester_status ON friendship (requester_id, status);
CREATE INDEX ix_friendship_addressee_status ON friendship (addressee_id, status);
CREATE INDEX ix_friendship_updated ON friendship (updated_at, friendship_id);

CREATE TABLE user_block (
    block_id   varchar(50)               NOT NULL,
    blocker_id varchar(50)               NOT NULL,
    blocked_id varchar(50)               NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT user_block_pkey PRIMARY KEY (block_id),
    CONSTRAINT uq_user_block_pair UNIQUE (blocker_id, blocked_id),
    CONSTRAINT fk_user_block_blocker FOREIGN KEY (blocker_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_block_blocked FOREIGN KEY (blocked_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_block_not_self CHECK (blocker_id <> blocked_id)
);

CREATE INDEX ix_user_block_blocker ON user_block (blocker_id);
CREATE INDEX ix_user_block_blocked ON user_block (blocked_id);
