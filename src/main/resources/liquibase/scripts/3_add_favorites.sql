--liquibase formatted sql

--changeset pkrylov:add-favorites
--comment: Add is_favorite column to post and archive_post tables (backwards compatible, default false)
ALTER TABLE post ADD COLUMN is_favorite BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE archive_post ADD COLUMN is_favorite BOOLEAN NOT NULL DEFAULT false;
