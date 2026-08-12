package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.realtime.OrganogramUpdatePublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrganizationService(
    private val companies: CompanyRepository,
    private val departments: DepartmentRepository,
    private val staff: StaffRepository,
    private val positions: PositionRepository,
    private val updates: OrganogramUpdatePublisher
) {
    @Transactional(readOnly = true)
    fun listCompanies(includeDeleted: Boolean) = companies.findAll()
        .filter { includeDeleted || !it.isDeleted }.map(::companyResponse)

    @Transactional
    fun createCompany(request: CompanyRequest): CompanyResponse = companyResponse(companies.save(
        Company(
            name = requiredText(request.name, "Company name"),
            regNumber = request.regNumber.clean(),
            headOffice = request.headOffice.clean(),
            dateEstablished = request.dateEstablished,
            logoUrl = request.logoUrl.clean(),
            status = request.status ?: EntityStatus.ACTIVE
        )
    )).also { updates.publish("Company", "CREATE", it.id) }

    @Transactional
    fun updateCompany(id: Long, request: CompanyRequest): CompanyResponse {
        val entity = company(id)
        request.name?.let { entity.name = requiredText(it, "Company name") }
        entity.regNumber = request.regNumber.clean()
        entity.headOffice = request.headOffice.clean()
        entity.dateEstablished = request.dateEstablished
        entity.logoUrl = request.logoUrl.clean()
        request.status?.let { entity.status = it }
        return companyResponse(companies.save(entity)).also { updates.publish("Company", "UPDATE", it.id) }
    }

    @Transactional
    fun deleteCompany(id: Long) = companies.save(company(id).apply { markDeleted() })
        .also { updates.publish("Company", "DELETE", id) }

    @Transactional
    fun restoreCompany(id: Long) = companyResponse(companies.save(company(id).apply { restore() }))
        .also { updates.publish("Company", "RESTORE", id) }

    @Transactional(readOnly = true)
    fun listDepartments(includeDeleted: Boolean) = departments.findAll()
        .filter { includeDeleted || !it.isDeleted }.map(::departmentResponse)

    @Transactional
    fun createDepartment(request: DepartmentRequest): DepartmentResponse {
        val company = company(requiredId(request.companyId, "companyId"))
        val entity = Department(
            company = company,
            name = requiredText(request.name, "Department name"),
            description = request.description.clean(),
            parentDepartment = request.parentDeptId?.let(::department),
            status = request.status ?: EntityStatus.ACTIVE
        )
        entity.headStaff = request.headStaffId?.let(::staffMember)
        validateDepartmentLinks(entity)
        return departmentResponse(departments.save(entity)).also { updates.publish("Department", "CREATE", it.id) }
    }

    @Transactional
    fun updateDepartment(id: Long, request: DepartmentRequest): DepartmentResponse {
        val entity = department(id)
        request.companyId?.let { entity.company = company(it) }
        request.name?.let { entity.name = requiredText(it, "Department name") }
        entity.description = request.description.clean()
        entity.parentDepartment = request.parentDeptId?.let(::department)
        entity.headStaff = request.headStaffId?.let(::staffMember)
        request.status?.let { entity.status = it }
        if (entity.parentDepartment?.id == entity.id) throw BadRequestException("A department cannot be its own parent")
        validateDepartmentLinks(entity)
        return departmentResponse(departments.save(entity)).also { updates.publish("Department", "UPDATE", it.id) }
    }

    @Transactional
    fun deleteDepartment(id: Long) = departments.save(department(id).apply { markDeleted() })
        .also { updates.publish("Department", "DELETE", id) }

    @Transactional
    fun restoreDepartment(id: Long) = departmentResponse(departments.save(department(id).apply { restore() }))
        .also { updates.publish("Department", "RESTORE", id) }

    @Transactional(readOnly = true)
    fun listStaff(includeDeleted: Boolean) = staff.findAll()
        .filter { includeDeleted || !it.isDeleted }.map(::staffResponse)

    @Transactional
    fun createStaff(request: StaffRequest): StaffResponse {
        val entity = Staff(
            company = company(requiredId(request.companyId, "companyId")),
            department = request.deptId?.let(::department),
            manager = request.managerId?.let(::staffMember),
            employeeCode = request.employeeCode.clean(),
            name = requiredText(request.name, "Staff name"),
            title = request.title.clean(),
            empType = request.empType ?: EmploymentType.PERMANENT,
            email = request.email.clean(),
            landline = request.landline.clean(),
            cellNumber = request.cellNumber.clean(),
            dateJoined = request.dateJoined,
            dateLeft = request.dateLeft,
            status = request.status ?: EntityStatus.ACTIVE,
            photoUrl = request.photoUrl.clean()
        )
        validateStaffLinks(entity)
        return staffResponse(staff.save(entity)).also { updates.publish("Staff", "CREATE", it.id) }
    }

    @Transactional
    fun updateStaff(id: Long, request: StaffRequest): StaffResponse {
        val entity = staffMember(id)
        request.companyId?.let { entity.company = company(it) }
        entity.department = request.deptId?.let(::department)
        entity.manager = request.managerId?.let(::staffMember)
        request.name?.let { entity.name = requiredText(it, "Staff name") }
        entity.employeeCode = request.employeeCode.clean()
        entity.title = request.title.clean()
        request.empType?.let { entity.empType = it }
        entity.email = request.email.clean()
        entity.landline = request.landline.clean()
        entity.cellNumber = request.cellNumber.clean()
        entity.dateJoined = request.dateJoined
        entity.dateLeft = request.dateLeft
        request.status?.let { entity.status = it }
        entity.photoUrl = request.photoUrl.clean()
        if (entity.manager?.id == entity.id) throw BadRequestException("A staff member cannot manage themselves")
        validateStaffLinks(entity)
        return staffResponse(staff.save(entity)).also { updates.publish("Staff", "UPDATE", it.id) }
    }

    @Transactional
    fun deleteStaff(id: Long) = staff.save(staffMember(id).apply { markDeleted() })
        .also { updates.publish("Staff", "DELETE", id) }

    @Transactional
    fun restoreStaff(id: Long) = staffResponse(staff.save(staffMember(id).apply { restore() }))
        .also { updates.publish("Staff", "RESTORE", id) }

    @Transactional(readOnly = true)
    fun listPositions(includeDeleted: Boolean) = positions.findAll()
        .filter { includeDeleted || !it.isDeleted }.map(::positionResponse)

    @Transactional
    fun createPosition(request: PositionRequest): PositionResponse {
        val entity = Position(
            company = company(requiredId(request.companyId, "companyId")),
            title = requiredText(request.title, "Position title"),
            department = request.deptId?.let(::department),
            isVacant = request.isVacant ?: true,
            staff = request.staffId?.let(::staffMember),
            status = request.status ?: PositionStatus.OPEN
        )
        validatePositionLinks(entity)
        return positionResponse(positions.save(entity)).also { updates.publish("Position", "CREATE", it.id) }
    }

    @Transactional
    fun updatePosition(id: Long, request: PositionRequest): PositionResponse {
        val entity = position(id)
        request.companyId?.let { entity.company = company(it) }
        request.title?.let { entity.title = requiredText(it, "Position title") }
        entity.department = request.deptId?.let(::department)
        request.isVacant?.let { entity.isVacant = it }
        entity.staff = request.staffId?.let(::staffMember)
        request.status?.let { entity.status = it }
        validatePositionLinks(entity)
        return positionResponse(positions.save(entity)).also { updates.publish("Position", "UPDATE", it.id) }
    }

    @Transactional
    fun deletePosition(id: Long) = positions.save(position(id).apply { markDeleted() })
        .also { updates.publish("Position", "DELETE", id) }

    @Transactional
    fun restorePosition(id: Long) = positionResponse(positions.save(position(id).apply { restore() }))
        .also { updates.publish("Position", "RESTORE", id) }

    private fun company(id: Long) = companies.findById(id)
        .orElseThrow { ResourceNotFoundException("Company", id) }
    private fun department(id: Long) = departments.findById(id)
        .orElseThrow { ResourceNotFoundException("Department", id) }
    private fun staffMember(id: Long) = staff.findById(id)
        .orElseThrow { ResourceNotFoundException("Staff", id) }
    private fun position(id: Long) = positions.findById(id)
        .orElseThrow { ResourceNotFoundException("Position", id) }

    private fun validateDepartmentLinks(entity: Department) {
        if (entity.parentDepartment != null && entity.parentDepartment?.company?.id != entity.company.id) {
            throw BadRequestException("Parent department must belong to the same company")
        }
        if (entity.headStaff != null && entity.headStaff?.company?.id != entity.company.id) {
            throw BadRequestException("Department head must belong to the same company")
        }
    }

    private fun validateStaffLinks(entity: Staff) {
        if (entity.department != null && entity.department?.company?.id != entity.company.id) {
            throw BadRequestException("Department must belong to the selected company")
        }
        if (entity.manager != null && entity.manager?.company?.id != entity.company.id) {
            throw BadRequestException("Manager must belong to the selected company")
        }
    }

    private fun validatePositionLinks(entity: Position) {
        if (entity.department != null && entity.department?.company?.id != entity.company.id) {
            throw BadRequestException("Department must belong to the selected company")
        }
        if (entity.staff != null && entity.staff?.company?.id != entity.company.id) {
            throw BadRequestException("Assigned staff member must belong to the selected company")
        }
    }

    private fun companyResponse(e: Company) = CompanyResponse(
        e.id!!, e.name, e.regNumber, e.headOffice, e.dateEstablished, e.logoUrl,
        e.status, e.isDeleted, e.createdAt, e.updatedAt
    )

    private fun departmentResponse(e: Department) = DepartmentResponse(
        e.id!!, e.company.id!!, e.name, e.description, e.parentDepartment?.id,
        e.headStaff?.id, e.status, e.isDeleted, e.createdAt, e.updatedAt
    )

    private fun staffResponse(e: Staff) = StaffResponse(
        e.id!!, e.company.id!!, e.department?.id, e.manager?.id, e.employeeCode,
        e.name, e.title, e.empType, e.email, e.landline, e.cellNumber, e.dateJoined,
        e.dateLeft, e.status, e.photoUrl, e.isDeleted, e.createdAt, e.updatedAt
    )

    private fun positionResponse(e: Position) = PositionResponse(
        e.id!!, e.company.id!!, e.title, e.department?.id, e.isVacant, e.staff?.id,
        e.status, e.isDeleted, e.createdAt, e.updatedAt
    )

    private fun requiredText(value: String?, field: String): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("$field is required")

    private fun requiredId(value: Long?, field: String): Long =
        value ?: throw BadRequestException("$field is required")

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
