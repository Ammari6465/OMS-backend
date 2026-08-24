-- Links a detected room to the zone it was promoted into, mirroring desk_id.
-- Without it nothing records which rooms have already become zones, so every
-- "Create rooms" re-attempts all of them and collides on the unique code.
ALTER TABLE workplace_detected_objects
    ADD COLUMN zone_id BIGINT NULL;

ALTER TABLE workplace_detected_objects
    ADD CONSTRAINT fk_detected_zone
        FOREIGN KEY (zone_id) REFERENCES workplace_zones (zone_id) ON DELETE SET NULL;
