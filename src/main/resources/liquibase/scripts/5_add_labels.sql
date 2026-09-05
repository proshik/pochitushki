--liquibase formatted sql

--changeset pkrylov:add_labels
--comment: User-owned labels, linked to unread and archived posts through separate join tables
CREATE TABLE label
(
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT      NOT NULL,
    created_date TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, name)
);

CREATE INDEX label_user_id_idx ON label (user_id);

-- Two join tables rather than one, because post and archive_post are two tables:
-- a single table could not carry a foreign key to both, and the id spaces overlap.
CREATE TABLE post_label
(
    post_id  BIGINT NOT NULL REFERENCES post (id) ON DELETE CASCADE,
    label_id BIGINT NOT NULL REFERENCES label (id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, label_id)
);

CREATE INDEX post_label_label_id_idx ON post_label (label_id);

CREATE TABLE archive_post_label
(
    post_id  BIGINT NOT NULL REFERENCES archive_post (id) ON DELETE CASCADE,
    label_id BIGINT NOT NULL REFERENCES label (id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, label_id)
);

CREATE INDEX archive_post_label_label_id_idx ON archive_post_label (label_id);
