package com.sunrich.oms.workplace

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

// Hierarchy reads join fetch their owning chain so a list or map request never degrades into a
// lazy-loading query per row, and the soft-delete filter is expressed as an `in` over the allowed
// flags so the same query serves both the default and the include-archived view.

interface OfficeRepository:JpaRepository<Office,Long>,JpaSpecificationExecutor<Office>{
 fun existsByCompany_IdAndCodeIgnoreCaseAndIsDeletedFalse(companyId:Long,code:String):Boolean
 fun existsByCompany_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(companyId:Long,code:String,id:Long):Boolean
 @Query("select o from Office o join fetch o.company where o.isDeleted in :flags order by o.name") fun findAllScoped(@Param("flags")flags:Collection<Boolean>):List<Office>
 @Query("select o from Office o join fetch o.company c where c.id=:companyId and o.isDeleted in :flags order by o.name") fun findCompanyScoped(@Param("companyId")companyId:Long,@Param("flags")flags:Collection<Boolean>):List<Office>
}

interface BuildingRepository:JpaRepository<Building,Long>,JpaSpecificationExecutor<Building>{
 fun existsByOffice_IdAndCodeIgnoreCaseAndIsDeletedFalse(officeId:Long,code:String):Boolean
 fun existsByOffice_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(officeId:Long,code:String,id:Long):Boolean
 fun existsByOffice_IdAndIsDeletedFalse(id:Long):Boolean
 @Query("select b from Building b join fetch b.office o join fetch o.company where b.isDeleted in :flags order by b.name") fun findAllScoped(@Param("flags")flags:Collection<Boolean>):List<Building>
 @Query("select b from Building b join fetch b.office o join fetch o.company c where c.id=:companyId and b.isDeleted in :flags order by b.name") fun findCompanyScoped(@Param("companyId")companyId:Long,@Param("flags")flags:Collection<Boolean>):List<Building>
}

interface FloorRepository:JpaRepository<Floor,Long>,JpaSpecificationExecutor<Floor>{
 fun existsByBuilding_IdAndIsDeletedFalse(id:Long):Boolean
 @Query("select f from Floor f join fetch f.building b join fetch b.office o join fetch o.company where f.isDeleted in :flags order by f.displayOrder, f.name") fun findAllScoped(@Param("flags")flags:Collection<Boolean>):List<Floor>
 @Query("select f from Floor f join fetch f.building b join fetch b.office o join fetch o.company c where c.id=:companyId and f.isDeleted in :flags order by f.displayOrder, f.name") fun findCompanyScoped(@Param("companyId")companyId:Long,@Param("flags")flags:Collection<Boolean>):List<Floor>
}

interface ZoneRepository:JpaRepository<Zone,Long>,JpaSpecificationExecutor<Zone>{
 fun existsByFloor_IdAndCodeIgnoreCaseAndIsDeletedFalse(floorId:Long,code:String):Boolean
 fun existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(floorId:Long,code:String,id:Long):Boolean
 fun findAllByFloor_IdAndIsDeletedFalseOrderByName(id:Long):List<Zone>
 fun existsByFloor_IdAndIsDeletedFalse(id:Long):Boolean
 @Query("select z from Zone z join fetch z.floor f join fetch f.building b join fetch b.office o join fetch o.company where z.isDeleted in :flags order by z.name") fun findAllScoped(@Param("flags")flags:Collection<Boolean>):List<Zone>
 @Query("select z from Zone z join fetch z.floor f join fetch f.building b join fetch b.office o join fetch o.company c where c.id=:companyId and z.isDeleted in :flags order by z.name") fun findCompanyScoped(@Param("companyId")companyId:Long,@Param("flags")flags:Collection<Boolean>):List<Zone>
 @Query("select z from Zone z join fetch z.floor f join fetch f.building b join fetch b.office o join fetch o.company where f.id=:floorId and z.isDeleted in :flags order by z.name") fun findByFloor(@Param("floorId")floorId:Long,@Param("flags")flags:Collection<Boolean>):List<Zone>
}

