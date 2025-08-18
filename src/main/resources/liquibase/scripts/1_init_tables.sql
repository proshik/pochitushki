--liquibase formatted sql

--changeset pkrylov:init
--comment: Create initiation tables
CREATE TABLE users
(
    id           BIGSERIAL PRIMARY KEY,
    telegram_id  BIGINT UNIQUE NOT NULL,
    username     TEXT,
    first_name   TEXT,
    last_name    TEXT,
    created_date TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_date TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE post
(
    id           BIGSERIAL PRIMARY KEY,
    title        TEXT,
    url          TEXT      NOT NULL,
    tags         TEXT[],
    user_id      BIGINT REFERENCES users (id),
    created_date TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_date TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (id, user_id)
);

CREATE INDEX post_user_id_idx ON post (user_id);

CREATE TABLE archive_post
(
    id           BIGSERIAL PRIMARY KEY,
    title        TEXT,
    url          TEXT      NOT NULL,
    tags         TEXT[],
    user_id      BIGINT REFERENCES users (id),
    created_date TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_date TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (id, user_id)
);

CREATE INDEX archive_post_user_id_idx ON archive_post (user_id);