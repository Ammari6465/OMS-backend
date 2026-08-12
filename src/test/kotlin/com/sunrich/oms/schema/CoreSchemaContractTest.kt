package com.sunrich.oms.schema

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("test")
class CoreSchemaContractTest {
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `physical schema follows the SRS table and column contract`() {
        dataSource.connection.use { connection ->
            val metadata = connection.metaData
            val columnsByTable = mutableMapOf<String, MutableSet<String>>()
            metadata.getColumns(connection.catalog, null, "%", "%").use { rows ->
                while (rows.next()) {
                    val table = rows.getString("TABLE_NAME").lowercase()
                    val column = rows.getString("COLUMN_NAME").lowercase()
                    columnsByTable.getOrPut(table) { mutableSetOf() } += column
                }
            }

            assertColumns(columnsByTable, "companies", "company_id", "name", "logo_url", "reg_number", "created_at")
            assertColumns(columnsByTable, "departments", "dept_id", "company_id", "name", "parent_dept_id", "head_staff_id")
            assertColumns(
                columnsByTable,
                "staff",
                "staff_id", "company_id", "dept_id", "manager_id", "name", "title", "emp_type",
                "email", "landline", "cell_number", "date_joined", "date_left", "status", "photo_url"
            )
            assertColumns(columnsByTable, "positions", "position_id", "company_id", "title", "dept_id", "is_vacant", "staff_id")
            assertColumns(columnsByTable, "users", "user_id", "staff_id", "email", "password_hash", "role", "is_active", "last_login")
            assertColumns(
                columnsByTable,
                "audit_log",
                "log_id", "staff_id", "changed_by", "change_type", "field_name", "old_value", "new_value", "changed_at"
            )
            assertColumns(columnsByTable, "notifications", "notif_id", "recipient_user_id", "type", "message", "is_read", "created_at")

            assertThat(columnsByTable).doesNotContainKey("audit_logs")
            assertThat(columnsByTable).doesNotContainKey("app_records")
            assertThat(columnsByTable.getValue("companies")).doesNotContain("registration_number")
            assertThat(columnsByTable.getValue("departments")).doesNotContain("department_id", "parent_department_id")
            assertThat(columnsByTable.getValue("notifications")).doesNotContain("notification_id", "title", "icon", "color")
            assertThat(columnsByTable.getValue("audit_log")).doesNotContain("is_deleted", "updated_at", "version")

            assertForeignKey(metadata, connection.catalog, "departments", "company_id", "companies", "company_id")
            assertForeignKey(metadata, connection.catalog, "departments", "parent_dept_id", "departments", "dept_id")
            assertForeignKey(metadata, connection.catalog, "departments", "head_staff_id", "staff", "staff_id")
            assertForeignKey(metadata, connection.catalog, "staff", "company_id", "companies", "company_id")
            assertForeignKey(metadata, connection.catalog, "staff", "dept_id", "departments", "dept_id")
            assertForeignKey(metadata, connection.catalog, "staff", "manager_id", "staff", "staff_id")
            assertForeignKey(metadata, connection.catalog, "positions", "company_id", "companies", "company_id")
            assertForeignKey(metadata, connection.catalog, "positions", "dept_id", "departments", "dept_id")
            assertForeignKey(metadata, connection.catalog, "positions", "staff_id", "staff", "staff_id")
            assertForeignKey(metadata, connection.catalog, "users", "staff_id", "staff", "staff_id")
            assertForeignKey(metadata, connection.catalog, "audit_log", "staff_id", "staff", "staff_id")
            assertForeignKey(metadata, connection.catalog, "audit_log", "changed_by", "users", "user_id")
            assertForeignKey(metadata, connection.catalog, "notifications", "recipient_user_id", "users", "user_id")
        }
    }

    private fun assertColumns(schema: Map<String, Set<String>>, table: String, vararg expected: String) {
        assertThat(schema).containsKey(table)
        assertThat(schema.getValue(table)).contains(*expected)
    }

    private fun assertForeignKey(
        metadata: java.sql.DatabaseMetaData,
        catalog: String?,
        table: String,
        column: String,
        referencedTable: String,
        referencedColumn: String
    ) {
        val importedKeys = mutableSetOf<List<String>>()
        metadata.getImportedKeys(catalog, null, table.uppercase()).use { rows ->
            while (rows.next()) {
                importedKeys += listOf(
                    rows.getString("FKCOLUMN_NAME").lowercase(),
                    rows.getString("PKTABLE_NAME").lowercase(),
                    rows.getString("PKCOLUMN_NAME").lowercase()
                )
            }
        }
        assertThat(importedKeys).contains(listOf(column, referencedTable, referencedColumn))
    }
}
