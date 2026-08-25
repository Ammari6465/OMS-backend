-- Phase 4: meeting-room booking.
--
-- Bookable spaces (conference/meeting rooms) can be reserved for a time window.
-- Mirrors desk bookings but for a WorkplaceSpace, with an organizer, a
-- participant count validated against the room's capacity, and an optional
-- equipment requirement. Concurrency uses the same pessimistic-lock approach:
-- the service locks the space row before the overlap check and insert.

CREATE TABLE IF NOT EXISTS workplace_room_bookings (
 room_booking_id BIGINT NOT NULL AUTO_INCREMENT,
 space_id BIGINT NOT NULL,
 organizer_staff_id BIGINT NOT NULL,
 booking_date DATE NOT NULL,
 start_time TIME NOT NULL,
 end_time TIME NOT NULL,
 time_zone VARCHAR(60) NOT NULL,
 participants INT NOT NULL DEFAULT 1,
 equipment_required VARCHAR(1000),
 title VARCHAR(200),
 status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
 check_in_time DATETIME NULL,
 check_out_time DATETIME NULL,
 cancellation_reason VARCHAR(1000),
 is_deleted TINYINT(1) NOT NULL DEFAULT 0, deleted_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(room_booking_id),
 KEY idx_room_booking_space_date(space_id,booking_date,status),
 KEY idx_room_booking_organizer(organizer_staff_id,booking_date),
 CONSTRAINT fk_room_booking_space FOREIGN KEY(space_id) REFERENCES workplace_spaces(space_id),
 CONSTRAINT fk_room_booking_organizer FOREIGN KEY(organizer_staff_id) REFERENCES staff(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
