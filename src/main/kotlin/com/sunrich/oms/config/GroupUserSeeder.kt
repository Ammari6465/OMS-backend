package com.sunrich.oms.config

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

/**
 * Seeds the production-like access model for the Sunrich group.
 *
 * There is exactly one group-wide SUPER_ADMIN (the Chairman). Every operating
 * sister concern gets one COMPANY_ADMIN, MANAGER, STAFF and READ_ONLY account.
 * Known demo logins and any additional SUPER_ADMIN accounts are soft-deleted,
 * disabled and assigned an unknown password so audit history stays intact.
 */
@Configuration
class GroupUserSeeder(
    @Value("\${oms.bootstrap.group-users.chairman-password}") private val chairmanPassword: String,
    @Value("\${oms.bootstrap.group-users.company-admin-password}") private val companyAdminPassword: String,
    @Value("\${oms.bootstrap.group-users.manager-password}") private val managerPassword: String,
    @Value("\${oms.bootstrap.group-users.staff-password}") private val staffPassword: String,
    @Value("\${oms.bootstrap.group-users.read-only-password}") private val readOnlyPassword: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Order(3)
    fun seedGroupUsers(
        companies: CompanyRepository,
        users: UserRepository,
        passwordEncoder: PasswordEncoder
    ): ApplicationRunner = ApplicationRunner {
        companies.findFirstByNameIgnoreCaseAndIsDeletedFalse(CompanyGroupSeeder.GROUP_PARENT_NAME)
            ?: error("${CompanyGroupSeeder.GROUP_PARENT_NAME} must be seeded before group users")

        val chairman = upsert(
            users, passwordEncoder, Account(
                username = CHAIRMAN_USERNAME,
                fullName = "Arjun Wijesinghe",
                email = "chairman@sunrichgroup.com",
                role = Role.SUPER_ADMIN,
                companyId = null,
                initialPassword = chairmanPassword
            )
        )

        users.findAll()
            .filter { it.id != chairman.id && (it.role == Role.SUPER_ADMIN || it.username.lowercase() in DUMMY_USERNAMES) }
            .forEach { archive(it, users, passwordEncoder) }

        COMPANY_ACCOUNTS.forEach { profile ->
            val company = companies.findFirstByNameIgnoreCaseAndIsDeletedFalse(profile.companyName)
                ?: error("Missing sister concern: ${profile.companyName}")
            listOf(
                Account("${profile.code}.admin", profile.companyAdmin, "${profile.code}.admin@sunrichgroup.com",
                    Role.COMPANY_ADMIN, company.id, companyAdminPassword),
                Account("${profile.code}.manager", profile.manager, "${profile.code}.manager@sunrichgroup.com",
                    Role.MANAGER, company.id, managerPassword),
                Account("${profile.code}.staff", profile.staff, "${profile.code}.staff@sunrichgroup.com",
                    Role.STAFF, company.id, staffPassword),
                Account("${profile.code}.auditor", profile.auditor, "${profile.code}.auditor@sunrichgroup.com",
                    Role.READ_ONLY, company.id, readOnlyPassword)
            ).forEach { upsert(users, passwordEncoder, it) }
        }

        log.info(
            "Group access seeded: one Chairman and {} company-scoped users across {} sister concerns",
            COMPANY_ACCOUNTS.size * COMPANY_ROLES.size,
            COMPANY_ACCOUNTS.size
        )
    }

    private fun upsert(users: UserRepository, encoder: PasswordEncoder, account: Account): User {
        val existing = users.findByUsernameIgnoreCase(account.username).orElse(null)
        if (existing == null) {
            return users.save(User(
                username = account.username,
                email = account.email,
                passwordHash = encoder.encode(account.initialPassword),
                role = account.role,
                fullName = account.fullName,
                companyId = account.companyId,
                status = EntityStatus.ACTIVE,
                isActive = true
            ))
        }

        var changed = false
        if (existing.isDeleted) { existing.restore(); changed = true }
        if (!existing.isActive) { existing.isActive = true; changed = true }
        if (existing.status != EntityStatus.ACTIVE) { existing.status = EntityStatus.ACTIVE; changed = true }
        if (existing.fullName != account.fullName) { existing.fullName = account.fullName; changed = true }
        if (!existing.email.equals(account.email, true)) { existing.email = account.email; changed = true }
        if (existing.role != account.role) { existing.role = account.role; changed = true }
        if (existing.companyId != account.companyId) { existing.companyId = account.companyId; changed = true }
        return if (changed) users.save(existing) else existing
    }

    private fun archive(user: User, users: UserRepository, encoder: PasswordEncoder) {
        if (user.isDeleted && !user.isActive) return
        if (!user.isDeleted) user.markDeleted()
        user.isActive = false
        user.status = EntityStatus.INACTIVE
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        user.passwordResetToken = null
        user.passwordResetExpires = null
        user.passwordHash = encoder.encode(UUID.randomUUID().toString())
        users.save(user)
        log.info("Archived obsolete seeded account: {}", user.username)
    }

    private data class Account(
        val username: String,
        val fullName: String,
        val email: String,
        val role: Role,
        val companyId: Long?,
        val initialPassword: String
    )

    data class CompanyAccessProfile(
        val companyName: String,
        val code: String,
        val companyAdmin: String,
        val manager: String,
        val staff: String,
        val auditor: String
    )

    companion object {
        const val CHAIRMAN_USERNAME = "chairman"
        val COMPANY_ROLES = setOf(Role.COMPANY_ADMIN, Role.MANAGER, Role.STAFF, Role.READ_ONLY)
        val DUMMY_USERNAMES = setOf(
            "admin_sunrich", "hr_manager", "dept_manager", "staff_john", "superadmin",
            "admin", "manager", "viewer", "viewer_guest"
        )

        val COMPANY_ACCOUNTS = listOf(
            CompanyAccessProfile("Atlantic Global Shipping", "atlantic", "Nadeesha Perera", "Ruwan Jayasinghe", "Kavindi Silva", "Imran Hameed"),
            CompanyAccessProfile("Admiral Shipping", "admiral", "Dilan Fernando", "Shalini de Alwis", "Kasun Bandara", "Farah Nizam"),
            CompanyAccessProfile("Sunrich Shipchandlers", "shipchandlers", "Roshan Mendis", "Tharushi Senanayake", "Nimal Rodrigo", "Ayesha Kareem"),
            CompanyAccessProfile("Sunrich Logistics", "logistics", "Malith Gunawardena", "Dinithi Peiris", "Chamod Wickramasinghe", "Rashmi Iqbal"),
            CompanyAccessProfile("Skyway Marketing", "skyway_marketing", "Ishara Weerakoon", "Sanjana Dias", "Akila Samarasinghe", "Mariam Azhar"),
            CompanyAccessProfile("Sunrich Ship Management", "ship_management", "Charith Abeysekara", "Piumi Karunaratne", "Shehan Cooray", "Nafla Rahman"),
            CompanyAccessProfile("Sunrich Impex", "impex", "Sachini Herath", "Janith Ekanayake", "Oshini Maduranga", "Zayan Saleem"),
            CompanyAccessProfile("Sunrich Ship Building", "ship_building", "Pradeep Ranatunga", "Nilushi Amarasinghe", "Gayan Kulatunga", "Husna Latiff"),
            CompanyAccessProfile("Sunrich Chartering", "chartering", "Udara Wijeratne", "Madhavi Liyanage", "Thilina Pathirana", "Sameera Faiz"),
            CompanyAccessProfile("Sunrich Properties and Investment", "properties", "Anjali Jayawardena", "Lakshan de Silva", "Dinuka Seneviratne", "Rihana Cassim"),
            CompanyAccessProfile("Sunrich Rice", "rice", "Suresh Balasuriya", "Gayani Dissanayake", "Isuru Rathnayake", "Fathima Rizwan"),
            CompanyAccessProfile("Sunrich Travels", "travels", "Menaka Kularatne", "Asela Nanayakkara", "Chathuri Welgama", "Aadil Mohideen"),
            CompanyAccessProfile("Sunrich Safaris", "safaris", "Harini Athukorala", "Vishwa Gamage", "Pasindu Lakmal", "Nusra Jaleel"),
            CompanyAccessProfile("Sunrich 360°", "sunrich360", "Ravindu Hettiarachchi", "Tania Peris", "Supun Alahakoon", "Shazna Ameen"),
            CompanyAccessProfile("Skyway Global Trading", "skyway_trading", "Dilshan Edirisinghe", "Nethmi Rajapaksa", "Bhanuka Perera", "Amina Farook"),
            CompanyAccessProfile("Sunrich Villas", "villas", "Sanduni Wijesundara", "Tharindu Kodikara", "Amaya Jayalath", "Rizwan Majeed"),
            CompanyAccessProfile("Sunrich Meraki", "meraki", "Yasara Abeyratne", "Kanishka Senarath", "Dulani Fonseka", "Hana Shafi"),
            CompanyAccessProfile("Sunrich Tiles", "tiles", "Manoj Weerasekara", "Erandi Goonetilleke", "Lahiru Jayasooriya", "Safiya Haniff"),
            CompanyAccessProfile("Sunrich Technology", "technology", "Navin Chandrasekara", "Iresha Wijemanne", "Kevin Dias", "Mishal Ismail"),
            CompanyAccessProfile("Sunrich Foundation", "foundation", "Deepika Samarasekara", "Ashan Perera", "Nirosha Fernando", "Sara Nazeer")
        )
    }
}
