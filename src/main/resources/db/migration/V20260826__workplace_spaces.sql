-- Phase 2: proper room/space modelling.
--
-- Rooms were previously flattened into generic zones, which lost their type and
-- geometry (the frontend re-guessed a room's type by matching a zone code with a
-- temporary scan overlay). A WorkplaceSpace is the typed, shaped successor: it
-- stores its own space_type and polygon so a cabin stays a cabin and keeps its
-- outline after recognition overlays are cleared. Zones remain as colour-coded
-- groupings; existing data is untouched.

CREATE TABLE IF NOT EXISTS workplace_spaces (
 space_id BIGINT NOT NULL AUTO_INCREMENT,
 floor_id BIGINT NOT NULL,
 zone_id BIGINT NULL,
 space_type VARCHAR(30) NOT NULL,
 name VARCHAR(200) NOT NULL,
 space_code VARCHAR(50) NOT NULL,
 polygon VARCHAR(4000),
 bbox_x DOUBLE NOT NULL DEFAULT 0,
 bbox_y DOUBLE NOT NULL DEFAULT 0,
 bbox_width DOUBLE NOT NULL DEFAULT 0,
 bbox_height DOUBLE NOT NULL DEFAULT 0,
 rotation INT NOT NULL DEFAULT 0,
 capacity INT NULL,
 display_colour VARCHAR(20) NOT NULL DEFAULT '#64748b',
 bookable TINYINT(1) NOT NULL DEFAULT 0,
 is_accessible TINYINT(1) NOT NULL DEFAULT 0,
 -- department_id references departments(dept_id) logically; kept unconstrained
 -- (like detected-object links) so the space domain stays decoupled.
 department_id BIGINT NULL,
 amenities VARCHAR(1000),
 equipment_tags VARCHAR(1000),
 notes VARCHAR(2000),
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(space_id),
 UNIQUE KEY uq_workplace_space_floor_code(floor_id,space_code),
 KEY idx_workplace_space_floor(floor_id,is_deleted),
 KEY idx_workplace_space_zone(zone_id),
 CONSTRAINT fk_workplace_space_floor FOREIGN KEY(floor_id) REFERENCES workplace_floors(floor_id),
 CONSTRAINT fk_workplace_space_zone FOREIGN KEY(zone_id) REFERENCES workplace_zones(zone_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A desk may sit inside a space (the room that contains it). Soft-deleting the
-- space clears the link in the service; ON DELETE SET NULL covers a hard delete.
ALTER TABLE workplace_desks
    ADD COLUMN space_id BIGINT NULL;
ALTER TABLE workplace_desks
    ADD CONSTRAINT fk_workplace_desk_space FOREIGN KEY(space_id) REFERENCES workplace_spaces(space_id) ON DELETE SET NULL;
CREATE INDEX idx_workplace_desk_space ON workplace_desks(space_id);

-- The typed successor to detected_objects.zone_id: a promoted room now links to
-- the space it became, so a re-scan does not offer it for promotion again.
ALTER TABLE workplace_detected_objects
    ADD COLUMN space_id BIGINT NULL;
ALTER TABLE workplace_detected_objects
    ADD CONSTRAINT fk_detected_space FOREIGN KEY(space_id) REFERENCES workplace_spaces(space_id) ON DELETE SET NULL;
