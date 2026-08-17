package com.sunrich.oms.workplace

import com.sunrich.oms.common.dto.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.*
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

private const val MANAGE="hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')"

@RestController @RequestMapping("/workplaces")
class WorkplaceController(private val service:WorkplaceService){
 @GetMapping("/offices") fun offices(@RequestParam(required=false)companyId:Long?,@RequestParam(defaultValue="false")includeDeleted:Boolean)=ApiResponse.ok(service.listOffices(companyId,includeDeleted))
 @PostMapping("/offices") @PreAuthorize(MANAGE) fun createOffice(@Valid @RequestBody r:OfficeRequest)=ApiResponse.ok(service.createOffice(r),"Office created")
 @PutMapping("/offices/{id}") @PreAuthorize(MANAGE) fun updateOffice(@PathVariable id:Long,@Valid @RequestBody r:OfficeRequest)=ApiResponse.ok(service.updateOffice(id,r),"Office updated")
 @GetMapping("/buildings") fun buildings(@RequestParam(required=false)officeId:Long?,@RequestParam(defaultValue="false")includeDeleted:Boolean)=ApiResponse.ok(service.listBuildings(officeId,includeDeleted))
 @PostMapping("/buildings") @PreAuthorize(MANAGE) fun createBuilding(@Valid @RequestBody r:BuildingRequest)=ApiResponse.ok(service.createBuilding(r),"Building created")
 @PutMapping("/buildings/{id}") @PreAuthorize(MANAGE) fun updateBuilding(@PathVariable id:Long,@Valid @RequestBody r:BuildingRequest)=ApiResponse.ok(service.updateBuilding(id,r),"Building updated")
 @GetMapping("/floors") fun floors(@RequestParam(required=false)buildingId:Long?,@RequestParam(defaultValue="false")includeDeleted:Boolean)=ApiResponse.ok(service.listFloors(buildingId,includeDeleted))
 @PostMapping("/floors") @PreAuthorize(MANAGE) fun createFloor(@Valid @RequestBody r:FloorRequest)=ApiResponse.ok(service.createFloor(r),"Floor created")
 @PutMapping("/floors/{id}") @PreAuthorize(MANAGE) fun updateFloor(@PathVariable id:Long,@Valid @RequestBody r:FloorRequest)=ApiResponse.ok(service.updateFloor(id,r),"Floor updated")
 @GetMapping("/floors/{id}/map") fun map(@PathVariable id:Long)=ApiResponse.ok(service.map(id))
 @PostMapping("/floors/{id}/plan",consumes=[MediaType.MULTIPART_FORM_DATA_VALUE]) @PreAuthorize(MANAGE) fun upload(@PathVariable id:Long,@RequestPart("file")file:MultipartFile)=ApiResponse.ok(service.uploadPlan(id,file),"Floor plan uploaded")
 @GetMapping("/floors/{id}/plan") fun plan(@PathVariable id:Long):ResponseEntity<ByteArray>{val(bytes,type,name)=service.plan(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"${name.replace("\"","")}\"").header(HttpHeaders.CACHE_CONTROL,"private, max-age=300").body(bytes)}
 @DeleteMapping("/floors/{id}/plan") @PreAuthorize(MANAGE) fun removePlan(@PathVariable id:Long):ApiResponse<Unit>{service.removePlan(id);return ApiResponse.ok("Floor plan removed")}
 @GetMapping("/zones") fun zones(@RequestParam(required=false)floorId:Long?,@RequestParam(defaultValue="false")includeDeleted:Boolean)=ApiResponse.ok(service.listZones(floorId,includeDeleted))
 @PostMapping("/zones") @PreAuthorize(MANAGE) fun createZone(@Valid @RequestBody r:ZoneRequest)=ApiResponse.ok(service.createZone(r),"Zone created")
 @PutMapping("/zones/{id}") @PreAuthorize(MANAGE) fun updateZone(@PathVariable id:Long,@Valid @RequestBody r:ZoneRequest)=ApiResponse.ok(service.updateZone(id,r),"Zone updated")
 @GetMapping("/desks") fun desks(@RequestParam(required=false)floorId:Long?,@RequestParam(defaultValue="false")includeDeleted:Boolean)=ApiResponse.ok(service.listDesks(floorId,includeDeleted))
 @PostMapping("/desks") @PreAuthorize(MANAGE) fun createDesk(@Valid @RequestBody r:DeskRequest)=ApiResponse.ok(service.createDesk(r),"Desk created")
 @PutMapping("/desks/{id}") @PreAuthorize(MANAGE) fun updateDesk(@PathVariable id:Long,@Valid @RequestBody r:DeskRequest)=ApiResponse.ok(service.updateDesk(id,r),"Desk updated")
 @PutMapping("/floors/{id}/desks/batch") @PreAuthorize(MANAGE) fun batch(@PathVariable id:Long,@Valid @RequestBody r:DeskBatchRequest)=ApiResponse.ok(service.batch(id,r),"Floor map saved")
 @PostMapping("/assignments") @PreAuthorize(MANAGE) fun assign(@Valid @RequestBody r:AssignmentRequest)=ApiResponse.ok(service.assign(r),"Desk assigned")
 @PostMapping("/assignments/{id}/transfer") @PreAuthorize(MANAGE) fun transfer(@PathVariable id:Long,@Valid @RequestBody r:TransferRequest)=ApiResponse.ok(service.transfer(id,r),"Desk transferred")
 @PostMapping("/assignments/{id}/release") @PreAuthorize(MANAGE) fun release(@PathVariable id:Long,@Valid @RequestBody r:ReleaseRequest)=ApiResponse.ok(service.release(id,r),"Desk released")
 @GetMapping("/assignments/staff/{staffId}/current") fun current(@PathVariable staffId:Long)=ApiResponse.ok(service.currentForStaff(staffId))
 @GetMapping("/assignments/staff/{staffId}/history") fun history(@PathVariable staffId:Long)=ApiResponse.ok(service.history(staffId))
 @GetMapping("/summary") fun summary(@RequestParam(required=false)companyId:Long?)=ApiResponse.ok(service.summary(companyId))
 @DeleteMapping("/{kind}/{id}") @PreAuthorize(MANAGE) fun archive(@PathVariable kind:String,@PathVariable id:Long):ApiResponse<Unit>{service.archive(kind,id);return ApiResponse.ok("Workplace record archived")}
 @PatchMapping("/{kind}/{id}/restore") @PreAuthorize(MANAGE) fun restore(@PathVariable kind:String,@PathVariable id:Long):ApiResponse<Unit>{service.restore(kind,id);return ApiResponse.ok("Workplace record restored")}
}
