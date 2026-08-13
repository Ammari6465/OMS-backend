package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
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
    fun listPositions(includeDeleted: Boolean) = positions.findAll()
        .filter { includeDeleted || !it.isDeleted }.map(::positionResponse)

    @Transactional
    fun createPosition(request: PositionRequest): PositionResponse {
        val entity = Position(
            company = company(requiredId(request.companyId, "companyId")),
            title = requiredText(request.title, "Position title"),
            department = request.deptId?.let(::department),
            reportsToPosition = request.reportsToPositionId?.let(::position),
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
        entity.reportsToPosition = request.reportsToPositionId?.let(::position)
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

    private fun validatePositionLinks(entity: Position) {
        if (entity.department != null && entity.department?.company?.id != entity.company.id) {
            throw BadRequestException("Department must belong to the selected company")
        }
        if (entity.staff != null && entity.staff?.company?.id != entity.company.id) {
            throw BadRequestException("Assigned staff member must belong to the selected company")
        }
        if (entity.staff?.isDeleted == true || entity.staff?.status != null && entity.staff?.status != EntityStatus.ACTIVE) {
            throw BadRequestException("Assigned staff member must be active")
        }
        if (entity.staff != null && entity.department != null && entity.staff?.department?.id != entity.department?.id) {
            throw BadRequestException("Assigned staff member must belong to the position's department")
        }
        entity.staff?.id?.let { staffId ->
            val occupied = positions.findFirstByStaff_IdAndIsDeletedFalse(staffId)
            if (occupied != null && occupied.id != entity.id) {
                throw ConflictException("Staff member is already assigned to another position.")
            }
            entity.isVacant = false
            if (entity.status == PositionStatus.OPEN) entity.status = PositionStatus.FILLED
        } ?: run {
            entity.isVacant = true
            if (entity.status == PositionStatus.FILLED) entity.status = PositionStatus.OPEN
        }
    }

    private fun companyResponse(e: Company) = CompanyResponse(
        e.id!!, e.name, e.regNumber, e.headOffice, e.dateEstablished, e.logoUrl,
        e.status, e.isDeleted, e.createdAt, e.updatedAt
    )

    private fun positionResponse(e: Position) = PositionResponse(
        id = e.id!!, companyId = e.company.id!!, title = e.title, companyName = e.company.name,
        deptId = e.department?.id, departmentName = e.department?.name,
        reportsToPositionId = e.reportsToPosition?.id, reportsToPositionTitle = e.reportsToPosition?.title,
        isVacant = e.isVacant, staffId = e.staff?.id, staffName = e.staff?.name,
        status = e.status, isDeleted = e.isDeleted, version = e.version,
        createdBy = e.createdBy, updatedBy = e.updatedBy, createdAt = e.createdAt, updatedAt = e.updatedAt
    )

    private fun requiredText(value: String?, field: String): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("$field is required")

    private fun requiredId(value: Long?, field: String): Long =
        value ?: throw BadRequestException("$field is required")

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
