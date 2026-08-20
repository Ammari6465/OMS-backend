package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.*
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.systemdata.*
import com.sunrich.oms.user.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.time.*

@Service
class WorkplaceService(
 private val offices:OfficeRepository,private val buildings:BuildingRepository,private val floors:FloorRepository,private val zones:ZoneRepository,private val desks:DeskRepository,private val assignments:DeskAssignmentRepository,
 private val companies:CompanyRepository,private val staff:StaffRepository,private val positions:PositionRepository,private val users:UserRepository,
 private val storage:FloorPlanStorage,private val audit:AuditTrailService,private val notifications:NotificationDeliveryService,private val clock:Clock,
 @Value("\${oms.workplace.hide-staff-names:false}")private val hideNames:Boolean
){
 private val log=org.slf4j.LoggerFactory.getLogger(javaClass)
 fun listOffices(companyId:Long?,includeDeleted:Boolean=false):List<OfficeResponse>{val f=flags(includeDeleted);val cid=principalCompany()?:companyId;val list=if(cid==null)offices.findAllScoped(f) else offices.findCompanyScoped(cid,f);return list.filter{companyId==null||it.company.id==companyId}.map(::office)}
 fun listBuildings(officeId:Long?,includeDeleted:Boolean=false)=scopedList(includeDeleted,buildings::findAllScoped,buildings::findCompanyScoped).filter{officeId==null||it.office.id==officeId}.map(::building)
 fun listFloors(buildingId:Long?,includeDeleted:Boolean=false)=scopedList(includeDeleted,floors::findAllScoped,floors::findCompanyScoped).filter{buildingId==null||it.building.id==buildingId}.map(::floor)
 fun listZones(floorId:Long?,includeDeleted:Boolean=false)=(if(floorId!=null){ownedFloor(floorId);zones.findByFloor(floorId,flags(includeDeleted))}else scopedList(includeDeleted,zones::findAllScoped,zones::findCompanyScoped)).map(::zone)
 fun listDesks(floorId:Long?,includeDeleted:Boolean=false):List<DeskResponse>{val list=if(floorId!=null){ownedFloor(floorId);desks.findByFloor(floorId,flags(includeDeleted))}else scopedList(includeDeleted,desks::findAllScoped,desks::findCompanyScoped);val ctx=assignmentContext(list);return list.map{desk(it,ctx)}}

 @Transactional fun createOffice(r:OfficeRequest):OfficeResponse{scope(r.companyId);val c=companies.findById(r.companyId).orElseThrow{ResourceNotFoundException("Company",r.companyId)};val code=code(r.code);if(offices.existsByCompany_IdAndCodeIgnoreCaseAndIsDeletedFalse(c.id!!,code))duplicate("Office code");val e=offices.save(Office(c,text(r.name,"Office name"),code,r.address?.trim(),r.city?.trim(),r.country?.trim(),validZone(r.timeZone),r.status));record(e.company.id!!,"Office",e.id,AuditAction.CREATE,null,"code=$code,name=${e.name}");return office(e)}
 @Transactional fun updateOffice(id:Long,r:OfficeRequest):OfficeResponse{val e=ownedOffice(id);version(e.version,r.version);scope(r.companyId);if(e.company.id!=r.companyId)throw BadRequestException("Office company cannot be changed");val before="code=${e.code},name=${e.name}";val code=code(r.code);if(offices.existsByCompany_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(r.companyId,code,id))duplicate("Office code");e.name=text(r.name,"Office name");e.code=code;e.address=r.address?.trim();e.city=r.city?.trim();e.country=r.country?.trim();e.timeZone=validZone(r.timeZone);e.status=r.status;val s=offices.saveAndFlush(e);record(r.companyId,"Office",id,AuditAction.UPDATE,before,"code=${s.code},name=${s.name}");return office(s)}
 @Transactional fun createBuilding(r:BuildingRequest):BuildingResponse{val o=ownedOffice(r.officeId);val code=code(r.code);if(buildings.existsByOffice_IdAndCodeIgnoreCaseAndIsDeletedFalse(o.id!!,code))duplicate("Building code");val e=buildings.save(Building(o,text(r.name,"Building name"),code,r.description?.trim(),r.status));record(o.company.id!!,"Building",e.id,AuditAction.CREATE,null,"code=$code,name=${e.name}");return building(e)}
 @Transactional fun updateBuilding(id:Long,r:BuildingRequest):BuildingResponse{val e=ownedBuilding(id);version(e.version,r.version);val o=ownedOffice(r.officeId);if(o.company.id!=e.office.company.id)cross();val code=code(r.code);if(buildings.existsByOffice_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(o.id!!,code,id))duplicate("Building code");e.office=o;e.name=text(r.name,"Building name");e.code=code;e.description=r.description?.trim();e.status=r.status;return building(buildings.saveAndFlush(e))}
 @Transactional fun createFloor(r:FloorRequest):FloorResponse{val b=ownedBuilding(r.buildingId);val e=floors.save(Floor(b,text(r.name,"Floor name"),r.displayOrder,status=r.status));record(b.office.company.id!!,"Floor",e.id,AuditAction.CREATE,null,"name=${e.name}");return floor(e)}
 @Transactional fun updateFloor(id:Long,r:FloorRequest):FloorResponse{val e=ownedFloor(id);version(e.version,r.version);val b=ownedBuilding(r.buildingId);if(company(b)!=company(e))cross();e.building=b;e.name=text(r.name,"Floor name");e.displayOrder=r.displayOrder;e.status=r.status;return floor(floors.saveAndFlush(e))}
 @Transactional fun createZone(r:ZoneRequest):ZoneResponse{val f=ownedFloor(r.floorId);val c=code(r.code);if(zones.existsByFloor_IdAndCodeIgnoreCaseAndIsDeletedFalse(f.id!!,c))duplicate("Zone code");val e=zones.save(Zone(f,text(r.name,"Zone name"),c,r.colour,r.description?.trim(),r.status));record(company(f),"Zone",e.id,AuditAction.CREATE,null,"code=$c,name=${e.name}");return zone(e)}
 @Transactional fun updateZone(id:Long,r:ZoneRequest):ZoneResponse{val e=ownedZone(id);version(e.version,r.version);val f=ownedFloor(r.floorId);if(company(f)!=company(e.floor))cross();val c=code(r.code);if(zones.existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(f.id!!,c,id))duplicate("Zone code");e.floor=f;e.name=text(r.name,"Zone name");e.code=c;e.colour=r.colour;e.description=r.description?.trim();e.status=r.status;return zone(zones.saveAndFlush(e))}
 @Transactional fun createDesk(r:DeskRequest):DeskResponse{val f=ownedFloor(r.floorId);validateCoordinates(r);val z=r.zoneId?.let{ownedZone(it).also{x->if(x.floor.id!=f.id)cross()}};val c=code(r.code);if(desks.existsByFloor_IdAndCodeIgnoreCaseAndIsDeletedFalse(f.id!!,c))duplicate("Desk code");val e=desks.save(toDesk(f,z,c,r));record(company(f),"Desk",e.id,AuditAction.CREATE,null,"desk=$c,floor=${f.id},x=${e.x},y=${e.y}");return desk(e,null)}
 @Transactional fun updateDesk(id:Long,r:DeskRequest):DeskResponse{val e=ownedDesk(id);version(e.version,r.version);val f=ownedFloor(r.floorId);if(company(f)!=company(e))cross();validateCoordinates(r);val z=r.zoneId?.let{ownedZone(it).also{x->if(x.floor.id!=f.id)cross()}};val c=code(r.code);if(desks.existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(f.id!!,c,id))duplicate("Desk code");apply(e,f,z,c,r);val s=desks.saveAndFlush(e);record(company(e),"Desk",id,AuditAction.UPDATE,null,"desk=$c,x=${s.x},y=${s.y}");return desk(s,current(s))}
 @Transactional fun batch(floorId:Long,r:DeskBatchRequest):List<DeskResponse>{ownedFloor(floorId);val existing=desks.findAllByFloor_IdAndIsDeletedFalseOrderByCode(floorId).associateBy{it.code.uppercase()};r.removedDeskIds.distinct().forEach{archiveDesk(ownedDesk(it).also{d->if(d.floor.id!=floorId)cross()})};r.desks.forEach{if(it.floorId!=floorId)throw BadRequestException("All desks must belong to the selected floor")};r.desks.forEach{req->val match=existing[req.code.trim().uppercase()];if(match==null)createDesk(req) else updateDesk(match.id!!,req.copy(version=match.version))};val saved=desks.findByFloor(floorId,flags(false));val ctx=assignmentContext(saved);return saved.map{desk(it,ctx)}}

 fun map(floorId:Long):FloorMapResponse{val f=ownedFloor(floorId);val list=desks.findByFloor(floorId,flags(false));val ctx=assignmentContext(list);return FloorMapResponse(floor(f),f.planStorageRef?.let{"/workplaces/floors/$floorId/plan"},zones.findAllByFloor_IdAndIsDeletedFalseOrderByName(floorId).map(::zone),list.map{desk(it,ctx)})}
 @Transactional fun uploadPlan(floorId:Long,file:MultipartFile):FloorResponse{val f=ownedFloor(floorId);val old=f.planStorageRef;val saved=storage.store(file);managePlanFiles(saved.reference,old);f.planStorageRef=saved.reference;f.planOriginalName=saved.originalName;f.planMediaType=saved.mediaType;f.planWidth=saved.width;f.planHeight=saved.height;val out=floors.saveAndFlush(f);record(company(f),"Floor",floorId,AuditAction.UPDATE,old?.let{"plan=present"},"plan=${saved.mediaType},name=${saved.originalName}");notifyActor(NotificationType.FLOOR_PLAN_REPLACED,"Floor plan updated for ${f.name}","/workplaces/floors/$floorId/map",floorId);return floor(out)}
 fun plan(floorId:Long):Triple<ByteArray,String,String>{val f=ownedFloor(floorId);val ref=f.planStorageRef?:throw ResourceNotFoundException("Floor plan");return Triple(storage.read(ref),f.planMediaType?:"application/octet-stream",f.planOriginalName?:"floor-plan")}
 @Transactional fun removePlan(floorId:Long){val f=ownedFloor(floorId);val old=f.planStorageRef?:return;f.planStorageRef=null;f.planOriginalName=null;f.planMediaType=null;f.planWidth=null;f.planHeight=null;floors.save(f);storage.delete(old);record(company(f),"Floor",floorId,AuditAction.UPDATE,"plan=present","plan=removed")}

 @Transactional fun assign(r:AssignmentRequest):AssignmentResponse{val d=ownedDesk(r.deskId);val s=staff.findById(r.staffId).orElseThrow{ResourceNotFoundException("Staff",r.staffId)};if(s.isDeleted||s.status!=EntityStatus.ACTIVE)throw BadRequestException("Only active staff can receive a desk assignment");if(s.company.id!=company(d))cross();if(d.isDeleted||d.status!=EntityStatus.ACTIVE||d.mode==DeskMode.UNAVAILABLE||d.availability==DeskAvailability.UNAVAILABLE)throw ConflictException("Desk is unavailable");if(r.effectiveTo!=null&&r.effectiveTo<r.effectiveFrom)throw BadRequestException("Assignment end date cannot precede its start date");val far=r.effectiveTo?:LocalDate.of(9999,12,31);if(r.primaryAssignment&&assignments.overlappingStaff(s.id!!,r.effectiveFrom,far).any{it.primaryAssignment})throw ConflictException("Staff already has an overlapping primary desk assignment");if(d.mode==DeskMode.ASSIGNED&&assignments.overlappingDesk(d.id!!,r.effectiveFrom,far).isNotEmpty())throw ConflictException("Desk already has an overlapping permanent assignment");val a=assignments.save(DeskAssignment(d,s,r.effectiveFrom,r.effectiveTo,r.primaryAssignment,r.reason?.trim(),actor()));syncAvailability(d);record(company(d),"DeskAssignment",a.id,AuditAction.CREATE,null,assignmentAudit(a));notifyActor(NotificationType.DESK_ASSIGNED,"${s.name} assigned to desk ${d.code}","/workplaces/floors/${d.floor.id}/map?deskId=${d.id}",a.id);return assignment(a)}
 @Transactional fun transfer(assignmentId:Long,r:TransferRequest):AssignmentResponse{val old=ownedAssignment(assignmentId);val target=ownedDesk(r.targetDeskId);if(company(old.desk)!=company(target))cross();releaseInternal(old,r.effectiveDate,r.reason?:"Desk transfer");return assign(AssignmentRequest(target.id!!,old.staff.id!!,r.effectiveDate,null,old.primaryAssignment,r.reason?:"Desk transfer")).also{notifyActor(NotificationType.DESK_TRANSFERRED,"${old.staff.name} transferred to desk ${target.code}","/workplaces/floors/${target.floor.id}/map?deskId=${target.id}",it.id)}}
 @Transactional fun release(id:Long,r:ReleaseRequest):AssignmentResponse{val a=ownedAssignment(id);version(a.version,r.version);releaseInternal(a,r.effectiveTo,r.reason?:"Released");notifyActor(NotificationType.DESK_RELEASED,"${a.staff.name} released from desk ${a.desk.code}","/workplaces/floors/${a.desk.floor.id}/map?deskId=${a.desk.id}",a.id);return assignment(a)}
 @Transactional fun releaseForStaff(staffId:Long,date:LocalDate,reason:String){assignments.activeForStaff(staffId,date).forEach{a->releaseInternal(a,date,reason);notify(a.assignedBy,NotificationType.DESK_RELEASED,"${a.staff.name} released from desk ${a.desk.code}: $reason",deskLink(a.desk),a.id)}}
 fun currentForStaff(staffId:Long):AssignmentResponse?=assignments.activeForStaff(staffId,LocalDate.now(clock)).firstOrNull()?.also{scope(company(it.desk))}?.let{assignment(it)}
 fun history(staffId:Long)=assignments.findAllByStaff_IdAndIsDeletedFalseOrderByEffectiveFromDesc(staffId).filter{companyAllowed(company(it.desk))}.map{assignment(it)}
 @Transactional(readOnly=true) fun summary(companyId:Long?):WorkplaceSummary{val cid=scopedCompany(companyId);val date=LocalDate.now(clock);val total=desks.countByFloor_Building_Office_Company_IdAndIsDeletedFalse(cid);val unavailable=desks.countUnavailable(cid,DeskMode.UNAVAILABLE,DeskAvailability.UNAVAILABLE);val assigned=assignments.countActive(cid,date);val assignable=(total-unavailable).coerceAtLeast(0);val activeStaff=staff.countByCompany_IdAndStatusAndIsDeletedFalse(cid,EntityStatus.ACTIVE);val staffWithDesk=assignments.countAssignedStaff(cid,date);return WorkplaceSummary(total,assigned,(assignable-assigned).coerceAtLeast(0),unavailable,(activeStaff-staffWithDesk).coerceAtLeast(0),if(assignable==0L)0.0 else assigned*100.0/assignable)}

 @Transactional fun archive(kind:String,id:Long){when(kind){"offices"->{val e=ownedOffice(id);if(buildings.existsByOffice_IdAndIsDeletedFalse(id))throw ConflictException("Archive buildings before archiving this office");e.markDeleted();offices.save(e)};"buildings"->{val e=ownedBuilding(id);if(floors.existsByBuilding_IdAndIsDeletedFalse(id))throw ConflictException("Archive floors before archiving this building");e.markDeleted();buildings.save(e)};"floors"->{val e=ownedFloor(id);if(desks.findAllByFloor_IdAndIsDeletedFalseOrderByCode(id).any{current(it)!=null})throw ConflictException("Release active desk assignments before archiving this floor");e.markDeleted();floors.save(e)};"zones"->{val e=ownedZone(id);e.markDeleted();zones.save(e)};"desks"->{archiveDesk(ownedDesk(id));return};else->throw BadRequestException("Unsupported workplace resource")};recordFromKind(kind,id,AuditAction.DELETE)}
 @Transactional fun restore(kind:String,id:Long){when(kind){"offices"->offices.findById(id).orElseThrow{ResourceNotFoundException("Office",id)}.also{scope(it.company.id!!);it.restore();offices.save(it)};"buildings"->buildings.findById(id).orElseThrow{ResourceNotFoundException("Building",id)}.also{scope(company(it));it.restore();buildings.save(it)};"floors"->floors.findById(id).orElseThrow{ResourceNotFoundException("Floor",id)}.also{scope(company(it));it.restore();floors.save(it)};"zones"->zones.findById(id).orElseThrow{ResourceNotFoundException("Zone",id)}.also{scope(company(it.floor));it.restore();zones.save(it)};"desks"->desks.findById(id).orElseThrow{ResourceNotFoundException("Desk",id)}.also{scope(company(it));it.restore();desks.save(it)};else->throw BadRequestException("Unsupported workplace resource")};recordFromKind(kind,id,AuditAction.RESTORE)}

 private fun releaseInternal(a:DeskAssignment,date:LocalDate,reason:String){if(date<a.effectiveFrom)throw BadRequestException("Release date cannot precede assignment start date");a.effectiveTo=date;a.releaseReason=reason.trim();a.releasedBy=actor();assignments.save(a);syncAvailability(a.desk);record(company(a.desk),"DeskAssignment",a.id,AuditAction.UPDATE,assignmentAudit(a),"released=${a.effectiveTo},reason=${a.releaseReason}")}
 private fun syncAvailability(d:Desk){d.availability=if(d.mode==DeskMode.UNAVAILABLE)DeskAvailability.UNAVAILABLE else if(current(d)!=null)DeskAvailability.ASSIGNED else DeskAvailability.AVAILABLE;desks.save(d)}
 private fun current(d:Desk)=assignments.activeForDesk(d.id!!,LocalDate.now(clock)).firstOrNull()
 private fun toDesk(f:Floor,z:Zone?,c:String,r:DeskRequest)=Desk(f,z,c,r.displayName?.trim(),r.mode,if(r.mode==DeskMode.UNAVAILABLE)DeskAvailability.UNAVAILABLE else r.availability,r.x,r.y,r.width,r.height,norm(r.rotation),r.capacity,r.telephoneExtension?.trim(),r.accessible,r.equipmentTags?.trim(),r.notes?.trim(),r.status)
 private fun apply(e:Desk,f:Floor,z:Zone?,c:String,r:DeskRequest){e.floor=f;e.zone=z;e.code=c;e.displayName=r.displayName?.trim();e.mode=r.mode;e.availability=if(r.mode==DeskMode.UNAVAILABLE)DeskAvailability.UNAVAILABLE else r.availability;e.x=r.x;e.y=r.y;e.width=r.width;e.height=r.height;e.rotation=norm(r.rotation);e.capacity=r.capacity;e.telephoneExtension=r.telephoneExtension?.trim();e.accessible=r.accessible;e.equipmentTags=r.equipmentTags?.trim();e.notes=r.notes?.trim();e.status=r.status}
 private fun validateCoordinates(r:DeskRequest){if(r.x+r.width>BigDecimal("100")||r.y+r.height>BigDecimal("100"))throw BadRequestException("Desk dimensions must remain inside the floor map")}
 private fun ownedOffice(id:Long)=offices.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Office",id)}.also{scope(it.company.id!!)}
 private fun ownedBuilding(id:Long)=buildings.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Building",id)}.also{scope(company(it))}
 private fun ownedFloor(id:Long)=floors.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Floor",id)}.also{scope(company(it))}
 private fun ownedZone(id:Long)=zones.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Zone",id)}.also{scope(company(it.floor))}
 private fun ownedDesk(id:Long)=desks.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Desk",id)}.also{scope(company(it))}
 private fun ownedAssignment(id:Long)=assignments.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Desk assignment",id)}.also{scope(company(it.desk))}
 private fun scope(companyId:Long){val p=SecurityUtils.currentPrincipal();if(p.role!=Role.SUPER_ADMIN&&p.companyId!=companyId)throw ForbiddenException()};private fun companyAllowed(id:Long)=SecurityUtils.currentPrincipal().let{it.role==Role.SUPER_ADMIN||it.companyId==id};private fun scopedCompany(requested:Long?):Long{val p=SecurityUtils.currentPrincipal();return if(p.role==Role.SUPER_ADMIN)requested?:throw BadRequestException("companyId is required")else p.companyId?:throw ForbiddenException()}
 private fun actor()=users.findById(SecurityUtils.currentUserId()).orElseThrow{ResourceNotFoundException("Authenticated user")};private fun record(companyId:Long,type:String,id:Long?,action:AuditAction,before:String?,after:String?)=audit.record(actor(),action,type,id,companyId,type,before,after);private fun notifyActor(type:NotificationType,message:String,link:String,id:Long?)=notifications.deliver(actor(),type,message,link,"Workplace",id)
 private fun managePlanFiles(newReference:String,oldReference:String?){
  if(!TransactionSynchronizationManager.isSynchronizationActive()){storage.delete(oldReference);return}
  TransactionSynchronizationManager.registerSynchronization(object:TransactionSynchronization{
   override fun afterCommit(){storage.delete(oldReference)}
   override fun afterCompletion(status:Int){if(status!=TransactionSynchronization.STATUS_COMMITTED)storage.delete(newReference)}
  })
 }
 private fun recordFromKind(kind:String,id:Long,action:AuditAction)=record(when(kind){"offices"->offices.findById(id).get().company.id!!;"buildings"->company(buildings.findById(id).get());"floors"->company(floors.findById(id).get());"zones"->company(zones.findById(id).get().floor);else->company(desks.findById(id).get())},kind,id,action,null,"id=$id")
 private fun company(b:Building)=b.office.company.id!!;private fun company(f:Floor)=f.building.office.company.id!!;private fun company(d:Desk)=company(d.floor);private fun company(o:Office)=o.company.id!!
 private fun office(e:Office)=OfficeResponse(e.id!!,e.version,e.company.id!!,e.company.name,e.name,e.code,e.address,e.city,e.country,e.timeZone,e.status,e.isDeleted)
 private fun building(e:Building)=BuildingResponse(e.id!!,e.version,e.office.id!!,e.office.name,e.office.company.id!!,e.name,e.code,e.description,e.status,e.isDeleted)
 // hasPlan reports whether the image can actually be served, not merely whether
 // the row still carries a reference: a plan whose file is gone must not be
 // advertised, or clients request it and get a 404 they cannot act on.
 private fun floor(e:Floor)=FloorResponse(e.id!!,e.version,e.building.id!!,e.building.name,e.building.office.id!!,e.building.office.name,e.building.office.company.id!!,e.building.office.company.name,e.name,e.displayOrder,planAvailable(e),e.planOriginalName,e.planMediaType,e.planWidth,e.planHeight,e.status,e.isDeleted)
 private fun planAvailable(e:Floor):Boolean{
  val ref=e.planStorageRef?:return false
  if(storage.exists(ref))return true
  log.warn("Floor {} references floor plan '{}' but the stored file is missing; serving desks without a plan",e.id,ref)
  return false
 }
 private fun zone(e:Zone)=ZoneResponse(e.id!!,e.version,e.floor.id!!,e.name,e.code,e.colour,e.description,e.status,e.isDeleted)
 private fun desk(e:Desk,a:DeskAssignment?,titles:Map<Long,String>?=null)=DeskResponse(e.id!!,e.version,e.floor.id!!,e.zone?.id,e.zone?.name,e.code,e.displayName,e.mode,if(a!=null)DeskAvailability.ASSIGNED else e.availability,e.x,e.y,e.width,e.height,e.rotation,e.capacity,e.telephoneExtension,e.accessible,e.equipmentTags,e.notes,e.status,e.isDeleted,a?.let{assignment(it,titles)})
 private fun assignment(a:DeskAssignment,titles:Map<Long,String>?=null):AssignmentResponse{val show=namesVisible();val f=a.desk.floor;val title=if(titles!=null)titles[a.staff.id!!]?:a.staff.title else positions.findFirstByStaff_IdAndIsDeletedFalse(a.staff.id!!)?.title?:a.staff.title;return AssignmentResponse(a.id!!,a.version,a.desk.id!!,a.desk.code,f.id!!,f.name,f.building.name,f.building.office.name,a.desk.zone?.name,a.desk.telephoneExtension,a.staff.id!!,if(show)a.staff.name else null,if(show)a.staff.employeeCode else null,a.staff.department?.id,a.staff.department?.name,title,a.effectiveFrom,a.effectiveTo,a.primaryAssignment,a.assignmentReason,a.releaseReason)}
 private fun assignmentAudit(a:DeskAssignment)="staff=${a.staff.id},desk=${a.desk.id},floor=${a.desk.floor.id},from=${a.effectiveFrom},to=${a.effectiveTo},reason=${a.assignmentReason}"
 private fun code(v:String)=text(v,"Code").uppercase();private fun text(v:String,label:String)=v.trim().takeIf{it.isNotEmpty()}?:throw BadRequestException("$label is required");private fun validZone(v:String)=runCatching{ZoneId.of(v.trim()).id}.getOrElse{throw BadRequestException("Invalid time zone")};private fun norm(v:Int)=((v%360)+360)%360;private fun version(actual:Long,sent:Long?){if(sent==null||actual!=sent)throw ConflictException("Record changed since it was opened. Reload and try again")};private fun duplicate(label:String):Nothing=throw ConflictException("$label already exists in this location");private fun cross():Nothing=throw BadRequestException("Referenced records must belong to the same company")

 // ---- batched read helpers ----------------------------------------------------------------
 // `flags` turns the include-archived toggle into the set of is_deleted values a query may return.
 private fun flags(includeDeleted:Boolean)=if(includeDeleted)listOf(false,true) else listOf(false)
 /** Runs the all-company query for a super admin and the company-scoped query for everyone else. */
 private fun <T> scopedList(includeDeleted:Boolean,all:(Collection<Boolean>)->List<T>,scoped:(Long,Collection<Boolean>)->List<T>):List<T>{val f=flags(includeDeleted);val cid=principalCompany();return if(cid==null)all(f) else scoped(cid,f)}
 private fun principalCompany():Long?{val p=SecurityUtils.currentPrincipal();return if(p.role==Role.SUPER_ADMIN)null else p.companyId?:throw ForbiddenException()}
 /** Active assignments and position titles for a set of desks, resolved in two queries rather than per desk. */
 private fun assignmentContext(list:List<Desk>):MapContext{val ids=list.mapNotNull{it.id};if(ids.isEmpty())return MapContext(emptyMap(),emptyMap());val active=assignments.activeForDesks(ids,LocalDate.now(clock));return MapContext(active.associateBy{it.desk.id!!},positionTitles(active.mapNotNull{it.staff.id}))}
 private fun positionTitles(staffIds:List<Long>):Map<Long,String>{val ids=staffIds.distinct();if(ids.isEmpty())return emptyMap();return positions.findAllByStaff_IdInAndIsDeletedFalse(ids).mapNotNull{p->p.staff?.id?.let{it to p.title}}.toMap()}
 private fun desk(e:Desk,ctx:MapContext)=desk(e,ctx.byDesk[e.id],ctx.titles)

 // ---- single-record reads -----------------------------------------------------------------
 fun getOffice(id:Long)=office(ownedOffice(id))
 fun getBuilding(id:Long)=building(ownedBuilding(id))
 fun getFloor(id:Long)=floor(ownedFloor(id))
 fun getZone(id:Long)=zone(ownedZone(id))
 fun getDesk(id:Long)=ownedDesk(id).let{desk(it,current(it))}

 /**
  * Floor-scoped search over desk code, staff name, employee code, department, position, zone and
  * telephone extension. Matching runs on the server so a viewer never needs the full staff list.
  */
 fun searchFloor(floorId:Long,query:String):List<WorkplaceSearchResult>{
  ownedFloor(floorId);val q=query.trim().lowercase();if(q.isEmpty())return emptyList()
  val list=desks.findByFloor(floorId,flags(false));val ctx=assignmentContext(list)
  return list.mapNotNull{d->
   val a=ctx.byDesk[d.id];val title=a?.staff?.id?.let{ctx.titles[it]}?:a?.staff?.title
   val named=namesVisible()
   val fields=listOfNotNull(
    "desk" to d.code,d.displayName?.let{"desk" to it},d.zone?.name?.let{"zone" to it},d.telephoneExtension?.let{"extension" to it},
    a?.staff?.name?.takeIf{named}?.let{"staff" to it},a?.staff?.employeeCode?.takeIf{named}?.let{"employee code" to it},
    a?.staff?.department?.name?.let{"department" to it},title?.let{"position" to it})
   fields.firstOrNull{it.second.lowercase().contains(q)}?.let{hit->
    WorkplaceSearchResult(d.id!!,d.code,d.floor.id!!,d.zone?.name,a?.staff?.id,a?.staff?.name?.takeIf{named},a?.staff?.employeeCode?.takeIf{named},a?.staff?.department?.name,title,d.telephoneExtension,d.availability,hit.first)}
  }
 }
 private fun namesVisible()=!hideNames||SecurityUtils.currentPrincipal().role in setOf(Role.SUPER_ADMIN,Role.COMPANY_ADMIN,Role.MANAGER)
 private fun archiveDesk(e:Desk){if(current(e)!=null)throw ConflictException("Release the active assignment before archiving this desk");e.markDeleted();desks.save(e);record(company(e),"desks",e.id,AuditAction.DELETE,null,"desk=${e.code}")}

 // ---- scheduled assignment lifecycle -------------------------------------------------------
 /**
  * Raises the activation notice on the day an assignment starts and the expiry notice on the day a
  * dated assignment ends. Delivery is de-duplicated by the notification service, so a repeated run
  * on the same day is harmless.
  */
 @Scheduled(cron="\${oms.workplace.assignment-notice-cron:0 5 6 * * *}")
 @Transactional fun raiseAssignmentNotices(){
  val today=LocalDate.now(clock)
  assignments.startingOn(today).forEach{notify(it.assignedBy,NotificationType.WORKPLACE_ASSIGNMENT_ACTIVATED,"${it.staff.name} is now seated at desk ${it.desk.code}",deskLink(it.desk),it.id)}
  assignments.endingOn(today).forEach{notify(it.assignedBy,NotificationType.WORKPLACE_ASSIGNMENT_EXPIRING,"Desk ${it.desk.code} assignment for ${it.staff.name} ends today",deskLink(it.desk),it.id)}
 }
 private fun deskLink(d:Desk)="/workplaces/floors/${d.floor.id}/map?deskId=${d.id}"
 private fun notify(recipient:User,type:NotificationType,message:String,link:String,id:Long?)=notifications.deliver(recipient,type,message,link,"Workplace",id)

}
