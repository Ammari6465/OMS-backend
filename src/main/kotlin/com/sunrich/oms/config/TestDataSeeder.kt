package com.sunrich.oms.config

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.organization.Department
import com.sunrich.oms.organization.DepartmentRepository
import com.sunrich.oms.organization.Position
import com.sunrich.oms.organization.PositionRepository
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.organization.StaffRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import java.time.LocalDate

/**
 * Seeds comprehensive demo and testing data across all application modules:
 * Companies, Departments, Staff Members, Positions, and Vacancies.
 */
@Configuration
class TestDataSeeder {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Order(2)
    fun seedComprehensiveTestData(
        companyRepository: CompanyRepository,
        departmentRepository: DepartmentRepository,
        staffRepository: StaffRepository,
        positionRepository: PositionRepository
    ): ApplicationRunner = ApplicationRunner {
        log.info("Checking system database state for testing data initialization...")

        // 1. Companies come from CompanyGroupSeeder (the real group structure).
        // Demo departments, staff and positions hang off the holding company.
        val companyA = companyRepository.findFirstByNameIgnoreCaseAndIsDeletedFalse(CompanyGroupSeeder.GROUP_PARENT_NAME)
            ?: companyRepository.save(
                Company(
                    name = CompanyGroupSeeder.GROUP_PARENT_NAME,
                    regNumber = "SRG-000",
                    headOffice = "Colombo, Sri Lanka",
                    dateEstablished = LocalDate.of(2010, 1, 15),
                    status = EntityStatus.ACTIVE
                )
            ).also { log.info("Seeded group holding company: {}", it.name) }

        // 2. Seed Departments
        val allDepts = departmentRepository.findAll().filter { it.company.id == companyA.id && !it.isDeleted }
        var deptExec = allDepts.firstOrNull { it.name == "Executive Management" }
        if (deptExec == null) {
            deptExec = departmentRepository.save(
                Department(company = companyA, name = "Executive Management", description = "C-Suite & Strategic Executive Operations")
            )
        }

        var deptEng = allDepts.firstOrNull { it.name == "Engineering & Technology" }
        if (deptEng == null) {
            deptEng = departmentRepository.save(
                Department(company = companyA, name = "Engineering & Technology", description = "Core Product Development & Tech Infrastructure")
            )
        }

        var deptDev = allDepts.firstOrNull { it.name == "Software Development" }
        if (deptDev == null) {
            deptDev = departmentRepository.save(
                Department(company = companyA, name = "Software Development", description = "Full Stack Application Engineering", parentDepartment = deptEng)
            )
        }

        var deptHR = allDepts.firstOrNull { it.name == "Human Resources" }
        if (deptHR == null) {
            deptHR = departmentRepository.save(
                Department(company = companyA, name = "Human Resources", description = "Talent Acquisition & Employee Engagement")
            )
        }

        var deptFin = allDepts.firstOrNull { it.name == "Finance & Accounting" }
        if (deptFin == null) {
            deptFin = departmentRepository.save(
                Department(company = companyA, name = "Finance & Accounting", description = "Corporate Financial Operations & Treasury")
            )
        }

        // 3. Seed Staff Members (Organogram Hierarchy)
        val allStaff = staffRepository.findAll().filter { it.company.id == companyA.id && !it.isDeleted }
        var ceoStaff = allStaff.firstOrNull { it.employeeCode == "EMP-001" }
        if (ceoStaff == null) {
            ceoStaff = staffRepository.save(
                Staff(
                    company = companyA,
                    department = deptExec,
                    manager = null,
                    employeeCode = "EMP-001",
                    name = "Eleanor Vance",
                    title = "Chief Executive Officer",
                    empType = EmploymentType.PERMANENT,
                    email = "eleanor.vance@sunrichgroup.com",
                    cellNumber = "+1-555-0101",
                    dateJoined = LocalDate.of(2015, 3, 1),
                    status = EntityStatus.ACTIVE
                )
            )
        }

        var vpEngStaff = allStaff.firstOrNull { it.employeeCode == "EMP-002" }
        if (vpEngStaff == null) {
            vpEngStaff = staffRepository.save(
                Staff(
                    company = companyA,
                    department = deptEng,
                    manager = ceoStaff,
                    employeeCode = "EMP-002",
                    name = "Marcus Brody",
                    title = "VP of Engineering",
                    empType = EmploymentType.PERMANENT,
                    email = "marcus.brody@sunrichgroup.com",
                    cellNumber = "+1-555-0102",
                    dateJoined = LocalDate.of(2017, 5, 15),
                    status = EntityStatus.ACTIVE
                )
            )
        }

        var leadArchStaff = allStaff.firstOrNull { it.employeeCode == "EMP-003" }
        if (leadArchStaff == null) {
            leadArchStaff = staffRepository.save(
                Staff(
                    company = companyA,
                    department = deptDev,
                    manager = vpEngStaff,
                    employeeCode = "EMP-003",
                    name = "Dr. Henry Jones",
                    title = "Lead Software Architect",
                    empType = EmploymentType.PERMANENT,
                    email = "henry.jones@sunrichgroup.com",
                    cellNumber = "+1-555-0103",
                    dateJoined = LocalDate.of(2019, 9, 10),
                    status = EntityStatus.ACTIVE
                )
            )
        }

        var hrHeadStaff = allStaff.firstOrNull { it.employeeCode == "EMP-004" }
        if (hrHeadStaff == null) {
            hrHeadStaff = staffRepository.save(
                Staff(
                    company = companyA,
                    department = deptHR,
                    manager = ceoStaff,
                    employeeCode = "EMP-004",
                    name = "Sophia Al-Mansoor",
                    title = "Head of Human Resources",
                    empType = EmploymentType.PERMANENT,
                    email = "sophia.mansoor@sunrichgroup.com",
                    cellNumber = "+1-555-0104",
                    dateJoined = LocalDate.of(2020, 2, 1),
                    status = EntityStatus.ACTIVE
                )
            )
        }

        // Set department heads
        deptExec.headStaff = ceoStaff; departmentRepository.save(deptExec)
        deptEng.headStaff = vpEngStaff; departmentRepository.save(deptEng)
        deptHR.headStaff = hrHeadStaff; departmentRepository.save(deptHR)

        // 4. Seed Positions & Vacancies
        val allPositions = positionRepository.findAll().filter { it.company.id == companyA.id && !it.isDeleted }
        var posCeo = allPositions.firstOrNull { it.title == "Chief Executive Officer" }
        if (posCeo == null) {
            posCeo = positionRepository.save(
                Position(
                    company = companyA,
                    title = "Chief Executive Officer",
                    department = deptExec,
                    reportsToPosition = null,
                    isVacant = false,
                    staff = ceoStaff,
                    status = PositionStatus.FILLED
                )
            )
        }

        var posVpEng = allPositions.firstOrNull { it.title == "VP of Engineering" }
        if (posVpEng == null) {
            posVpEng = positionRepository.save(
                Position(
                    company = companyA,
                    title = "VP of Engineering",
                    department = deptEng,
                    reportsToPosition = posCeo,
                    isVacant = false,
                    staff = vpEngStaff,
                    status = PositionStatus.FILLED
                )
            )
        }

        var posArch = allPositions.firstOrNull { it.title == "Lead Software Architect" }
        if (posArch == null) {
            posArch = positionRepository.save(
                Position(
                    company = companyA,
                    title = "Lead Software Architect",
                    department = deptDev,
                    reportsToPosition = posVpEng,
                    isVacant = false,
                    staff = leadArchStaff,
                    status = PositionStatus.FILLED
                )
            )
        }

        var posVacantEng = allPositions.firstOrNull { it.title == "Senior Full Stack Engineer" }
        if (posVacantEng == null) {
            positionRepository.save(
                Position(
                    company = companyA,
                    title = "Senior Full Stack Engineer",
                    department = deptDev,
                    reportsToPosition = posArch,
                    isVacant = true,
                    staff = null,
                    status = PositionStatus.OPEN
                )
            )
        }

        log.info("Comprehensive test data seeding completed successfully!")
    }
}
