-- tripmate 도메인 RDB 스키마 (tripmate_post / tripmate_post_image / tripmate_post_like).
-- enum 은 varchar + CHECK, 시각은 timestamptz, 자식 FK 는 ON DELETE CASCADE
-- (게시글/유저 삭제 시 이미지·좋아요 일괄 정리). MongoDB 컬렉션(tripmate_image,
-- tripmate_post_draft, tripmate_search_history)은 auto-index-creation 으로 런타임 생성.

CREATE TABLE tripmate_post (
    post_id           varchar(50)               NOT NULL,
    user_id           varchar(50)               NOT NULL,
    title             varchar(100)              NOT NULL,
    content           varchar(500)              NOT NULL,
    preferred_age_min integer                   NOT NULL,
    preferred_age_max integer                   NOT NULL,
    preferred_gender  varchar(255)              NOT NULL,
    region            varchar(100)              NOT NULL,
    travel_start_date date                      NOT NULL,
    travel_end_date   date                      NOT NULL,
    companion_type    varchar(255)              NOT NULL,
    is_displayed      boolean                   NOT NULL,
    created_at        timestamp(6) with time zone NOT NULL,
    updated_at        timestamp(6) with time zone NOT NULL,
    CONSTRAINT tripmate_post_pkey PRIMARY KEY (post_id),
    CONSTRAINT fk_tripmate_post_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT tripmate_post_preferred_gender_check CHECK (preferred_gender IN ('MALE','FEMALE','ANY')),
    CONSTRAINT tripmate_post_companion_type_check CHECK (companion_type IN ('FRIEND','FAMILY','COUPLE','SOLE')),
    CONSTRAINT ck_preferred_age_range CHECK (preferred_age_min <= preferred_age_max),
    CONSTRAINT ck_travel_date_range CHECK (travel_start_date <= travel_end_date),
    CONSTRAINT ck_content_min_length CHECK (char_length(content) >= 10)
);

CREATE TABLE tripmate_post_image (
    image_id    varchar(50)  NOT NULL,
    post_id     varchar(50)  NOT NULL,
    image_url   varchar(500) NOT NULL,
    image_order integer      NOT NULL,
    CONSTRAINT tripmate_post_image_pkey PRIMARY KEY (image_id),
    CONSTRAINT fk_tripmate_post_image_post FOREIGN KEY (post_id)
        REFERENCES tripmate_post (post_id) ON DELETE CASCADE
);

CREATE TABLE tripmate_post_like (
    user_id    varchar(50)               NOT NULL,
    post_id    varchar(50)               NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT tripmate_post_like_pkey PRIMARY KEY (user_id, post_id),
    CONSTRAINT fk_tripmate_post_like_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_tripmate_post_like_post FOREIGN KEY (post_id)
        REFERENCES tripmate_post (post_id) ON DELETE CASCADE
);

CREATE INDEX ix_tripmate_post_user_id ON tripmate_post (user_id);
CREATE INDEX ix_tripmate_post_region ON tripmate_post (region);
CREATE INDEX ix_tripmate_post_travel_dates ON tripmate_post (travel_start_date, travel_end_date);
CREATE INDEX ix_tripmate_post_created ON tripmate_post (created_at, post_id);
CREATE INDEX ix_tripmate_post_image_post_id ON tripmate_post_image (post_id);
CREATE INDEX ix_tripmate_post_like_post_id ON tripmate_post_like (post_id);
