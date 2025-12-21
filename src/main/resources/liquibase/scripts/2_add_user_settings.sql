--liquibase formatted sql

--changeset pkrylov:add_user_settings
--comment: Add user settings
ALTER TABLE users
    ADD COLUMN settings JSONB NOT NULL DEFAULT '{
        "languageCode": "en",
        "tgFeedEntriesNumber": 3
    }'::jsonb;


CREATE INDEX post_url_idx ON post (url);