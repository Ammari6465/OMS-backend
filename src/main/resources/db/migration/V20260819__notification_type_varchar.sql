-- Notification categories evolve with business modules. Convert the legacy
-- native ENUM to a string column so adding a category cannot roll back an
-- otherwise successful business transaction (for example, floor-plan upload).
-- Existing enum values are preserved by MySQL during this widening conversion.

ALTER TABLE notifications
    MODIFY COLUMN type VARCHAR(64) NOT NULL;
