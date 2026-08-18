-- Production-safe, idempotent workplace migration for existing installations.
-- No existing table or row is dropped or recreated.

CREATE TABLE IF NOT EXISTS workplace_offices (
 office_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, company_id BIGINT UNSIGNED NOT NULL, name VARCHAR(200) NOT NULL, office_code VARCHAR(50) NOT NULL, address_text VARCHAR(500), city VARCHAR(100), country VARCHAR(100), time_zone VARCHAR(60) NOT NULL DEFAULT 'UTC', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT UNSIGNED, updated_by BIGINT UNSIGNED, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(office_id), UNIQUE KEY uq_workplace_office_company_code(company_id,office_code), KEY idx_workplace_office_company(company_id,is_deleted), CONSTRAINT fk_workplace_office_company FOREIGN KEY(company_id) REFERENCES companies(company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workplace_buildings (
 building_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, office_id BIGINT UNSIGNED NOT NULL, name VARCHAR(200) NOT NULL, building_code VARCHAR(50) NOT NULL, description VARCHAR(1000), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT UNSIGNED, updated_by BIGINT UNSIGNED, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(building_id), UNIQUE KEY uq_workplace_building_office_code(office_id,building_code), KEY idx_workplace_building_office(office_id,is_deleted), CONSTRAINT fk_workplace_building_office FOREIGN KEY(office_id) REFERENCES workplace_offices(office_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workplace_floors (
 floor_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, building_id BIGINT UNSIGNED NOT NULL, name VARCHAR(200) NOT NULL, display_order INT NOT NULL DEFAULT 0,
 plan_storage_ref VARCHAR(255), plan_original_name VARCHAR(255), plan_media_type VARCHAR(100), plan_width INT, plan_height INT, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT UNSIGNED, updated_by BIGINT UNSIGNED, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(floor_id), KEY idx_workplace_floor_building(building_id,is_deleted), CONSTRAINT fk_workplace_floor_building FOREIGN KEY(building_id) REFERENCES workplace_buildings(building_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workplace_zones (
 zone_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, floor_id BIGINT UNSIGNED NOT NULL, name VARCHAR(200) NOT NULL, zone_code VARCHAR(50) NOT NULL, display_colour VARCHAR(20) NOT NULL DEFAULT '#64748b', description VARCHAR(1000), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT UNSIGNED, updated_by BIGINT UNSIGNED, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(zone_id), UNIQUE KEY uq_workplace_zone_floor_code(floor_id,zone_code), KEY idx_workplace_zone_floor(floor_id,is_deleted), CONSTRAINT fk_workplace_zone_floor FOREIGN KEY(floor_id) REFERENCES workplace_floors(floor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workplace_desks (
 desk_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, floor_id BIGINT UNSIGNED NOT NULL, zone_id BIGINT UNSIGNED, desk_code VARCHAR(80) NOT NULL, display_name VARCHAR(200), mode VARCHAR(20) NOT NULL, availability VARCHAR(20) NOT NULL,
 x DECIMAL(7,4) NOT NULL, y DECIMAL(7,4) NOT NULL, width DECIMAL(7,4) NOT NULL, height DECIMAL(7,4) NOT NULL, rotation INT NOT NULL DEFAULT 0, capacity INT NOT NULL DEFAULT 1,
 telephone_extension VARCHAR(30), is_accessible TINYINT(1) NOT NULL DEFAULT 0, equipment_tags VARCHAR(1000), notes VARCHAR(2000), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT UNSIGNED, updated_by BIGINT UNSIGNED, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(desk_id), UNIQUE KEY uq_workplace_desk_floor_code(floor_id,desk_code), KEY idx_workplace_desk_floor(floor_id,is_deleted), KEY idx_workplace_desk_zone(zone_id), CONSTRAINT fk_workplace_desk_floor FOREIGN KEY(floor_id) REFERENCES workplace_floors(floor_id), CONSTRAINT fk_workplace_desk_zone FOREIGN KEY(zone_id) REFERENCES workplace_zones(zone_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workplace_desk_assignments (
 assignment_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, desk_id BIGINT UNSIGNED NOT NULL, staff_id BIGINT UNSIGNED NOT NULL, effective_from DATE NOT NULL, effective_to DATE, is_primary TINYINT(1) NOT NULL DEFAULT 1,
 assignment_reason VARCHAR(1000), assigned_by BIGINT UNSIGNED NOT NULL, released_by BIGINT UNSIGNED, release_reason VARCHAR(1000),
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT UNSIGNED, updated_by BIGINT UNSIGNED, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(assignment_id), KEY idx_workplace_assignment_staff(staff_id,effective_from,effective_to), KEY idx_workplace_assignment_desk(desk_id,effective_from,effective_to),
 CONSTRAINT fk_workplace_assignment_desk FOREIGN KEY(desk_id) REFERENCES workplace_desks(desk_id), CONSTRAINT fk_workplace_assignment_staff FOREIGN KEY(staff_id) REFERENCES staff(staff_id), CONSTRAINT fk_workplace_assignment_assigned_by FOREIGN KEY(assigned_by) REFERENCES users(user_id), CONSTRAINT fk_workplace_assignment_released_by FOREIGN KEY(released_by) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
