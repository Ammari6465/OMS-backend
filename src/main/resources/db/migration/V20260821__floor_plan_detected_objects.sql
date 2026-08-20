-- Regions recognised on a floor plan, stored separately from the uploaded image
-- so detection runs and manual edits never modify the original file.
--
-- Geometry is normalised to 0..1 on both axes rather than pixels, so overlays
-- stay aligned at any zoom and survive the plan being re-uploaded at a
-- different resolution.

CREATE TABLE IF NOT EXISTS workplace_detected_objects (
    detected_object_id BIGINT          NOT NULL AUTO_INCREMENT,
    floor_id           BIGINT          NOT NULL,
    object_type        VARCHAR(40)     NOT NULL,
    name               VARCHAR(200)    NULL,
    object_code        VARCHAR(50)     NULL,
    polygon            VARCHAR(4000)   NOT NULL,
    bbox_x             DOUBLE          NOT NULL DEFAULT 0,
    bbox_y             DOUBLE          NOT NULL DEFAULT 0,
    bbox_width         DOUBLE          NOT NULL DEFAULT 0,
    bbox_height        DOUBLE          NOT NULL DEFAULT 0,
    center_x           DOUBLE          NOT NULL DEFAULT 0,
    center_y           DOUBLE          NOT NULL DEFAULT 0,
    rotation           INT             NOT NULL DEFAULT 0,
    area               DOUBLE          NOT NULL DEFAULT 0,
    confidence         DOUBLE          NOT NULL DEFAULT 1,
    ocr_text           VARCHAR(1000)   NULL,
    source             VARCHAR(20)     NOT NULL DEFAULT 'AUTO',
    detector           VARCHAR(60)     NULL,
    desk_id            BIGINT          NULL,
    is_deleted         TINYINT(1)      NOT NULL DEFAULT 0,
    deleted_at         DATETIME        NULL,
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by         BIGINT          NULL,
    updated_by         BIGINT          NULL,
    version            BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (detected_object_id),
    KEY idx_detected_floor (floor_id, is_deleted),
    KEY idx_detected_type (floor_id, object_type),
    CONSTRAINT fk_detected_floor FOREIGN KEY (floor_id) REFERENCES workplace_floors (floor_id),
    -- A promoted desk may be archived independently, so this link is advisory.
    CONSTRAINT fk_detected_desk FOREIGN KEY (desk_id) REFERENCES workplace_desks (desk_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
