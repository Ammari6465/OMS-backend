-- Permanent staff assignments for cabins. This is intentionally separate from
-- desk assignment and room booking data: a cabin remains non-bookable while it
-- is allocated to one employee for an effective date range.
CREATE TABLE IF NOT EXISTS workplace_space_assignments (
 space_assignment_id BIGINT NOT NULL AUTO_INCREMENT,
 space_id BIGINT NOT NULL,
 staff_id BIGINT NOT NULL,
 effective_from DATE NOT NULL,
 effective_to DATE,
 is_primary TINYINT(1) NOT NULL DEFAULT 1,
 assignment_reason VARCHAR(1000),
 assigned_by BIGINT NOT NULL,
 released_by BIGINT,
 release_reason VARCHAR(1000),
 is_deleted TINYINT(1) NOT NULL DEFAULT 0,
 deleted_at DATETIME,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 created_by BIGINT,
 updated_by BIGINT,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(space_assignment_id),
 KEY idx_workplace_space_assignment_staff(staff_id,effective_from,effective_to),
 KEY idx_workplace_space_assignment_space(space_id,effective_from,effective_to),
 CONSTRAINT fk_space_assignment_space FOREIGN KEY(space_id) REFERENCES workplace_spaces(space_id),
 CONSTRAINT fk_space_assignment_staff FOREIGN KEY(staff_id) REFERENCES staff(staff_id),
 CONSTRAINT fk_space_assignment_assigned_by FOREIGN KEY(assigned_by) REFERENCES users(user_id),
 CONSTRAINT fk_space_assignment_released_by FOREIGN KEY(released_by) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
