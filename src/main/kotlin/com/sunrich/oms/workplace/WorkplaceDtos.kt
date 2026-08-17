package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.EntityStatus
import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class OfficeRequest(val companyId:Long,@field:NotBlank @field:Size(max=200)val name:String,@field:NotBlank @field:Size(max=50)val code:String,@field:Size(max=500)val address:String?=null,@field:Size(max=100)val city:String?=null,@field:Size(max=100)val country:String?=null,@field:NotBlank val timeZone:String="UTC",val status:EntityStatus=EntityStatus.ACTIVE,val version:Long?=null)
data class BuildingRequest(val officeId:Long,@field:NotBlank val name:String,@field:NotBlank val code:String,val description:String?=null,val status:EntityStatus=EntityStatus.ACTIVE,val version:Long?=null)
data class FloorRequest(val buildingId:Long,@field:NotBlank val name:String,val displayOrder:Int=0,val status:EntityStatus=EntityStatus.ACTIVE,val version:Long?=null)
data class ZoneRequest(val floorId:Long,@field:NotBlank val name:String,@field:NotBlank val code:String,@field:Pattern(regexp="^#[0-9A-Fa-f]{6}$")val colour:String="#64748b",val description:String?=null,val status:EntityStatus=EntityStatus.ACTIVE,val version:Long?=null)
data class DeskRequest(val floorId:Long,val zoneId:Long?=null,@field:NotBlank val code:String,val displayName:String?=null,val mode:DeskMode=DeskMode.ASSIGNED,val availability:DeskAvailability=DeskAvailability.AVAILABLE,@field:DecimalMin("0")@field:DecimalMax("100")val x:BigDecimal,@field:DecimalMin("0")@field:DecimalMax("100")val y:BigDecimal,@field:DecimalMin("0.01")val width:BigDecimal,@field:DecimalMin("0.01")val height:BigDecimal,val rotation:Int=0,@field:Min(1)val capacity:Int=1,val telephoneExtension:String?=null,val accessible:Boolean=false,val equipmentTags:String?=null,val notes:String?=null,val status:EntityStatus=EntityStatus.ACTIVE,val version:Long?=null)
data class DeskBatchRequest(val desks:List<DeskRequest>)
data class AssignmentRequest(val deskId:Long,val staffId:Long,val effectiveFrom:LocalDate,val effectiveTo:LocalDate?=null,val primaryAssignment:Boolean=true,val reason:String?=null)
data class TransferRequest(val targetDeskId:Long,val effectiveDate:LocalDate,val reason:String?=null)
data class ReleaseRequest(val effectiveTo:LocalDate,val reason:String?=null,val version:Long)
data class OfficeResponse(val id:Long,val version:Long,val companyId:Long,val companyName:String,val name:String,val code:String,val address:String?,val city:String?,val country:String?,val timeZone:String,val status:EntityStatus,val isDeleted:Boolean)
data class BuildingResponse(val id:Long,val version:Long,val officeId:Long,val officeName:String,val companyId:Long,val name:String,val code:String,val description:String?,val status:EntityStatus,val isDeleted:Boolean)
data class FloorResponse(val id:Long,val version:Long,val buildingId:Long,val buildingName:String,val officeId:Long,val officeName:String,val companyId:Long,val companyName:String,val name:String,val displayOrder:Int,val hasPlan:Boolean,val planOriginalName:String?,val planMediaType:String?,val planWidth:Int?,val planHeight:Int?,val status:EntityStatus,val isDeleted:Boolean)
data class ZoneResponse(val id:Long,val version:Long,val floorId:Long,val name:String,val code:String,val colour:String,val description:String?,val status:EntityStatus,val isDeleted:Boolean)
data class AssignmentResponse(val id:Long,val version:Long,val deskId:Long,val deskCode:String,val floorId:Long,val floorName:String,val buildingName:String,val officeName:String,val zoneName:String?,val telephoneExtension:String?,val staffId:Long,val staffName:String?,val employeeCode:String?,val departmentId:Long?,val departmentName:String?,val positionTitle:String?,val effectiveFrom:LocalDate,val effectiveTo:LocalDate?,val primaryAssignment:Boolean,val reason:String?,val releaseReason:String?)
data class DeskResponse(val id:Long,val version:Long,val floorId:Long,val zoneId:Long?,val zoneName:String?,val code:String,val displayName:String?,val mode:DeskMode,val availability:DeskAvailability,val x:BigDecimal,val y:BigDecimal,val width:BigDecimal,val height:BigDecimal,val rotation:Int,val capacity:Int,val telephoneExtension:String?,val accessible:Boolean,val equipmentTags:String?,val notes:String?,val status:EntityStatus,val isDeleted:Boolean,val assignment:AssignmentResponse?=null)
data class FloorMapResponse(val floor:FloorResponse,val planUrl:String?,val zones:List<ZoneResponse>,val desks:List<DeskResponse>)
data class WorkplaceSummary(val totalDesks:Long,val assignedDesks:Long,val availableDesks:Long,val unavailableDesks:Long,val staffWithoutDesks:Long,val utilizationPercent:Double)