interface DeskRepository:JpaRepository<Desk,Long>,JpaSpecificationExecutor<Desk>{
 fun existsByFloor_IdAndCodeIgnoreCaseAndIsDeletedFalse(floorId:Long,code:String):Boolean
 fun existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(floorId:Long,code:String,id:Long):Boolean
 fun findAllByFloor_IdAndIsDeletedFalseOrderByCode(id:Long):List<Desk>
 fun existsByFloor_IdAndIsDeletedFalse(id:Long):Boolean
 fun countByFloor_Building_Office_Company_IdAndIsDeletedFalse(companyId:Long):Long
 @Query("select d from Desk d join fetch d.floor f join fetch f.building b join fetch b.office o join fetch o.company left join fetch d.zone where f.id=:floorId and d.isDeleted in :flags order by d.code") fun findByFloor(@Param("floorId")floorId:Long,@Param("flags")flags:Collection<Boolean>):List<Desk>
 @Query("select d from Desk d join fetch d.floor f join fetch f.building b join fetch b.office o join fetch o.company left join fetch d.zone where d.isDeleted in :flags order by d.code") fun findAllScoped(@Param("flags")flags:Collection<Boolean>):List<Desk>
 @Query("select d from Desk d join fetch d.floor f join fetch f.building b join fetch b.office o join fetch o.company c left join fetch d.zone where c.id=:companyId and d.isDeleted in :flags order by d.code") fun findCompanyScoped(@Param("companyId")companyId:Long,@Param("flags")flags:Collection<Boolean>):List<Desk>
 @Query("select count(d) from Desk d where d.floor.building.office.company.id=:companyId and d.isDeleted=false and (d.mode=:mode or d.availability=:availability)") fun countUnavailable(@Param("companyId")companyId:Long,@Param("mode")mode:DeskMode,@Param("availability")availability:DeskAvailability):Long
}

interface DeskAssignmentRepository:JpaRepository<DeskAssignment,Long>,JpaSpecificationExecutor<DeskAssignment>{
 @Query("select a from DeskAssignment a where a.staff.id=:staffId and a.isDeleted=false and a.primaryAssignment=true and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date) order by a.effectiveFrom desc") fun activeForStaff(@Param("staffId")staffId:Long,@Param("date")date:LocalDate):List<DeskAssignment>
 @Query("select a from DeskAssignment a where a.desk.id=:deskId and a.isDeleted=false and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date) order by a.effectiveFrom desc") fun activeForDesk(@Param("deskId")deskId:Long,@Param("date")date:LocalDate):List<DeskAssignment>
 @Query("select a from DeskAssignment a where a.staff.id=:staffId and a.isDeleted=false and a.effectiveFrom<:to and (a.effectiveTo is null or a.effectiveTo>:from)") fun overlappingStaff(@Param("staffId")staffId:Long,@Param("from")from:LocalDate,@Param("to")to:LocalDate):List<DeskAssignment>
 @Query("select a from DeskAssignment a where a.desk.id=:deskId and a.isDeleted=false and a.effectiveFrom<:to and (a.effectiveTo is null or a.effectiveTo>:from)") fun overlappingDesk(@Param("deskId")deskId:Long,@Param("from")from:LocalDate,@Param("to")to:LocalDate):List<DeskAssignment>
 fun findAllByStaff_IdAndIsDeletedFalseOrderByEffectiveFromDesc(staffId:Long):List<DeskAssignment>
 @Query("select count(a) from DeskAssignment a where a.desk.floor.building.office.company.id=:companyId and a.isDeleted=false and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date)") fun countActive(@Param("companyId")companyId:Long,@Param("date")date:LocalDate):Long
 /** One query for every active assignment across a set of desks, so a floor map never issues a query per desk. */
 @Query("select a from DeskAssignment a join fetch a.desk d join fetch d.floor f join fetch f.building b join fetch b.office left join fetch d.zone join fetch a.staff s left join fetch s.department where d.id in :deskIds and a.isDeleted=false and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date)") fun activeForDesks(@Param("deskIds")deskIds:Collection<Long>,@Param("date")date:LocalDate):List<DeskAssignment>
 /** Assignments that start today, used to raise the activation notification exactly once. */
 @Query("select a from DeskAssignment a join fetch a.desk d join fetch d.floor f join fetch f.building b join fetch b.office join fetch a.staff join fetch a.assignedBy where a.isDeleted=false and a.effectiveFrom=:date") fun startingOn(@Param("date")date:LocalDate):List<DeskAssignment>
 /** Active assignments whose end date falls on a given day, used for the expiry warning. */
 @Query("select a from DeskAssignment a join fetch a.desk d join fetch d.floor f join fetch f.building b join fetch b.office join fetch a.staff join fetch a.assignedBy where a.isDeleted=false and a.effectiveTo=:date") fun endingOn(@Param("date")date:LocalDate):List<DeskAssignment>
 @Query("select count(distinct a.staff.id) from DeskAssignment a where a.desk.floor.building.office.company.id=:companyId and a.isDeleted=false and a.primaryAssignment=true and a.effectiveFrom<=:date and (a.effectiveTo is null or a.effectiveTo>:date)") fun countAssignedStaff(@Param("companyId")companyId:Long,@Param("date")date:LocalDate):Long
}

