-- Hibernate may have created this column as a MySQL ENUM before the original
-- floor-plan migration was introduced. New detector classifications then fail
-- at insert time with "Data truncated for column 'object_type'". Keep the
-- database extensible in the same way as the JPA mapping's declared length.
ALTER TABLE workplace_detected_objects
    MODIFY COLUMN object_type VARCHAR(40) NOT NULL;
