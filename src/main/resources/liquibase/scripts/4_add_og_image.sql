--liquibase formatted sql

--changeset pkrylov:add_og_image
--comment: Store og:image url captured at save time; rendered as the cover "jacket" in the web UI
ALTER TABLE post ADD COLUMN og_image_url TEXT;
ALTER TABLE archive_post ADD COLUMN og_image_url TEXT;
