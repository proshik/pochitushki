--liquibase formatted sql

--changeset pkrylov:add_pdf_cache
--comment: Cache generated PDFs so asking for the same article twice does not refetch and re-render it.

-- Keyed by (user, url, engine) rather than by post id, which the plan sketched:
--  * post and archive_post are separate id spaces, so a post_id alone is ambiguous,
--    and a foreign key would need two tables like the label links do;
--  * archiving re-inserts the row under a new id, which would throw the cache away
--    on a move even though the article is unchanged;
--  * the PDF is a render of a URL by an engine — that is the real key.
-- user_id keeps one reader's fetch from being served to another.
CREATE TABLE pdf_cache
(
    user_id      BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    url_hash     TEXT      NOT NULL,
    engine       TEXT      NOT NULL,
    url          TEXT      NOT NULL,
    content      BYTEA     NOT NULL,
    created_date TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, url_hash, engine)
);

CREATE INDEX pdf_cache_created_date_idx ON pdf_cache (created_date);
