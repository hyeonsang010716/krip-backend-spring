-- notification 도메인 RDB 스키마 (fcm_token). inbox 는 MongoDB(런타임 인덱스).
-- token UNIQUE — 디바이스 식별자(동일 토큰 재등록은 owner 교체). 탈퇴 시 users FK CASCADE.

CREATE TABLE fcm_token (
    fcm_token_id varchar(50)                 NOT NULL,
    user_id      varchar(50)                 NOT NULL,
    token        varchar(512)                NOT NULL,
    created_at   timestamp(6) with time zone NOT NULL,
    updated_at   timestamp(6) with time zone NOT NULL,
    CONSTRAINT fcm_token_pkey PRIMARY KEY (fcm_token_id),
    CONSTRAINT fk_fcm_token_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT uq_fcm_token_token UNIQUE (token)
);

CREATE INDEX ix_fcm_token_user_id ON fcm_token (user_id);
