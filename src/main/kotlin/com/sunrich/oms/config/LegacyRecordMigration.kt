package com.sunrich.oms.config

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.organization.Department
import com.sunrich.oms.organization.DepartmentRepository
import com.sunrich.oms.organization.Position
import com.sunrich.oms.organization.PositionRepository
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.organization.StaffRepository
import com.sunrich.oms.systemdata.AuditLog
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.systemdata.Notification
import com.sunrich.oms.systemdata.NotificationRepository
import com.sunrich.oms.systemdata.SystemSetting
import com.sunrich.oms.systemdata.SystemSettingRepository
import com.sunrich.oms.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

private data class LegacyRecord(
    val id: Long,
    val collectionName: String,
    val payload: String,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime?
)

@Configuration
class LegacyRecordMigrationConfiguration {
    @Bean
    @Order(10)
    fun migrateLegacyAppRecords(service: LegacyRecordMigrationService) = ApplicationRunner { service.migrate() }
}

@Service
class LegacyRecordMigrationService(
    private val jdbcTemplate: JdbcTemplate,
    private val companies: CompanyRepository,
    private val departments: DepartmentRepository,
    private val staff: StaffRepository,
    private val positions: PositionRepository,
    private val audits: AuditLogRepository,
    private val notifications: NotificationRepository,
    private val settings: SystemSettingRepository,
    private val users: UserRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    @Transactional
    fun migrate() {
        val records = readLegacyRecords()
        if (records.isEmpty()) return

        migrateOrganization(records)
        migrateSystemData(records)
        log.info("Legacy app_records migration checked {} records", records.size)
    }

    private fun migrateOrganization(records: List<LegacyRecord>) {
        if (companies.count() + departments.count() + staff.count() + positions.count() > 0) return

        val companyMap = mutableMapOf<Long, Company>()
        val departmentMap = mutableMapOf<Long, Department>()
        val staffMap = mutableMapOf<Long, Staff>()

        records.of("companies").forEach { record ->
            val p = payload(record)
            val entity = Company(
                name = p.text("name") ?: "Legacy company ${record.id}",
                regNumber = p.text("regNumber"),
                headOffice = p.text("headOffice"),
                dateEstablished = p.date("dateEstablished"),
                logoUrl = p.text("logoUrl"),
                status = p.enum("status", EntityStatus.ACTIVE)
            ).withDeleted(record)
            companyMap[record.id] = companies.save(entity)
        }

        records.of("departments").forEach { record ->
            val p = payload(record)
            val company = companyMap[p.long("companyId")] ?: return@forEach
            val entity = Department(
                company = company,
                name = p.text("name") ?: "Legacy department ${record.id}",
                description = p.text("description"),
                status = p.enum("status", EntityStatus.ACTIVE)
            ).withDeleted(record)
            departmentMap[record.id] = departments.save(entity)
        }

        records.of("staff").forEach { record ->
            val p = payload(record)
            val company = companyMap[p.long("companyId")] ?: return@forEach
            val entity = Staff(
                company = company,
                department = departmentMap[p.long("deptId")],
                employeeCode = p.text("employeeCode"),
                name = p.text("name") ?: "Legacy staff ${record.id}",
                title = p.text("title"),
                empType = p.enum("empType", EmploymentType.PERMANENT),
                email = p.text("email"),
                landline = p.text("landline"),
                cellNumber = p.text("cellNumber"),
                dateJoined = p.date("dateJoined"),
                dateLeft = p.date("dateLeft"),
                status = p.enum("status", EntityStatus.ACTIVE),
                photoUrl = p.text("photoUrl")
            ).withDeleted(record)
            staffMap[record.id] = staff.save(entity)
        }

        records.of("departments").forEach { record ->
            val p = payload(record)
            val entity = departmentMap[record.id] ?: return@forEach
            entity.parentDepartment = departmentMap[p.long("parentDeptId")]
            entity.headStaff = staffMap[p.long("headStaffId")]
            departments.save(entity)
        }

        records.of("staff").forEach { record ->
            val entity = staffMap[record.id] ?: return@forEach
            entity.manager = staffMap[payload(record).long("managerId")]
            staff.save(entity)
        }

        records.of("positions").forEach { record ->
            val p = payload(record)
            val company = companyMap[p.long("companyId")] ?: return@forEach
            positions.save(Position(
                company = company,
                title = p.text("title") ?: "Legacy position ${record.id}",
                department = departmentMap[p.long("deptId")],
                isVacant = p.bool("isVacant") ?: true,
                staff = staffMap[p.long("staffId")],
                status = p.enum("status", PositionStatus.OPEN)
            ).withDeleted(record))
        }
    }

    private fun migrateSystemData(records: List<LegacyRecord>) {
        val migrationUser = users.findAll().firstOrNull() ?: return
        if (audits.count() == 0L) records.of("audit").forEach { record ->
            val p = payload(record)
            val action = runCatching { AuditAction.valueOf(p.text("action") ?: "CREATE") }.getOrDefault(AuditAction.CREATE)
            audits.save(AuditLog(
                changedBy = migrationUser,
                changeType = action,
                fieldName = p.text("entityType") ?: "Legacy",
                entityType = p.text("entityType") ?: "Legacy",
                entityId = p.long("entityId"),
                oldValue = null,
                newValue = p.text("summary") ?: "Migrated legacy audit entry",
                changedAt = p.dateTime("changedAt") ?: record.createdAt ?: LocalDateTime.now()
            ))
        }

        if (notifications.count() == 0L) records.of("notifications").filterNot { it.isDeleted }.forEach { record ->
            val p = payload(record)
            notifications.save(Notification(
                recipient = migrationUser,
                type = p.enum("type", NotificationType.SYSTEM),
                message = p.text("message") ?: "",
                isRead = p.bool("isRead") ?: false
            ))
        }

        if (settings.count() == 0L) records.of("settings").forEach { record ->
            val p = payload(record)
            val kind = p.text("kind") ?: return@forEach
            if (kind != "notification-preferences") return@forEach
            @Suppress("UNCHECKED_CAST")
            val values = p["values"] as? Map<String, Boolean> ?: emptyMap()
            val entity = SystemSetting(kind)
            entity.onboarding = values["onboarding"]
            entity.exits = values["exits"]
            entity.transfers = values["transfers"]
            entity.vacancies = values["vacancies"]
            settings.save(entity.withDeleted(record))
        }
    }

    private fun readLegacyRecords(): List<LegacyRecord> = try {
        jdbcTemplate.query(
            "SELECT record_id, collection_name, payload, is_deleted, created_at FROM app_records ORDER BY record_id"
        ) { result, _ ->
            LegacyRecord(
                id = result.getLong("record_id"),
                collectionName = result.getString("collection_name"),
                payload = result.getString("payload"),
                isDeleted = result.getBoolean("is_deleted"),
                createdAt = result.getTimestamp("created_at")?.toLocalDateTime()
            )
        }
    } catch (_: DataAccessException) {
        emptyList()
    }

    private fun payload(record: LegacyRecord): Map<String, Any?> = objectMapper.readValue(record.payload, mapType)
    private fun List<LegacyRecord>.of(name: String) = filter { it.collectionName == name }
    private fun Map<String, Any?>.text(key: String) = this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    private fun Map<String, Any?>.long(key: String) = (this[key] as? Number)?.toLong() ?: text(key)?.toLongOrNull()
    private fun Map<String, Any?>.bool(key: String) = this[key] as? Boolean ?: text(key)?.toBooleanStrictOrNull()
    private fun Map<String, Any?>.date(key: String) = text(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun Map<String, Any?>.dateTime(key: String) = text(key)?.let { runCatching { LocalDateTime.parse(it.removeSuffix("Z")) }.getOrNull() }
    private inline fun <reified T : Enum<T>> Map<String, Any?>.enum(key: String, fallback: T): T =
        text(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private fun <T : com.sunrich.oms.common.entity.BaseEntity> T.withDeleted(record: LegacyRecord): T {
        if (record.isDeleted) markDeleted()
        return this
    }
}
