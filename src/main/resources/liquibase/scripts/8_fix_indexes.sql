--liquibase formatted sql

--changeset pkrylov:fix_post_indexes
--comment: Match the indexes to the queries the app actually runs (shelf paging, duplicate lookup)

-- Every shelf is "this user's rows, newest first, LIMIT/OFFSET". With only post(user_id)
-- Postgres reads the whole user partition and sorts it on each page.
CREATE INDEX IF NOT EXISTS post_user_created_idx ON post (user_id, created_date DESC);
CREATE INDEX IF NOT EXISTS archive_post_user_created_idx ON archive_post (user_id, created_date DESC);

-- The duplicate check is an exact match on (user_id, url) now, not `url ILIKE 'prefix%'`,
-- so the old url-only index served nothing and cost every insert.
DROP INDEX IF EXISTS post_url_idx;
CREATE INDEX IF NOT EXISTS post_user_url_idx ON post (user_id, url);
CREATE INDEX IF NOT EXISTS archive_post_user_url_idx ON archive_post (user_id, url);

-- UNIQUE(user_id, name) already indexes user_id as its leading column.
DROP INDEX IF EXISTS label_user_id_idx;

--rollback DROP INDEX IF EXISTS post_user_created_idx;
--rollback DROP INDEX IF EXISTS archive_post_user_created_idx;
--rollback DROP INDEX IF EXISTS post_user_url_idx;
--rollback DROP INDEX IF EXISTS archive_post_user_url_idx;
--rollback CREATE INDEX post_url_idx ON post (url);
--rollback CREATE INDEX label_user_id_idx ON label (user_id);
