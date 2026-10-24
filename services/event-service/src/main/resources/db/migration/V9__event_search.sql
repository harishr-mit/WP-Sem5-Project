-- Indexed catalogue search (D17): title, code and description in one lower-cased text with a GIN trigram index, so
-- LIKE '%q%' doesn't scan the table. pg_trgm is a trusted extension (PostgreSQL 13+).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE event
    ADD COLUMN search_text TEXT GENERATED ALWAYS AS (
        lower(title || ' ' || code || ' ' || coalesce(description, ''))) STORED;

CREATE INDEX ix_event_search_trgm ON event USING gin (search_text gin_trgm_ops);
