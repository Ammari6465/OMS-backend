package com.sunrich.oms.organization

import org.springframework.data.jpa.repository.JpaRepository

interface CompanyRepository : JpaRepository<Company, Long>
interface DepartmentRepository : JpaRepository<Department, Long>
interface StaffRepository : JpaRepository<Staff, Long>
interface PositionRepository : JpaRepository<Position, Long>
