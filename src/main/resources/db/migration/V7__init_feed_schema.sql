-- feed 도메인 RDB 스키마 (feed_post / feed_post_like / feed_post_comment).
-- enum 은 varchar + CHECK, 시각은 timestamptz. 유저/게시물 삭제 시 like/comment FK CASCADE.
-- 컴파운드 인덱스 (user_id, visibility, created_at, post_id) 로 모든 페이지네이션 케이스 커버.

CREATE TABLE feed_post (
    post_id              varchar(50)                 NOT NULL,
    user_id              varchar(50)                 NOT NULL,
    visibility           varchar(255)                NOT NULL,
    caption              varchar(100),
    original_url         varchar(500)                NOT NULL,
    thumbnail_small_url  varchar(500)                NOT NULL,
    thumbnail_medium_url varchar(500)                NOT NULL,
    created_at           timestamp(6) with time zone NOT NULL,
    updated_at           timestamp(6) with time zone NOT NULL,
    CONSTRAINT feed_post_pkey PRIMARY KEY (post_id),
    CONSTRAINT fk_feed_post_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT feed_post_visibility_check CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'PUBLIC'))
);

CREATE INDEX ix_feed_post_owner_visibility_created
    ON feed_post (user_id, visibility, created_at, post_id);

CREATE TABLE feed_post_like (
    user_id    varchar(50)                 NOT NULL,
    post_id    varchar(50)                 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT feed_post_like_pkey PRIMARY KEY (user_id, post_id),
    CONSTRAINT fk_feed_post_like_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_feed_post_like_post FOREIGN KEY (post_id)
        REFERENCES feed_post (post_id) ON DELETE CASCADE
);

CREATE INDEX ix_feed_post_like_post_id ON feed_post_like (post_id);

CREATE TABLE feed_post_comment (
    comment_id varchar(50)                 NOT NULL,
    post_id    varchar(50)                 NOT NULL,
    user_id    varchar(50)                 NOT NULL,
    content    varchar(500)                NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT feed_post_comment_pkey PRIMARY KEY (comment_id),
    CONSTRAINT fk_feed_post_comment_post FOREIGN KEY (post_id)
        REFERENCES feed_post (post_id) ON DELETE CASCADE,
    CONSTRAINT fk_feed_post_comment_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_feed_post_comment_min_length CHECK (char_length(content) >= 1)
);

CREATE INDEX ix_feed_post_comment_post_created ON feed_post_comment (post_id, created_at);
