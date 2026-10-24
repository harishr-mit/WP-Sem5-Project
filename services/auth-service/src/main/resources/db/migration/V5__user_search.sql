-- Indexed admin user search (D17). One lower-cased text per user; the GIN trigram index serves LIKE '%q%' (a leading
-- wildcard, which a b-tree can't) and similarity() for ranking. pg_trgm is a trusted extension: the database owner
-- may create it (PostgreSQL 13+).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE app_user
    ADD COLUMN search_text TEXT GENERATED ALWAYS AS (
        lower(full_name || ' ' || email || ' ' || coalesce(register_number, ''))) STORED;

CREATE INDEX ix_app_user_search_trgm ON app_user USING gin (search_text gin_trgm_ops);
