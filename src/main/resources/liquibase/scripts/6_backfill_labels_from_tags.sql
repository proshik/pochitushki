--liquibase formatted sql

--changeset pkrylov:backfill_labels_from_tags
--comment: Converge the two tag systems: turn the legacy Pocket-import tags[] arrays into real labels.
--          The tags column is deliberately left in place and untouched, so reverting the code that
--          stopped rendering it restores the old behaviour without a data restore.

-- Names are normalised the same way LabelService.normalise does it: trim, drop a leading '#',
-- collapse whitespace, cap at 40 chars. '|' is split too - the pre-3.7 exporter joined several
-- tags into one array element with that separator, which is why the row mapper still splits on it.
INSERT INTO label(user_id, name)
SELECT DISTINCT user_id, name
FROM (
    SELECT r.user_id,
           left(btrim(regexp_replace(ltrim(btrim(part), '#'), '\s+', ' ', 'g')), 40) AS name
    FROM (
        SELECT user_id, unnest(tags) AS t FROM post WHERE tags IS NOT NULL
        UNION ALL
        SELECT user_id, unnest(tags) AS t FROM archive_post WHERE tags IS NOT NULL
    ) r,
    unnest(string_to_array(r.t, '|')) AS part
) x
WHERE name <> ''
ON CONFLICT (user_id, name) DO NOTHING;

INSERT INTO post_label(post_id, label_id)
SELECT DISTINCT x.id, l.id
FROM (
    SELECT p.id, p.user_id,
           left(btrim(regexp_replace(ltrim(btrim(part), '#'), '\s+', ' ', 'g')), 40) AS name
    FROM post p,
         unnest(p.tags) AS t,
         unnest(string_to_array(t, '|')) AS part
    WHERE p.tags IS NOT NULL
) x
JOIN label l ON l.user_id = x.user_id AND l.name = x.name
WHERE x.name <> ''
ON CONFLICT DO NOTHING;

INSERT INTO archive_post_label(post_id, label_id)
SELECT DISTINCT x.id, l.id
FROM (
    SELECT p.id, p.user_id,
           left(btrim(regexp_replace(ltrim(btrim(part), '#'), '\s+', ' ', 'g')), 40) AS name
    FROM archive_post p,
         unnest(p.tags) AS t,
         unnest(string_to_array(t, '|')) AS part
    WHERE p.tags IS NOT NULL
) x
JOIN label l ON l.user_id = x.user_id AND l.name = x.name
WHERE x.name <> ''
ON CONFLICT DO NOTHING;
