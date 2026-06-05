-- tour 도메인 즐겨찾기 스키마 (favorite_place).
-- place_id 는 MongoDB Place 참조라 FK 제약 없음. (user_id, place_id) UNIQUE 로 중복 방지.
-- 유저 삭제 시 즐겨찾기 일괄 정리(FK ON DELETE CASCADE).

CREATE TABLE favorite_place (
    favorite_id varchar(50)                 NOT NULL,
    user_id     varchar(50)                 NOT NULL,
    place_id    varchar(255)                NOT NULL,
    created_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT favorite_place_pkey PRIMARY KEY (favorite_id),
    CONSTRAINT fk_favorite_place_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT uq_user_favorite_place UNIQUE (user_id, place_id)
);

CREATE INDEX ix_favorite_user_id ON favorite_place (user_id);
CREATE INDEX ix_favorite_place_id ON favorite_place (place_id);
