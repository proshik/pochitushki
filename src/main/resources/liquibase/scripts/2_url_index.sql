--liquibase formatted sql

--changeset pkrylov:url_index
--comment: Add index for url attribute in post table
CREATE INDEX post_url_idx ON post (url);