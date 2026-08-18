package com.sunrich.oms.workplace

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface OfficeRepository:JpaRepository<Office,Long>,JpaSpecificationExecutor<Office>{fun existsByCompany_IdAndCodeIgnoreCaseAndIsDeletedFalse(companyId:Long,code:String):Boolean;fun existsByCompany_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(companyId:Long,code:String,id:Long):Boolean}
interface BuildingRepository:JpaRepository<Building,Long>,JpaSpecificationExecutor<Building>{fun existsByOffice_IdAndCodeIgnoreCaseAndIsDeletedFalse(officeId:Long,code:String):Boolean;fun existsByOffice_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(officeId:Long,code:String,id:Long):Boolean;fun existsByOffice_IdAndIsDeletedFalse(id:Long):Boolean}
interface FloorRepository:JpaRepository<Floor,Long>,JpaSpecificationExecutor<Floor>{fun existsByBuilding_IdAndIsDeletedFalse(id:Long):Boolean}
interface ZoneRepository:JpaRepository<Zone,Long>,JpaSpecificationExecutor<Zone>{fun existsByFloor_IdAndCodeIgnoreCaseAndIsDeletedFalse(floorId:Long,code:String):Boolean;fun existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(floorId:Long,code:String,id:Long):Boolean;fun findAllByFloor_IdAndIsDeletedFalseOrderByName(id:Long):List<Zone>}
interface DeskRepository:JpaRepository<Desk,Long>,JpaSpecificationExecutor<Desk>{fun existsByFloor_IdAndCodeIgnoreCaseAndIsDeletedFalse(floorId:Long,code:String):Boolean;fun existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(floorId:Long,code:String,id:Long):Boolean;fun findAllByFloor_IdAndIsDeletedFalseOrderByCode(id:Long):List<Desk>;fun existsByFloor_IdAndIsDeletedFalse(id:Long):Boolean;fun countByFloor_Building_Office_Company_IdAndIsDeletedFalse(companyId:Long):Long}
interface DeskAssignmentRepository:JpaRepository<DeskAssignment,Long>,JpaSpecificationExecutor<DeskAssignment>{
 @Query("select a from DeskAssignment a where a.staff.id=:staffId and a.isDeleted=false and a.primaryAssignment=true and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date) order by a.effectiveFrom desc") fun activeForStaff(@Param("staffId")staffId:Long,@Param("date")date:LocalDate):List<DeskAssignment>
 @Query("select a from DeskAssignment a where a.desk.id=:deskId and a.isDeleted=false and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date) order by a.effectiveFrom desc") fun activeForDesk(@Param("deskId")deskId:Long,@Param("date")date:LocalDate):List<DeskAssignment>
 @Query("select a from DeskAssignment a where a.staff.id=:staffId and a.isDeleted=false and a.effectiveFrom<:to and (a.effectiveTo is null or a.effectiveTo>:from)") fun overlappingStaff(@Param("staffId")staffId:Long,@Param("from")from:LocalDate,@Param("to")to:LocalDate):List<DeskAssignment>
 @Query("select a from DeskAssignment a where a.desk.id=:deskId and a.isDeleted=false and a.effectiveFrom<:to and (a.effectiveTo is null or a.effectiveTo>:from)") fun overlappingDesk(@Param("deskId")deskId:Long,@Param("from")from:LocalDate,@Param("to")to:LocalDate):List<DeskAssignment>
 fun findAllByStaff_IdAndIsDeletedFalseOrderByEffectiveFromDesc(staffId:Long):List<DeskAssignment>
 @Query("select count(a) from DeskAssignment a where a.desk.floor.building.office.company.id=:companyId and a.isDeleted=false and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date)") fun countActive(@Param("companyId")companyId:Long,@Param("date")date:LocalDate):Long
}
