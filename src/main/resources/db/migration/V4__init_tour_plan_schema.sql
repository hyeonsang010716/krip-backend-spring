-- tour 플랜 RDB 스키마 (tour_plan / tour_plan_item).
-- public/share 가 read-only 로 사용하는 슬라이스(전체 tour CRUD/추천은 추후 포팅).
-- 시각은 timestamptz, 자식 FK 는 ON DELETE CASCADE(플랜/유저 삭제 시 카드 일괄 정리).
-- place_id 는 MongoDB Place 참조라 FK 제약 없음. MongoDB place 컬렉션은 별도 시드/인덱스.

CREATE TABLE tour_plan (
    plan_id     varchar(50)                 NOT NULL,
    user_id     varchar(50)                 NOT NULL,
    title       varchar(100),
    travel_days integer                     NOT NULL,
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT tour_plan_pkey PRIMARY KEY (plan_id),
    CONSTRAINT fk_tour_plan_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_tour_plan_travel_days_min CHECK (travel_days >= 1)
);

CREATE INDEX ix_tour_plan_user_id ON tour_plan (user_id);

CREATE TABLE tour_plan_item (
    item_id      varchar(50)                 NOT NULL,
    plan_id      varchar(50)                 NOT NULL,
    day_number   integer                     NOT NULL,
    position     double precision            NOT NULL,
    place_id     varchar(255)                NOT NULL,
    display_name varchar(255)                NOT NULL,
    address      varchar(500)                NOT NULL,
    visit_time   varchar(5),
    created_at   timestamp(6) with time zone NOT NULL,
    updated_at   timestamp(6) with time zone NOT NULL,
    CONSTRAINT tour_plan_item_pkey PRIMARY KEY (item_id),
    CONSTRAINT fk_tour_plan_item_plan FOREIGN KEY (plan_id)
        REFERENCES tour_plan (plan_id) ON DELETE CASCADE,
    CONSTRAINT uq_tour_plan_item_position UNIQUE (plan_id, day_number, position),
    CONSTRAINT ck_tour_plan_item_day_min CHECK (day_number >= 1)
);

CREATE INDEX ix_tour_plan_item_place_id ON tour_plan_item (place_id);
