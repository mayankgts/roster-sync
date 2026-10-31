ALTER TABLE outbox ADD COLUMN dead_lettered_at TIMESTAMPTZ;
ALTER TABLE outbox ADD COLUMN last_error VARCHAR(200);

-- The relay's query now also excludes dead-lettered rows, so the index must match it.
DROP INDEX idx_outbox_unpublished;
CREATE INDEX idx_outbox_pending ON outbox (id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;