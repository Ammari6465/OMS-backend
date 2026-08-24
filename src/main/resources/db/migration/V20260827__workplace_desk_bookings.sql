-- Phase 3: desk reservation and check-in.
--
-- The desk modes RESERVABLE / DROP_IN and the availability states RESERVED /
-- CHECKED_IN existed as enums with no workflow behind them. This table gives
-- them a real booking lifecycle: book -> check in -> check out, with cancel and
-- automatic no-show release. Two simultaneous requests cannot reserve the same
-- desk for an overlapping slot: the booking service takes a pessimistic write
-- lock on the desk row before it checks for a clash and inserts.

CREATE TABLE IF NOT EXISTS workplace_desk_bookings (
 booking_id BIGINT NOT NULL AUTO_INCREMENT,
 desk_id BIGINT NOT NULL,
 staff_id BIGINT NOT NULL,
 booking_date DATE NOT NULL,
 start_time TIME NOT NULL,
 end_time TIME NOT NULL,
 time_zone VARCHAR(60) NOT NULL,
 booking_type VARCHAR(20) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
 check_in_time DATETIME NULL,
 check_out_time DATETIME NULL,
 cancellation_reason VARCHAR(1000),
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(booking_id),
 KEY idx_desk_booking_desk_date(desk_id,booking_date,status),
 KEY idx_desk_booking_staff_date(staff_id,booking_date),
 CONSTRAINT fk_desk_booking_desk FOREIGN KEY(desk_id) REFERENCES workplace_desks(desk_id),
 CONSTRAINT fk_desk_booking_staff FOREIGN KEY(staff_id) REFERENCES staff(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
