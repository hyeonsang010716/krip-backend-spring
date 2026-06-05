-- chat 도메인 RDB 스키마 (chat_room / chat_room_member).
-- 유저 FK 는 ON DELETE SET NULL(대화/방 보존, 탈퇴 자리 NULL), 단 chat_room_member.user_id 는 CASCADE.
-- effective_last_at 은 GENERATED STORED — 방 리스트 정렬 인덱스 1개로 끝.
-- chat_message 는 MongoDB(별도) — server_seq UNIQUE 인덱스는 런타임 생성.

CREATE TABLE chat_room (
    chat_room_id            varchar(50)                 NOT NULL,
    type                    varchar(255)                NOT NULL,
    title                   varchar(100),
    creator_id              varchar(50),
    direct_user_a_id        varchar(50),
    direct_user_b_id        varchar(50),
    last_message_id         varchar(50),
    last_message_server_seq bigint,
    last_message_at         timestamp(6) with time zone,
    created_at              timestamp(6) with time zone NOT NULL,
    updated_at              timestamp(6) with time zone NOT NULL,
    effective_last_at       timestamp(6) with time zone
        GENERATED ALWAYS AS (COALESCE(last_message_at, created_at)) STORED,
    CONSTRAINT chat_room_pkey PRIMARY KEY (chat_room_id),
    CONSTRAINT fk_chat_room_creator FOREIGN KEY (creator_id)
        REFERENCES users (user_id) ON DELETE SET NULL,
    CONSTRAINT fk_chat_room_direct_a FOREIGN KEY (direct_user_a_id)
        REFERENCES users (user_id) ON DELETE SET NULL,
    CONSTRAINT fk_chat_room_direct_b FOREIGN KEY (direct_user_b_id)
        REFERENCES users (user_id) ON DELETE SET NULL,
    CONSTRAINT chat_room_type_check CHECK (type IN ('DIRECT', 'GROUP')),
    CONSTRAINT ck_chat_room_direct_pair_shape CHECK (
        (type = 'GROUP' AND direct_user_a_id IS NULL AND direct_user_b_id IS NULL)
        OR (type = 'DIRECT' AND direct_user_a_id IS NOT NULL AND direct_user_b_id IS NOT NULL
            AND direct_user_a_id < direct_user_b_id)
        OR (type = 'DIRECT' AND (direct_user_a_id IS NULL OR direct_user_b_id IS NULL))
    )
);

-- DIRECT 는 (a,b) 쌍당 최대 1개 — DB 레벨 race 직렬화.
CREATE UNIQUE INDEX uq_chat_room_direct_pair ON chat_room (direct_user_a_id, direct_user_b_id)
    WHERE type = 'DIRECT';
CREATE INDEX ix_chat_room_effective_last_at ON chat_room (effective_last_at);

CREATE TABLE chat_room_member (
    chat_room_id                 varchar(50) NOT NULL,
    user_id                      varchar(50) NOT NULL,
    joined_at                    timestamp(6) with time zone NOT NULL,
    last_read_message_server_seq bigint,
    last_read_at                 timestamp(6) with time zone,
    is_left                      boolean     NOT NULL DEFAULT false,
    notification_muted           boolean,
    CONSTRAINT chat_room_member_pkey PRIMARY KEY (chat_room_id, user_id),
    CONSTRAINT fk_chat_room_member_room FOREIGN KEY (chat_room_id)
        REFERENCES chat_room (chat_room_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_room_member_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE
);

-- 유저별 활성 방 조회용 — is_left=false 부분 인덱스로 크기 최소화.
CREATE INDEX ix_chat_room_member_user_active ON chat_room_member (user_id)
    WHERE is_left = false;
