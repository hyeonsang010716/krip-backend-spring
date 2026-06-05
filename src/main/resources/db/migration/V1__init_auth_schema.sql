-- auth 도메인 초기 스키마.
-- enum 은 varchar + CHECK (Hibernate @Enumerated(STRING) 매핑), 시각은 timestamptz,
-- 자식 FK 는 ON DELETE CASCADE (하드 탈퇴 시 users 삭제로 일괄 정리).

CREATE TABLE users (
    user_id          varchar(50)               NOT NULL,
    auth_provider    varchar(255)              NOT NULL,
    auth_provider_id varchar(255)              NOT NULL,
    status           varchar(255)              NOT NULL,
    notification_muted boolean,
    created_at       timestamp(6) with time zone NOT NULL,
    updated_at       timestamp(6) with time zone NOT NULL,
    CONSTRAINT users_pkey PRIMARY KEY (user_id),
    CONSTRAINT uq_provider_account UNIQUE (auth_provider, auth_provider_id),
    CONSTRAINT users_auth_provider_check CHECK (auth_provider = 'GOOGLE'),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);

CREATE TABLE user_detail_inform (
    user_id           varchar(50)   NOT NULL,
    email             varchar(255)  NOT NULL,
    user_name         varchar(100)  NOT NULL,
    phone_number      varchar(20),
    age               integer       NOT NULL,
    gender            varchar(255)  NOT NULL,
    nationality       varchar(50)   NOT NULL,
    profile_image_url varchar(2048),
    CONSTRAINT user_detail_inform_pkey PRIMARY KEY (user_id),
    CONSTRAINT user_detail_inform_gender_check CHECK (gender IN ('MALE', 'FEMALE')),
    CONSTRAINT fk_user_detail_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE
);

CREATE TABLE user_travel_style (
    id      varchar(50)  NOT NULL,
    user_id varchar(50)  NOT NULL,
    style   varchar(255) NOT NULL,
    CONSTRAINT user_travel_style_pkey PRIMARY KEY (id),
    CONSTRAINT user_travel_style_style_check CHECK (style IN (
        'ACTIVITY','FAMOUS_ATTRACTIONS','HEALING','CULTURE_HISTORY','SHOPPING','FOOD_TOUR',
        'PHOTO_AESTHETIC','FESTIVAL_EVENT','NATURE','TRADITIONAL','TREKKING','HIDDEN_GEMS',
        'ART_EXHIBITION','THEME_PARK','FOOD_HALAL','FOOD_VEGETARIAN','FOODIE','CAFE_LOVER',
        'DENSITY_RELAXED','DENSITY_PACKED','BUDGET_SAVING','BUDGET_MODERATE','BUDGET_PREMIUM',
        'WALKING_LOW','WALKING_MEDIUM','WALKING_HIGH','TRANSPORT_PUBLIC','TRANSPORT_CAR',
        'TRANSPORT_TAXI','COMPANION_INDEPENDENT','COMPANION_TOGETHER','COMPANION_FLEXIBLE',
        'DAYTIME','NIGHTLIFE','NIGHT_VIEW','COMMUNICATION_HIGH','COMMUNICATION_LOW',
        'PLANNER','SPONTANEOUS','FOLLOWER')),
    CONSTRAINT fk_user_travel_style_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE
);

CREATE INDEX ix_provider_lookup ON users (auth_provider, auth_provider_id);
CREATE INDEX ix_user_detail_email ON user_detail_inform (email);
CREATE INDEX ix_user_travel_style_user_id ON user_travel_style (user_id);

-- 탐색 목록용 partial index — ACTIVE 가 압도적 다수라 일반 인덱스는
-- planner 가 스킵하므로 partial 로 강제 사용. `WHERE status='ACTIVE' ORDER BY created_at DESC, user_id DESC` 커버.
CREATE INDEX ix_users_active_created ON users (created_at, user_id) WHERE status = 'ACTIVE';
