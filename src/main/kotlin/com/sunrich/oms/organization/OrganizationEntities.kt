package com.sunrich.oms.organization

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "companies",
    indexes = [
        Index(name = "idx_companies_status", columnList = "status,is_deleted"),
        Index(name = "idx_companies_parent", columnList = "parent_company_id,is_deleted")
    ]
)
class Company(
    @Column(nullable = false, length = 200)
    var name: String,

    @Column(name = "reg_number", length = 100, unique = true)
    var regNumber: String? = null,

    @Column(name = "head_office", length = 300)
    var headOffice: String? = null,

    @Column(name = "date_established")
    var dateEstablished: LocalDate? = null,

    @Column(name = "logo_url", length = 500)
    var logoUrl: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EntityStatus = EntityStatus.ACTIVE,

    /**
     * Group hierarchy. The holding company ("Sunrich Companies") has no parent;
     * every sister concern points at it. Self-referencing so the group can nest
     * further without a second table.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_company_id", foreignKey = ForeignKey(name = "fk_companies_parent"))
    var parentCompany: Company? = null
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_id")
    var id: Long? = null

    /** A company with no parent is the group holding company. */
    val isGroupParent: Boolean get() = parentCompany == null
}

@Entity
@Table(
    name = "departments",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_departments_company_name",
            columnNames = ["company_id", "name"]
        )
    ],
    indexes = [
        Index(name = "idx_departments_company", columnList = "company_id,is_deleted"),
        Index(name = "idx_departments_parent", columnList = "parent_dept_id")
    ]
)
class Department(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, foreignKey = ForeignKey(name = "fk_departments_company"))
    var company: Company,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(length = 1000)
    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_dept_id", foreignKey = ForeignKey(name = "fk_departments_parent"))
    var parentDepartment: Department? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EntityStatus = EntityStatus.ACTIVE
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dept_id")
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "head_staff_id", foreignKey = ForeignKey(name = "fk_departments_head_staff"))
    var headStaff: Staff? = null
}

@Entity
@Table(
    name = "staff",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_staff_company_empcode",
            columnNames = ["company_id", "employee_code"]
        )
    ],
    indexes = [
        Index(name = "idx_staff_company", columnList = "company_id,is_deleted"),
        Index(name = "idx_staff_department", columnList = "dept_id"),
        Index(name = "idx_staff_manager", columnList = "manager_id"),
        Index(name = "idx_staff_status", columnList = "status"),
        Index(name = "idx_staff_name", columnList = "name"),
        Index(name = "idx_staff_email", columnList = "email")
    ]
)
class Staff(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, foreignKey = ForeignKey(name = "fk_staff_company"))
    var company: Company,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id", foreignKey = ForeignKey(name = "fk_staff_department"))
    var department: Department? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id", foreignKey = ForeignKey(name = "fk_staff_manager"))
    var manager: Staff? = null,

    @Column(name = "employee_code", length = 100)
    var employeeCode: String? = null,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(length = 200)
    var title: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "emp_type", nullable = false, length = 30)
    var empType: EmploymentType = EmploymentType.PERMANENT,

    @Column(length = 200)
    var email: String? = null,

    @Column(length = 50)
    var landline: String? = null,

    @Column(name = "cell_number", length = 50)
    var cellNumber: String? = null,

    @Column(name = "date_joined")
    var dateJoined: LocalDate? = null,

    @Column(name = "date_left")
    var dateLeft: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EntityStatus = EntityStatus.ACTIVE,

    @Column(name = "photo_url", length = 500)
    var photoUrl: String? = null
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "staff_id")
    var id: Long? = null
}

@Entity
@Table(
    name = "positions",
    indexes = [
        Index(name = "idx_positions_company", columnList = "company_id,is_deleted"),
        Index(name = "idx_positions_department", columnList = "dept_id"),
        Index(name = "idx_positions_staff", columnList = "staff_id"),
        Index(name = "idx_positions_reports_to", columnList = "reports_to_position_id")
    ]
)
class Position(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, foreignKey = ForeignKey(name = "fk_positions_company"))
    var company: Company,

    @Column(nullable = false, length = 200)
    var title: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id", foreignKey = ForeignKey(name = "fk_positions_department"))
    var department: Department? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reports_to_position_id", foreignKey = ForeignKey(name = "fk_position_reports_to"))
    var reportsToPosition: Position? = null,

    @Column(name = "is_vacant", nullable = false)
    var isVacant: Boolean = true,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", foreignKey = ForeignKey(name = "fk_positions_staff"))
    var staff: Staff? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PositionStatus = PositionStatus.OPEN
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "position_id")
    var id: Long? = null
}
