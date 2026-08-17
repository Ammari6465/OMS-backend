-- =====================================================================
-- Organogram Management System (OMS) — Canonical Database Schema
-- MySQL 8.x / InnoDB
-- Charset: utf8mb4 (full Unicode incl. emoji), collation utf8mb4_unicode_ci
--
-- This file is the authoritative DDL. It is mounted into the MySQL
-- container on first boot (docker-compose, Phase 4). In local dev the
-- application can also run with hibernate ddl-auto=update; this file
-- remains the single source of truth for the physical model.
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- companies : one row per company in the group
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS companies (
    company_id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name                  VARCHAR(200)    NOT NULL,
    logo_url              VARCHAR(500)    NULL,
    reg_number            VARCHAR(100)    NULL,
    head_office_location  VARCHAR(300)    NULL,
    date_established      DATE            NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    is_deleted            TINYINT(1)      NOT NULL DEFAULT 0,
    deleted_at            DATETIME        NULL,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by            BIGINT UNSIGNED NULL,
    updated_by            BIGINT UNSIGNED NULL,
    version               BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (company_id),
    UNIQUE KEY uq_companies_reg_number (reg_number),
    KEY idx_companies_status (status),
    KEY idx_companies_is_deleted (is_deleted),
    KEY idx_companies_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- departments : nested departments per company (self-referencing)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS departments (
    dept_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id      BIGINT UNSIGNED NOT NULL,
    name            VARCHAR(200)    NOT NULL,
    description     VARCHAR(1000)   NULL,
    parent_dept_id  BIGINT UNSIGNED NULL,
    head_staff_id   BIGINT UNSIGNED NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    is_deleted      TINYINT(1)      NOT NULL DEFAULT 0,
    deleted_at      DATETIME        NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by      BIGINT UNSIGNED NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (dept_id),
    UNIQUE KEY uq_dept_company_name (company_id, name),
    KEY idx_departments_company (company_id),
    KEY idx_departments_parent (parent_dept_id),
    KEY idx_departments_head (head_staff_id),
    KEY idx_departments_is_deleted (is_deleted),
    CONSTRAINT fk_dept_company FOREIGN KEY (company_id)
        REFERENCES companies (company_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_dept_parent FOREIGN KEY (parent_dept_id)
        REFERENCES departments (dept_id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- staff : the people; self-referencing manager_id builds the tree
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS staff (
    staff_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id      BIGINT UNSIGNED NOT NULL,
    dept_id         BIGINT UNSIGNED NULL,
    manager_id      BIGINT UNSIGNED NULL,
    employee_code   VARCHAR(100)    NULL,
    name            VARCHAR(200)    NOT NULL,
    title           VARCHAR(200)    NULL,
    emp_type        VARCHAR(20)     NOT NULL DEFAULT 'PERMANENT',
    email           VARCHAR(200)    NULL,
    landline        VARCHAR(50)     NULL,
    cell_number     VARCHAR(50)     NULL,
    date_joined     DATE            NULL,
    date_left       DATE            NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    photo_url       VARCHAR(500)    NULL,
    is_deleted      TINYINT(1)      NOT NULL DEFAULT 0,
    deleted_at      DATETIME        NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by      BIGINT UNSIGNED NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (staff_id),
    UNIQUE KEY uq_staff_company_empcode (company_id, employee_code),
    KEY idx_staff_company (company_id),
    KEY idx_staff_dept (dept_id),
    KEY idx_staff_manager (manager_id),
    KEY idx_staff_status (status),
    KEY idx_staff_name (name),
    KEY idx_staff_email (email),
    KEY idx_staff_is_deleted (is_deleted),
    CONSTRAINT fk_staff_company FOREIGN KEY (company_id)
        REFERENCES companies (company_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_staff_dept FOREIGN KEY (dept_id)
        REFERENCES departments (dept_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_staff_manager FOREIGN KEY (manager_id)
        REFERENCES staff (staff_id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Deferred FK: department head references staff (circular with above)
ALTER TABLE departments
    ADD CONSTRAINT fk_dept_head FOREIGN KEY (head_staff_id)
        REFERENCES staff (staff_id) ON DELETE SET NULL ON UPDATE CASCADE;

-- ---------------------------------------------------------------------
-- positions : filled & vacant positions in the organogram
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS positions (
    position_id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id              BIGINT UNSIGNED NOT NULL,
    title                   VARCHAR(200)    NOT NULL,
    dept_id                 BIGINT UNSIGNED NULL,
    reports_to_position_id  BIGINT UNSIGNED NULL,
    is_vacant               TINYINT(1)      NOT NULL DEFAULT 1,
    staff_id                BIGINT UNSIGNED NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    is_deleted              TINYINT(1)      NOT NULL DEFAULT 0,
    deleted_at              DATETIME        NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by              BIGINT UNSIGNED NULL,
    updated_by              BIGINT UNSIGNED NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (position_id),
    KEY idx_positions_company (company_id),
    KEY idx_positions_dept (dept_id),
    KEY idx_positions_staff (staff_id),
    KEY idx_positions_vacant (is_vacant),
    KEY idx_positions_reports_to (reports_to_position_id),
    KEY idx_positions_is_deleted (is_deleted),
    CONSTRAINT fk_position_company FOREIGN KEY (company_id)
        REFERENCES companies (company_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_position_dept FOREIGN KEY (dept_id)
        REFERENCES departments (dept_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_position_staff FOREIGN KEY (staff_id)
        REFERENCES staff (staff_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_position_reports_to FOREIGN KEY (reports_to_position_id)
        REFERENCES positions (position_id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- users : system login accounts, linked to a staff record
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    user_id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    staff_id                BIGINT UNSIGNED NULL,
    company_id              BIGINT UNSIGNED NULL,
    username                VARCHAR(100)    NOT NULL,
    full_name               VARCHAR(200)    NULL,
    email                   VARCHAR(200)    NOT NULL,
    password_hash           VARCHAR(255)    NOT NULL,
    role                    VARCHAR(30)     NOT NULL DEFAULT 'READ_ONLY',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    is_active               TINYINT(1)      NOT NULL DEFAULT 1,
    last_login              DATETIME        NULL,
    failed_login_attempts   INT             NOT NULL DEFAULT 0,
    locked_until            DATETIME        NULL,
    password_reset_token    VARCHAR(100)    NULL,
    password_reset_expires  DATETIME        NULL,
    is_deleted              TINYINT(1)      NOT NULL DEFAULT 0,
    deleted_at              DATETIME        NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by              BIGINT UNSIGNED NULL,
    updated_by              BIGINT UNSIGNED NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_users_username (username),
    UNIQUE KEY uq_users_email (email),
    KEY idx_users_role (role),
    KEY idx_users_staff (staff_id),
    KEY idx_users_company (company_id),
    KEY idx_users_reset_token (password_reset_token),
    KEY idx_users_is_deleted (is_deleted),
    CONSTRAINT fk_users_staff FOREIGN KEY (staff_id)
        REFERENCES staff (staff_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_users_company FOREIGN KEY (company_id)
        REFERENCES companies (company_id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- audit_log : immutable change history; never deleted
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    log_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entity_type     VARCHAR(50)     NOT NULL,
    entity_id       BIGINT UNSIGNED NULL,
    staff_id        BIGINT UNSIGNED NULL,
    company_id      BIGINT UNSIGNED NULL,
    changed_by      BIGINT UNSIGNED NULL,
    changed_by_name VARCHAR(200)    NULL,
    change_type     VARCHAR(30)     NOT NULL,
    field_name      VARCHAR(100)    NULL,
    old_value       TEXT            NULL,
    new_value       TEXT            NULL,
    changed_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (log_id),
    KEY idx_audit_entity (entity_type, entity_id),
    KEY idx_audit_staff (staff_id),
    KEY idx_audit_company (company_id),
    KEY idx_audit_changed_by (changed_by),
    KEY idx_audit_changed_at (changed_at),
    KEY idx_audit_change_type (change_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- notifications : in-app notification store
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notifications (
    notif_id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_user_id   BIGINT UNSIGNED NOT NULL,
    type                VARCHAR(40)     NOT NULL,
    title               VARCHAR(200)    NULL,
    message             VARCHAR(1000)   NOT NULL,
    link                VARCHAR(300)    NULL,
    entity_type         VARCHAR(50)     NULL,
    entity_id           BIGINT UNSIGNED NULL,
    is_read             TINYINT(1)      NOT NULL DEFAULT 0,
    read_at             DATETIME        NULL,
    email_sent          TINYINT(1)      NOT NULL DEFAULT 0,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notif_id),
    KEY idx_notif_recipient (recipient_user_id),
    KEY idx_notif_is_read (is_read),
    KEY idx_notif_created_at (created_at),
    CONSTRAINT fk_notif_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES users (user_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Workplace seating hierarchy and assignment history (idempotent upgrade-safe DDL)
CREATE TABLE IF NOT EXISTS workplace_offices (
 office_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, company_id BIGINT UNSIGNED NOT NULL, name VARCHAR(200) NOT NULL, office_code VARCHAR(50) NOT NULL,
 address_text VARCHAR(500), city VARCHAR(100), country VARCHAR(100), time_zone VARCHAR(60) NOT NULL DEFAULT 'UTC', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
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
 telephone_extension VARCHAR(30), accessible TINYINT(1) NOT NULL DEFAULT 0, equipment_tags VARCHAR(1000), notes VARCHAR(2000), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
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

SET FOREIGN_KEY_CHECKS = 1;
