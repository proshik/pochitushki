--liquibase formatted sql

--changeset pkrylov:add_token_revocation
--comment: Let logout actually invalidate issued JWTs instead of only dropping the cookie

-- A JWT is stateless: before this column "log out" cleared the browser cookie and the token
-- itself stayed valid for the rest of its TTL, so a copy taken from a shared machine kept
-- working. Logout now stamps this column, and the interceptor refuses any token issued
-- before it. NULL means "nothing revoked", which is every existing row.
ALTER TABLE users
    ADD COLUMN tokens_valid_after TIMESTAMP;

--rollback ALTER TABLE users DROP COLUMN tokens_valid_after;
