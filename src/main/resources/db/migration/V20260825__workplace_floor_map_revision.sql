-- Floor-map optimistic lock. Two administrators editing the same floor map must
-- not silently overwrite each other: every desk / zone / room / detected-object
-- / plan change bumps this counter, and a batch map save must present the value
-- it started from. A stale value is rejected with HTTP 409 rather than applied.
--
-- Separate from the row `version` on workplace_floors: `version` guards edits to
-- the floor record itself (name, display order, plan metadata), while
-- `map_revision` guards the whole map's contents, which live in child tables.

ALTER TABLE workplace_floors
    ADD COLUMN map_revision BIGINT NOT NULL DEFAULT 0;
