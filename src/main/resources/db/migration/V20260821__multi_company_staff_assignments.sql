-- A staff record is a person; this table stores each company-specific
-- employment context. Existing staff.company_id remains the primary context
-- for backward compatibility and is backfilled here as is_primary = true.

SET @schema := DATABASE();
SET @has_staff := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'staff'
);

SET @ddl := IF(@has_staff = 1,
    'CREATE TABLE IF NOT EXISTS staff_company_assignments (
        assignment_id BIGINT NOT NULL AUTO_INCREMENT,
        staff_id BIGINT NOT NULL,
        company_id BIGINT NOT NULL,
        dept_id BIGINT NULL,
        manager_id BIGINT NULL,
        title VARCHAR(200) NULL,
        is_primary TINYINT(1) NOT NULL DEFAULT 0,
        effective_from DATE NULL,
        effective_to DATE NULL,
        status VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'',
        is_deleted TINYINT(1) NOT NULL DEFAULT 0,
        deleted_at DATETIME NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        created_by BIGINT NULL,
        updated_by BIGINT NULL,
        version BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (assignment_id),
        UNIQUE KEY uq_staff_company_assignment (staff_id, company_id),
        KEY idx_staff_assignment_company (company_id, status, is_deleted),
        KEY idx_staff_assignment_staff (staff_id, is_primary, is_deleted),
        CONSTRAINT fk_staff_assignment_staff FOREIGN KEY (staff_id) REFERENCES staff(staff_id),
        CONSTRAINT fk_staff_assignment_company FOREIGN KEY (company_id) REFERENCES companies(company_id),
        CONSTRAINT fk_staff_assignment_department FOREIGN KEY (dept_id) REFERENCES departments(dept_id) ON DELETE SET NULL,
        CONSTRAINT fk_staff_assignment_manager FOREIGN KEY (manager_id) REFERENCES staff(staff_id) ON DELETE SET NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_assignments := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'staff_company_assignments'
);
SET @dml := IF(@has_assignments = 1,
    'INSERT INTO staff_company_assignments
        (staff_id, company_id, dept_id, manager_id, title, is_primary, effective_from, effective_to,
         status, is_deleted, deleted_at, created_at, updated_at, created_by, updated_by, version)
     SELECT s.staff_id, s.company_id, s.dept_id, s.manager_id, s.title, 1, s.date_joined, s.date_left,
            s.status, s.is_deleted, s.deleted_at, s.created_at, s.updated_at, s.created_by, s.updated_by, 0
       FROM staff s
      WHERE NOT EXISTS (
          SELECT 1 FROM staff_company_assignments a
           WHERE a.staff_id = s.staff_id AND a.company_id = s.company_id
      )',
    'SELECT 1');
PREPARE stmt FROM @dml; EXECUTE stmt; DEALLOCATE PREPARE stmt;

