-- Group hierarchy for companies: one holding company ("Sunrich Companies")
-- with every sister concern pointing at it through parent_company_id.
--
-- Idempotent and production-safe: the column, index and foreign key are only
-- added when missing, and no row is modified here. Linking existing companies
-- to the holding company is done by CompanyGroupSeeder at startup so the same
-- rules (single root, no cycles) apply to fresh and upgraded databases alike.

SET @schema := DATABASE();

-- A brand-new database has no companies table yet (Hibernate creates it after
-- Flyway runs); skip cleanly in that case.
SET @has_table := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'companies'
);

-- parent_company_id. Signed BIGINT to match the companies.company_id that
-- Hibernate's schema update actually creates (as V20260817 already assumes);
-- a foreign key requires both columns to have the same signedness.
-- ---------------------------------------------------------------------------
SET @has_column := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'companies' AND COLUMN_NAME = 'parent_company_id'
);
SET @ddl := IF(@has_table = 1 AND @has_column = 0,
    'ALTER TABLE companies ADD COLUMN parent_company_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- idx_companies_parent ------------------------------------------------------
SET @has_index := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'companies' AND INDEX_NAME = 'idx_companies_parent'
);
SET @ddl := IF(@has_table = 1 AND @has_index = 0,
    'ALTER TABLE companies ADD KEY idx_companies_parent (parent_company_id, is_deleted)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- fk_companies_parent -------------------------------------------------------
SET @has_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'companies'
      AND CONSTRAINT_NAME = 'fk_companies_parent' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @ddl := IF(@has_table = 1 AND @has_fk = 0,
    'ALTER TABLE companies ADD CONSTRAINT fk_companies_parent FOREIGN KEY (parent_company_id) REFERENCES companies(company_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
