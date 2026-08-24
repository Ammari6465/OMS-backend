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
 private val offices:OfficeRepository,private val buildings:BuildingRepository,private val floors:FloorRepository,private val zones:ZoneRepository,private val spaces:WorkplaceSpaceRepository,private val desks:DeskRepository,private val assignments:DeskAssignmentRepository,
 private val companies:CompanyRepository,private val staff:StaffRepository,private val positions:PositionRepository,private val departments:DepartmentRepository,private val users:UserRepository,
 private val detectedObjects:com.sunrich.oms.workplace.detection.DetectedObjectRepository,
 private val bookings:DeskBookingRepository,
 private val storage:FloorPlanStorage,private val audit:AuditTrailService,private val notifications:NotificationDeliveryService,private val clock:Clock,
 @Value("\${oms.workplace.hide-staff-names:false}")private val hideNames:Boolean
){
 private val log=org.slf4j.LoggerFactory.getLogger(javaClass)
 /** Open-ended sentinel for "no end date" when testing assignment overlap windows. */
 private val FAR:LocalDate=LocalDate.of(9999,12,31)
 fun listOffices(companyId:Long?,includeDeleted:Boolean=false):List<OfficeResponse>{val f=flags(includeDeleted);val ids=principalCompanies()?:companyId?.let(::sharedWith);val list=if(ids==null)offices.findAllScoped(f) else offices.findCompanyScoped(ids,f)
  // Viewing "as" a company shows its own offices plus the shared premises it
  // inherits from the holding company above it.
  val requested=companyId?.let(::sharedWith);return list.filter{requested==null||it.company.id in requested}.map(::office)}
 fun listBuildings(officeId:Long?,includeDeleted:Boolean=false)=scopedList(includeDeleted,buildings::findAllScoped,buildings::findCompanyScoped).filter{officeId==null||it.office.id==officeId}.map(::building)
 fun listFloors(buildingId:Long?,includeDeleted:Boolean=false)=scopedList(includeDeleted,floors::findAllScoped,floors::findCompanyScoped).filter{buildingId==null||it.building.id==buildingId}.map(::floor)
 fun listZones(floorId:Long?,includeDeleted:Boolean=false)=(if(floorId!=null){readableFloor(floorId);zones.findByFloor(floorId,flags(includeDeleted))}else scopedList(includeDeleted,zones::findAllScoped,zones::findCompanyScoped)).map(::zone)
 fun listDesks(floorId:Long?,includeDeleted:Boolean=false):List<DeskResponse>{val list=if(floorId!=null){readableFloor(floorId);desks.findByFloor(floorId,flags(includeDeleted))}else scopedList(includeDeleted,desks::findAllScoped,desks::findCompanyScoped);val ctx=assignmentContext(list);return list.map{desk(it,ctx)}}

 @Transactional fun createOffice(r:OfficeRequest):OfficeResponse{scope(r.companyId);val c=companies.findById(r.companyId).orElseThrow{ResourceNotFoundException("Company",r.companyId)};val code=code(r.code);if(offices.existsByCompany_IdAndCodeIgnoreCaseAndIsDeletedFalse(c.id!!,code))duplicate("Office code");val e=offices.save(Office(c,text(r.name,"Office name"),code,r.address?.trim(),r.city?.trim(),r.country?.trim(),validZone(r.timeZone),r.status));record(e.company.id!!,"Office",e.id,AuditAction.CREATE,null,"code=$code,name=${e.name}");return office(e)}
 @Transactional fun updateOffice(id:Long,r:OfficeRequest):OfficeResponse{val e=ownedOffice(id);version(e.version,r.version);scope(r.companyId);if(e.company.id!=r.companyId)throw BadRequestException("Office company cannot be changed");val before="code=${e.code},name=${e.name}";val code=code(r.code);if(offices.existsByCompany_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(r.companyId,code,id))duplicate("Office code");e.name=text(r.name,"Office name");e.code=code;e.address=r.address?.trim();e.city=r.city?.trim();e.country=r.country?.trim();e.timeZone=validZone(r.timeZone);e.status=r.status;val s=offices.saveAndFlush(e);record(r.companyId,"Office",id,AuditAction.UPDATE,before,"code=${s.code},name=${s.name}");return office(s)}
 @Transactional fun createBuilding(r:BuildingRequest):BuildingResponse{val o=ownedOffice(r.officeId);val code=code(r.code);if(buildings.existsByOffice_IdAndCodeIgnoreCaseAndIsDeletedFalse(o.id!!,code))duplicate("Building code");val e=buildings.save(Building(o,text(r.name,"Building name"),code,r.description?.trim(),r.status));record(o.company.id!!,"Building",e.id,AuditAction.CREATE,null,"code=$code,name=${e.name}");return building(e)}
 @Transactional fun updateBuilding(id:Long,r:BuildingRequest):BuildingResponse{val e=ownedBuilding(id);version(e.version,r.version);val o=ownedOffice(r.officeId);if(o.company.id!=e.office.company.id)cross();val code=code(r.code);if(buildings.existsByOffice_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(o.id!!,code,id))duplicate("Building code");e.office=o;e.name=text(r.name,"Building name");e.code=code;e.description=r.description?.trim();e.status=r.status;return building(buildings.saveAndFlush(e))}
 @Transactional fun createFloor(r:FloorRequest):FloorResponse{val b=ownedBuilding(r.buildingId);val e=floors.save(Floor(b,text(r.name,"Floor name"),r.displayOrder,status=r.status));record(b.office.company.id!!,"Floor",e.id,AuditAction.CREATE,null,"name=${e.name}");return floor(e)}
 @Transactional fun updateFloor(id:Long,r:FloorRequest):FloorResponse{val e=ownedFloor(id);version(e.version,r.version);val b=ownedBuilding(r.buildingId);if(company(b)!=company(e))cross();e.building=b;e.name=text(r.name,"Floor name");e.displayOrder=r.displayOrder;e.status=r.status;return floor(floors.saveAndFlush(e))}
 @Transactional fun createZone(r:ZoneRequest):ZoneResponse{val f=ownedFloor(r.floorId);val c=code(r.code);if(zones.existsByFloor_IdAndCodeIgnoreCaseAndIsDeletedFalse(f.id!!,c))duplicate("Zone code");val e=zones.save(Zone(f,text(r.name,"Zone name"),c,r.colour,r.description?.trim(),r.status));record(company(f),"Zone",e.id,AuditAction.CREATE,null,"code=$c,name=${e.name}");bumpRevision(f);return zone(e)}
 @Transactional fun updateZone(id:Long,r:ZoneRequest):ZoneResponse{val e=ownedZone(id);version(e.version,r.version);val f=ownedFloor(r.floorId);if(company(f)!=company(e.floor))cross();val c=code(r.code);if(zones.existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(f.id!!,c,id))duplicate("Zone code");e.floor=f;e.name=text(r.name,"Zone name");e.code=c;e.colour=r.colour;e.description=r.description?.trim();e.status=r.status;val s=zones.saveAndFlush(e);bumpRevision(f);return zone(s)}
 @Transactional fun createDesk(r:DeskRequest):DeskResponse{val f=ownedFloor(r.floorId);validateCoordinates(r);val z=r.zoneId?.let{ownedZone(it).also{x->if(x.floor.id!=f.id)cross()}};val sp=resolveSpace(r,f);val c=code(r.code);val prior=desks.findFirstByFloor_IdAndCodeIgnoreCase(f.id!!,c);if(prior!=null&&!prior.isDeleted)duplicate("Desk code");val e=if(prior!=null){prior.restore();apply(prior,f,z,sp,c,r);desks.saveAndFlush(prior)}else desks.save(toDesk(f,z,sp,c,r));record(company(f),"Desk",e.id,if(prior==null)AuditAction.CREATE else AuditAction.RESTORE,null,"desk=$c,floor=${f.id},x=${e.x},y=${e.y}");bumpRevision(f);return desk(e,null)}
 @Transactional fun updateDesk(id:Long,r:DeskRequest):DeskResponse{val e=ownedDesk(id);version(e.version,r.version);val f=ownedFloor(r.floorId);if(company(f)!=company(e))cross();validateCoordinates(r);val z=r.zoneId?.let{ownedZone(it).also{x->if(x.floor.id!=f.id)cross()}};val sp=resolveSpace(r,f);val c=code(r.code);if(desks.existsByFloor_IdAndCodeIgnoreCaseAndIdNot(f.id!!,c,id))duplicate("Desk code");apply(e,f,z,sp,c,r);val s=desks.saveAndFlush(e);record(company(e),"Desk",id,AuditAction.UPDATE,null,"desk=$c,x=${s.x},y=${s.y}");bumpRevision(f);return desk(s,current(s))}
 /**
  * Batch floor-map save. Existing desks are matched by **id**, never by code, so
  * renaming a desk updates the same row instead of leaving the original behind
  * and creating a second one. The entire request is validated before any row is
  * written, and the whole operation is one transaction, so a rejection anywhere
  * leaves the map exactly as it was.
  *
  * Concurrency: the request carries the floor-map revision the client started
  * from. If the stored revision has moved on, another administrator changed the
  * map first and the save is rejected with 409 (see [requireMapRevision]) rather
  * than silently overwriting their work. The revision is then bumped through the
  * floor row's optimistic-lock version, so two saves that both slip past the
  * precondition check still cannot both commit.
  */
 @Transactional fun batch(floorId:Long,r:DeskBatchRequest):List<DeskResponse>{
  val f=ownedFloor(floorId)
  requireMapRevision(f,r.mapRevision)
  val removed=r.removedDeskIds.distinct()

  // ---- request-shape validation: no database writes happen until this all passes ----
  val ids=r.desks.mapNotNull{it.id}
  if(ids.size!=ids.toSet().size)throw BadRequestException("The same desk id appears more than once in the request")
  val idSet=ids.toSet()
  removed.firstOrNull{it in idSet}?.let{throw BadRequestException("Desk $it cannot be updated and removed in the same request")}
  val seenCodes=HashSet<String>()
  r.desks.forEach{if(!seenCodes.add(it.code.trim().uppercase()))throw ConflictException("Desk code ${it.code.trim()} appears more than once in the request")}
  r.desks.forEach{if(it.floorId!=floorId)throw BadRequestException("All desks must belong to the selected floor")}

  // ---- resolve every referenced record and validate it, still without writing ----
  val removeTargets=removed.map{id->
   desks.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Desk",id)}.also{
    if(it.floor.id!=floorId)throw BadRequestException("Desk $id belongs to another floor and cannot be modified here")
    if(hasActiveOrFutureAssignment(it.id!!))throw ConflictException("Release the active or scheduled assignment on desk ${it.code} before removing it")}}
  val plans=ArrayList<BatchPlan>()
  r.desks.forEach{item->
   validateCoordinates(item.toDeskRequest())
   val zone=item.zoneId?.let{zid->zones.findById(zid).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Zone",zid)}.also{if(it.floor.id!=floorId)cross()}}
   val space=item.spaceId?.let{sid->spaces.findById(sid).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Space",sid)}.also{if(it.floor.id!=floorId)cross()}}
   val c=code(item.code)
   if(item.id!=null){
    val e=desks.findById(item.id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Desk",item.id)}
    if(e.floor.id!=floorId)throw BadRequestException("Desk ${item.id} belongs to another floor and cannot be modified here")
    if(item.version==null||item.version!=e.version)throw ConflictException("Desk ${e.code} changed since it was opened. Reload the map and try again")
    if(desks.existsByFloor_IdAndCodeIgnoreCaseAndIdNot(floorId,c,item.id))throw ConflictException("Desk code ${item.code.trim()} already exists on this floor")
    plans+=BatchPlan(e,item,zone,space)
   }else plans+=BatchPlan(null,item,zone,space)
  }

  // ---- apply: validation has passed, so these writes will not need to be undone ----
  removeTargets.forEach{archiveDesk(it)}
  plans.forEach{p->
   if(p.existing!=null){apply(p.existing,f,p.zone,p.space,code(p.item.code),p.item.toDeskRequest());val s=desks.saveAndFlush(p.existing);record(company(p.existing),"Desk",p.existing.id,AuditAction.UPDATE,null,"desk=${s.code},x=${s.x},y=${s.y}")}
   else createDeskInBatch(f,p.zone,p.space,p.item)}
  bumpRevision(f)
  val saved=desks.findByFloor(floorId,flags(false));val ctx=assignmentContext(saved);return saved.map{desk(it,ctx)}}

 private data class BatchPlan(val existing:Desk?,val item:DeskBatchItem,val zone:Zone?,val space:WorkplaceSpace?)

 /** Creates one desk during a batch, restoring a soft-deleted row that already holds the code (mirrors [createDesk]). */
 private fun createDeskInBatch(f:Floor,z:Zone?,sp:WorkplaceSpace?,item:DeskBatchItem){
  val c=code(item.code);val req=item.toDeskRequest();val prior=desks.findFirstByFloor_IdAndCodeIgnoreCase(f.id!!,c)
  if(prior!=null&&!prior.isDeleted)duplicate("Desk code")
  val e=if(prior!=null){prior.restore();apply(prior,f,z,sp,c,req);desks.saveAndFlush(prior)}else desks.save(toDesk(f,z,sp,c,req))
  record(company(f),"Desk",e.id,if(prior==null)AuditAction.CREATE else AuditAction.RESTORE,null,"desk=$c,floor=${f.id},x=${e.x},y=${e.y}")}

 /** Rejects a save built on a stale view of the floor map. */
 private fun requireMapRevision(f:Floor,sent:Long?){
  if(sent==null)throw BadRequestException("mapRevision is required for a floor map save")
  if(sent!=f.mapRevision)throw ConflictException("The floor map changed since you opened it. Reload the latest map and reapply your changes.")}
 /** Advances the floor-map revision. The floor row's @Version makes two concurrent bumps mutually exclusive: the loser fails with 409. */
 private fun bumpRevision(f:Floor){f.mapRevision+=1;floors.saveAndFlush(f)}
 /** Seam so the detection module can bump the map revision when it changes detected objects. */
 @Transactional fun bumpFloorMapRevision(floorId:Long){bumpRevision(floors.findById(floorId).orElseThrow{ResourceNotFoundException("Floor",floorId)})}

/** Desk codes already taken on a floor, for callers that must avoid a clash rather than recover from one: a duplicate raised mid-transaction cannot be caught and walked past. */
 // Include soft-deleted rows: the unique index uq_workplace_{desk,zone}_floor_code
 // spans (floor_id, code) with no is_deleted term, so a deleted record still
 // holds its code. A caller reserving codes to avoid a clash must see those too,
 // or it reuses a code the database still rejects — which, mid-transaction,
 // poisons the whole batch.
 fun deskCodes(floorId:Long):Set<String> = desks.findByFloor(floorId,flags(true)).map{it.code.uppercase()}.toSet()
 fun zoneCodes(floorId:Long):Set<String> = zones.findByFloor(floorId,flags(true)).map{it.code.uppercase()}.toSet()
 fun spaceCodes(floorId:Long):Set<String> = spaces.findByFloor(floorId,flags(true)).map{it.code.uppercase()}.toSet()
 fun map(floorId:Long):FloorMapResponse{val f=readableFloor(floorId);val list=desks.findByFloor(floorId,flags(false));val ctx=assignmentContext(list)
  val spaceList=spaces.findByFloor(floorId,flags(false));val counts=deskCountsBySpace(floorId);val deptNames=departmentNames(spaceList)
  return FloorMapResponse(floor(f),f.planStorageRef?.let{"/workplaces/floors/$floorId/plan"},zones.findAllByFloor_IdAndIsDeletedFalseOrderByName(floorId).map(::zone),spaceList.map{space(it,counts[it.id]?:0,deptNames)},list.map{desk(it,ctx)})}
 @Transactional fun uploadPlan(floorId:Long,file:MultipartFile):FloorResponse{val f=ownedFloor(floorId);val old=f.planStorageRef;val saved=storage.store(file);managePlanFiles(saved.reference,old);f.planStorageRef=saved.reference;f.planOriginalName=saved.originalName;f.planMediaType=saved.mediaType;f.planWidth=saved.width;f.planHeight=saved.height;f.mapRevision+=1;val out=floors.saveAndFlush(f);record(company(f),"Floor",floorId,AuditAction.UPDATE,old?.let{"plan=present"},"plan=${saved.mediaType},name=${saved.originalName}");notifyActor(NotificationType.FLOOR_PLAN_REPLACED,"Floor plan updated for ${f.name}","/workplaces/floors/$floorId/map",floorId);return floor(out)}
 fun plan(floorId:Long):Triple<ByteArray,String,String>{val f=readableFloor(floorId);val ref=f.planStorageRef?:throw ResourceNotFoundException("Floor plan");return Triple(storage.read(ref),f.planMediaType?:"application/octet-stream",f.planOriginalName?:"floor-plan")}
 @Transactional fun removePlan(floorId:Long){val f=ownedFloor(floorId);val old=f.planStorageRef?:return;f.planStorageRef=null;f.planOriginalName=null;f.planMediaType=null;f.planWidth=null;f.planHeight=null;f.mapRevision+=1;floors.save(f);storage.delete(old);record(company(f),"Floor",floorId,AuditAction.UPDATE,"plan=present","plan=removed")}

 /** Empties every editable workplace record on a floor but retains its uploaded plan image. */
 @Transactional fun clearFloorContents(floorId:Long):FloorContentsClearResult{
  val f=ownedFloor(floorId)
  val floorDesks=desks.findAllByFloor_IdAndIsDeletedFalseOrderByCode(floorId)
  val deskIds=floorDesks.mapNotNull{it.id}
  val floorAssignments=if(deskIds.isEmpty())emptyList() else assignments.findAllByDesk_IdInAndIsDeletedFalse(deskIds)
  floorAssignments.forEach{it.markDeleted()};assignments.saveAll(floorAssignments)
  floorDesks.forEach{it.markDeleted()};desks.saveAll(floorDesks)
  val floorZones=zones.findAllByFloor_IdAndIsDeletedFalseOrderByName(floorId)
  floorZones.forEach{it.markDeleted()};zones.saveAll(floorZones)
  bumpRevision(f)
  val result=FloorContentsClearResult(floorDesks.size,floorZones.size,floorAssignments.size)
  record(company(f),"Floor",floorId,AuditAction.DELETE,null,"mapContentsCleared:desks=${result.desks},zones=${result.zones},assignments=${result.assignments}")
  return result
 }

 @Transactional fun assign(r:AssignmentRequest):AssignmentResponse{val d=ownedDesk(r.deskId);val s=staff.findById(r.staffId).orElseThrow{ResourceNotFoundException("Staff",r.staffId)};if(s.isDeleted||s.status!=EntityStatus.ACTIVE)throw BadRequestException("Only active staff can receive a desk assignment");if(s.company.id!=company(d))cross();if(d.isDeleted||d.status!=EntityStatus.ACTIVE||d.mode==DeskMode.UNAVAILABLE||d.availability==DeskAvailability.UNAVAILABLE)throw ConflictException("Desk is unavailable");if(r.effectiveTo!=null&&r.effectiveTo<r.effectiveFrom)throw BadRequestException("Assignment end date cannot precede its start date");val far=r.effectiveTo?:LocalDate.of(9999,12,31);if(r.primaryAssignment&&assignments.overlappingStaff(s.id!!,r.effectiveFrom,far).any{it.primaryAssignment})throw ConflictException("Staff already has an overlapping primary desk assignment");if(d.mode==DeskMode.ASSIGNED&&assignments.overlappingDesk(d.id!!,r.effectiveFrom,far).isNotEmpty())throw ConflictException("Desk already has an overlapping permanent assignment");val a=assignments.save(DeskAssignment(d,s,r.effectiveFrom,r.effectiveTo,r.primaryAssignment,r.reason?.trim(),actor()));syncAvailability(d);record(company(d),"DeskAssignment",a.id,AuditAction.CREATE,null,assignmentAudit(a));notifyActor(NotificationType.DESK_ASSIGNED,"${s.name} assigned to desk ${d.code}","/workplaces/floors/${d.floor.id}/map?deskId=${d.id}",a.id);return assignment(a)}
 @Transactional fun transfer(assignmentId:Long,r:TransferRequest):AssignmentResponse{val old=ownedAssignment(assignmentId);val target=ownedDesk(r.targetDeskId);if(company(old.desk)!=company(target))cross();releaseInternal(old,r.effectiveDate,r.reason?:"Desk transfer");return assign(AssignmentRequest(target.id!!,old.staff.id!!,r.effectiveDate,null,old.primaryAssignment,r.reason?:"Desk transfer")).also{notifyActor(NotificationType.DESK_TRANSFERRED,"${old.staff.name} transferred to desk ${target.code}","/workplaces/floors/${target.floor.id}/map?deskId=${target.id}",it.id)}}
 @Transactional fun release(id:Long,r:ReleaseRequest):AssignmentResponse{val a=ownedAssignment(id);version(a.version,r.version);releaseInternal(a,r.effectiveTo,r.reason?:"Released");notifyActor(NotificationType.DESK_RELEASED,"${a.staff.name} released from desk ${a.desk.code}","/workplaces/floors/${a.desk.floor.id}/map?deskId=${a.desk.id}",a.id);return assignment(a)}
 @Transactional fun releaseForStaff(staffId:Long,date:LocalDate,reason:String){assignments.activeForStaff(staffId,date).forEach{a->releaseInternal(a,date,reason);notify(a.assignedBy,NotificationType.DESK_RELEASED,"${a.staff.name} released from desk ${a.desk.code}: $reason",deskLink(a.desk),a.id)}}
 fun currentForStaff(staffId:Long):AssignmentResponse?=assignments.activeForStaff(staffId,LocalDate.now(clock)).firstOrNull()?.also{scope(company(it.desk))}?.let{assignment(it)}
 fun history(staffId:Long)=assignments.findAllByStaff_IdAndIsDeletedFalseOrderByEffectiveFromDesc(staffId).filter{companyAllowed(company(it.desk))}.map{assignment(it)}
 @Transactional(readOnly=true) fun summary(companyId:Long?):WorkplaceSummary{// Counted over the same companies the map shows, so the tiles cannot report
  // zero desks while shared premises are on screen. Staff, though, are counted
  // for the selected company alone: people belong to one company even when the
  // building they sit in is shared.
  val cid=scopedCompany(companyId);val ids=sharedWith(cid);val date=LocalDate.now(clock);val total=desks.countByFloor_Building_Office_Company_IdInAndIsDeletedFalse(ids);val unavailable=desks.countUnavailable(ids,DeskMode.UNAVAILABLE,DeskAvailability.UNAVAILABLE);val assigned=assignments.countActive(ids,date);val assignable=(total-unavailable).coerceAtLeast(0);val activeStaff=staff.countByCompany_IdAndStatusAndIsDeletedFalse(cid,EntityStatus.ACTIVE);
  // Both halves of "staff without a desk" must count the same population, or
  // people seated in shared premises would subtract from another company's
  // headcount and drive the figure negative.
  val staffWithDesk=assignments.countAssignedStaff(setOf(cid),date);return WorkplaceSummary(total,assigned,(assignable-assigned).coerceAtLeast(0),unavailable,(activeStaff-staffWithDesk).coerceAtLeast(0),if(assignable==0L)0.0 else assigned*100.0/assignable)}

 /**
  * Soft-deletes a workplace record, enforcing the hierarchy so an archived
  * parent never leaves a visible active child behind:
  * Company → Office → Building → Floor → Space/Zone → Desk.
  */
 @Transactional fun archive(kind:String,id:Long){when(kind){
   "offices"->{val e=ownedOffice(id);if(buildings.existsByOffice_IdAndIsDeletedFalse(id))throw ConflictException("Archive the buildings in this office before archiving the office");e.markDeleted();offices.save(e)}
   "buildings"->{val e=ownedBuilding(id);if(floors.existsByBuilding_IdAndIsDeletedFalse(id))throw ConflictException("Archive the floors in this building before archiving the building");e.markDeleted();buildings.save(e)}
   "floors"->{val e=ownedFloor(id);if(zones.existsByFloor_IdAndIsDeletedFalse(id))throw ConflictException("Archive the zones on this floor before archiving the floor");if(spaces.existsByFloor_IdAndIsDeletedFalse(id))throw ConflictException("Archive the spaces on this floor before archiving the floor");if(desks.existsByFloor_IdAndIsDeletedFalse(id))throw ConflictException("Archive the desks on this floor before archiving the floor");e.markDeleted();floors.save(e)}
   "zones"->{archiveZone(ownedZone(id));return}
   "spaces"->{archiveSpace(ownedSpace(id));return}
   "desks"->{archiveDesk(ownedDesk(id));return}
   else->throw BadRequestException("Unsupported workplace resource")};recordFromKind(kind,id,AuditAction.DELETE)}
 /**
  * Restores a soft-deleted record, but never under an archived parent — a
  * restored desk whose floor is still archived would be an invisible orphan.
  */
 @Transactional fun restore(kind:String,id:Long){when(kind){
   "offices"->offices.findById(id).orElseThrow{ResourceNotFoundException("Office",id)}.also{scope(it.company.id!!);it.restore();offices.save(it)}
   "buildings"->buildings.findById(id).orElseThrow{ResourceNotFoundException("Building",id)}.also{scope(company(it));if(it.office.isDeleted)throw ConflictException("Restore the parent office before restoring this building");it.restore();buildings.save(it)}
   "floors"->floors.findById(id).orElseThrow{ResourceNotFoundException("Floor",id)}.also{scope(company(it));if(it.building.isDeleted)throw ConflictException("Restore the parent building before restoring this floor");it.restore();floors.save(it)}
   "zones"->zones.findById(id).orElseThrow{ResourceNotFoundException("Zone",id)}.also{scope(company(it.floor));if(it.floor.isDeleted)throw ConflictException("Restore the parent floor before restoring this zone");it.restore();zones.save(it)}
   "spaces"->spaces.findById(id).orElseThrow{ResourceNotFoundException("Space",id)}.also{scope(company(it.floor));if(it.floor.isDeleted)throw ConflictException("Restore the parent floor before restoring this space");it.restore();spaces.save(it)}
   "desks"->desks.findById(id).orElseThrow{ResourceNotFoundException("Desk",id)}.also{scope(company(it));if(it.floor.isDeleted)throw ConflictException("Restore the parent floor before restoring this desk");it.restore();desks.save(it)}
   else->throw BadRequestException("Unsupported workplace resource")};recordFromKind(kind,id,AuditAction.RESTORE)}

 // ---- workplace spaces (typed rooms with permanent geometry) -------------------------------
 fun listSpaces(floorId:Long?,includeDeleted:Boolean=false):List<SpaceResponse>{
  val list=if(floorId!=null){readableFloor(floorId);spaces.findByFloor(floorId,flags(includeDeleted))}else scopedList(includeDeleted,spaces::findAllScoped,spaces::findCompanyScoped)
  val deptNames=departmentNames(list);val countsByFloor=list.mapNotNull{it.floor.id}.distinct().associateWith{deskCountsBySpace(it)}
  return list.map{space(it,countsByFloor[it.floor.id]?.get(it.id)?:0,deptNames)}}
 fun getSpace(id:Long):SpaceResponse{val e=ownedSpace(id);return space(e,deskCountsBySpace(e.floor.id!!)[e.id]?:0,departmentNames(listOf(e)))}
 @Transactional fun createSpace(r:SpaceRequest):SpaceResponse{
  val f=ownedFloor(r.floorId);val z=r.zoneId?.let{ownedZone(it).also{x->if(x.floor.id!=f.id)cross()}};validateDepartment(r.departmentId,company(f))
  val c=code(r.code);val prior=spaces.findFirstByFloor_IdAndCodeIgnoreCase(f.id!!,c);if(prior!=null&&!prior.isDeleted)duplicate("Space code")
  val e=if(prior!=null){prior.restore();applySpace(prior,f,z,c,r);spaces.saveAndFlush(prior)}else spaces.save(toSpace(f,z,c,r))
  record(company(f),"WorkplaceSpace",e.id,if(prior==null)AuditAction.CREATE else AuditAction.RESTORE,null,"space=$c,type=${e.type},name=${e.name}");bumpRevision(f)
  return space(e,0,departmentNames(listOf(e)))}
 @Transactional fun updateSpace(id:Long,r:SpaceRequest):SpaceResponse{
  val e=ownedSpace(id);version(e.version,r.version);val f=ownedFloor(r.floorId);if(company(f)!=company(e.floor))cross()
  val z=r.zoneId?.let{ownedZone(it).also{x->if(x.floor.id!=f.id)cross()}};validateDepartment(r.departmentId,company(f))
  val c=code(r.code);if(spaces.existsByFloor_IdAndCodeIgnoreCaseAndIdNotAndIsDeletedFalse(f.id!!,c,id))duplicate("Space code")
  val before="code=${e.code},name=${e.name},type=${e.type}";applySpace(e,f,z,c,r);val s=spaces.saveAndFlush(e)
  record(company(e.floor),"WorkplaceSpace",id,AuditAction.UPDATE,before,"code=${s.code},name=${s.name},type=${s.type}");bumpRevision(f)
  return space(s,deskCountsBySpace(f.id!!)[s.id]?:0,departmentNames(listOf(s)))}
 fun getSpaceEntity(id:Long)=ownedSpace(id)
 private fun ownedSpace(id:Long)=spaces.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Space",id)}.also{scope(company(it.floor))}
 /** Archives a space: its desks stay on the floor but lose the space link, its detection link is cleared, and the map revision advances. */
 private fun archiveSpace(e:WorkplaceSpace){val spaceDesks=desks.findByFloor(e.floor.id!!,flags(false)).filter{it.space?.id==e.id};if(spaceDesks.isNotEmpty()){spaceDesks.forEach{it.space=null};desks.saveAll(spaceDesks)};unlinkPromotedSpace(e);e.markDeleted();spaces.save(e);bumpRevision(e.floor)}
 /** Clears the promotion link on any detected object that produced this space. */
 private fun unlinkPromotedSpace(e:WorkplaceSpace){val links=detectedObjects.findAllBySpaceIdAndIsDeletedFalse(e.id!!);if(links.isEmpty())return;links.forEach{it.spaceId=null};detectedObjects.saveAll(links);record(company(e.floor),"DetectedObject",e.id,AuditAction.UPDATE,"spaceLink=${e.id}","spaceLink=null;objects=${links.size}")}
 /** Public seam so the detection module can create a space when promoting a room. */
 @Transactional fun createSpaceForPromotion(r:SpaceRequest)=createSpace(r)
 private fun toSpace(f:Floor,z:Zone?,c:String,r:SpaceRequest):WorkplaceSpace{val poly=normalisePolygon(r.polygon);val box=boundingBox(poly);return WorkplaceSpace(f,z,r.type,text(r.name,"Space name"),c,poly,box.x,box.y,box.width,box.height,norm(r.rotation),r.capacity,r.colour,r.bookable,r.accessible,r.departmentId,r.amenities?.trim(),r.equipmentTags?.trim(),r.notes?.trim(),r.status)}
 private fun applySpace(e:WorkplaceSpace,f:Floor,z:Zone?,c:String,r:SpaceRequest){e.floor=f;e.zone=z;e.type=r.type;e.name=text(r.name,"Space name");e.code=c;e.rotation=norm(r.rotation);e.capacity=r.capacity;e.colour=r.colour;e.bookable=r.bookable;e.accessible=r.accessible;e.departmentId=r.departmentId;e.amenities=r.amenities?.trim();e.equipmentTags=r.equipmentTags?.trim();e.notes=r.notes?.trim();e.status=r.status;if(r.polygon!=null){val poly=normalisePolygon(r.polygon);val box=boundingBox(poly);e.polygon=poly;e.bboxX=box.x;e.bboxY=box.y;e.bboxWidth=box.width;e.bboxHeight=box.height}}
 private fun validateDepartment(deptId:Long?,companyId:Long){if(deptId==null)return;val d=departments.findById(deptId).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Department",deptId)};if(d.company.id!=companyId)throw BadRequestException("Department must belong to the same company as the space")}
 private fun deskCountsBySpace(floorId:Long):Map<Long,Int> = desks.spaceDeskCounts(floorId).associate{(it[0] as Long) to (it[1] as Long).toInt()}
 private fun departmentNames(list:List<WorkplaceSpace>):Map<Long,String>{val ids=list.mapNotNull{it.departmentId}.distinct();if(ids.isEmpty())return emptyMap();return departments.findAllById(ids).mapNotNull{d->d.id?.let{it to d.name}}.toMap()}
 private fun space(e:WorkplaceSpace,deskCount:Int,deptNames:Map<Long,String>)=SpaceResponse(e.id!!,e.version,e.floor.id!!,e.zone?.id,e.zone?.name,e.type,e.name,e.code,parsePolygon(e.polygon),SpaceBox(e.bboxX,e.bboxY,e.bboxWidth,e.bboxHeight),e.rotation,e.capacity,e.colour,e.bookable,e.accessible,e.departmentId,e.departmentId?.let{deptNames[it]},e.amenities,e.equipmentTags,e.notes,deskCount,e.status,e.isDeleted)
 /** Parses a stored "x,y x,y ..." polygon into points for the client. */
 private fun parsePolygon(raw:String?):List<PlanPoint> = raw?.trim()?.takeIf{it.isNotEmpty()}?.split(Regex("\\s+"))?.mapNotNull{pair->val xy=pair.split(",");if(xy.size==2){val x=xy[0].toDoubleOrNull();val y=xy[1].toDoubleOrNull();if(x!=null&&y!=null)PlanPoint(x,y) else null}else null}?:emptyList()
 /** Validates and normalises a polygon: 'x,y' pairs, each within 0..1, at least three points. */
 private fun normalisePolygon(raw:String?):String?{
  val r=raw?.trim()?.takeIf{it.isNotEmpty()}?:return null
  val pts=r.split(Regex("\\s+")).map{pair->val xy=pair.split(",");if(xy.size!=2)throw BadRequestException("Polygon points must be 'x,y' pairs");val x=xy[0].toDoubleOrNull()?:throw BadRequestException("Polygon has a non-numeric coordinate");val y=xy[1].toDoubleOrNull()?:throw BadRequestException("Polygon has a non-numeric coordinate");if(x<-0.01||x>1.01||y<-0.01||y>1.01)throw BadRequestException("Polygon coordinates must be within 0..1");x.coerceIn(0.0,1.0) to y.coerceIn(0.0,1.0)}
  if(pts.size<3)throw BadRequestException("A space polygon needs at least three points");if(pts.size>512)throw BadRequestException("A space polygon has too many points")
  return pts.joinToString(" "){"${it.first},${it.second}"}}
 private fun boundingBox(poly:String?):SpaceBox{if(poly==null)return SpaceBox(0.0,0.0,0.0,0.0);val pts=poly.split(Regex("\\s+")).map{val xy=it.split(",");xy[0].toDouble() to xy[1].toDouble()};return SpaceBox(pts.minOf{it.first},pts.minOf{it.second},pts.maxOf{it.first}-pts.minOf{it.first},pts.maxOf{it.second}-pts.minOf{it.second})}

 private fun releaseInternal(a:DeskAssignment,date:LocalDate,reason:String){if(date<a.effectiveFrom)throw BadRequestException("Release date cannot precede assignment start date");a.effectiveTo=date;a.releaseReason=reason.trim();a.releasedBy=actor();assignments.save(a);syncAvailability(a.desk);record(company(a.desk),"DeskAssignment",a.id,AuditAction.UPDATE,assignmentAudit(a),"released=${a.effectiveTo},reason=${a.releaseReason}")}
 private fun syncAvailability(d:Desk){d.availability=if(d.mode==DeskMode.UNAVAILABLE)DeskAvailability.UNAVAILABLE else if(current(d)!=null)DeskAvailability.ASSIGNED else DeskAvailability.AVAILABLE;desks.save(d)}
 private fun current(d:Desk)=assignments.activeForDesk(d.id!!,LocalDate.now(clock)).firstOrNull()
 private fun toDesk(f:Floor,z:Zone?,sp:WorkplaceSpace?,c:String,r:DeskRequest)=Desk(f,z,sp,c,r.displayName?.trim(),r.mode,if(r.mode==DeskMode.UNAVAILABLE)DeskAvailability.UNAVAILABLE else r.availability,r.x,r.y,r.width,r.height,norm(r.rotation),r.capacity,r.telephoneExtension?.trim(),r.accessible,r.equipmentTags?.trim(),r.notes?.trim(),r.status)
 private fun apply(e:Desk,f:Floor,z:Zone?,sp:WorkplaceSpace?,c:String,r:DeskRequest){e.floor=f;e.zone=z;e.space=sp;e.code=c;e.displayName=r.displayName?.trim();e.mode=r.mode;e.availability=if(r.mode==DeskMode.UNAVAILABLE)DeskAvailability.UNAVAILABLE else r.availability;e.x=r.x;e.y=r.y;e.width=r.width;e.height=r.height;e.rotation=norm(r.rotation);e.capacity=r.capacity;e.telephoneExtension=r.telephoneExtension?.trim();e.accessible=r.accessible;e.equipmentTags=r.equipmentTags?.trim();e.notes=r.notes?.trim();e.status=r.status}
 /** The space this desk sits in, verified to belong to the same floor. */
 private fun resolveSpace(r:DeskRequest,f:Floor)=r.spaceId?.let{ownedSpace(it).also{x->if(x.floor.id!=f.id)cross()}}
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
 private fun recordFromKind(kind:String,id:Long,action:AuditAction)=record(when(kind){"offices"->offices.findById(id).get().company.id!!;"buildings"->company(buildings.findById(id).get());"floors"->company(floors.findById(id).get());"zones"->company(zones.findById(id).get().floor);"spaces"->company(spaces.findById(id).get().floor);else->company(desks.findById(id).get())},kind,id,action,null,"id=$id")
 private fun company(b:Building)=b.office.company.id!!;private fun company(f:Floor)=f.building.office.company.id!!;private fun company(d:Desk)=company(d.floor);private fun company(o:Office)=o.company.id!!
 private fun office(e:Office)=OfficeResponse(e.id!!,e.version,e.company.id!!,e.company.name,e.name,e.code,e.address,e.city,e.country,e.timeZone,e.status,e.isDeleted)
 private fun building(e:Building)=BuildingResponse(e.id!!,e.version,e.office.id!!,e.office.name,e.office.company.id!!,e.name,e.code,e.description,e.status,e.isDeleted)
 // hasPlan reports whether the image can actually be served, not merely whether
 // the row still carries a reference: a plan whose file is gone must not be
 // advertised, or clients request it and get a 404 they cannot act on.
 private fun floor(e:Floor)=FloorResponse(e.id!!,e.version,e.building.id!!,e.building.name,e.building.office.id!!,e.building.office.name,e.building.office.company.id!!,e.building.office.company.name,e.name,e.displayOrder,planAvailable(e),e.planOriginalName,e.planMediaType,e.planWidth,e.planHeight,e.mapRevision,e.status,e.isDeleted)
 private fun planAvailable(e:Floor):Boolean{
  val ref=e.planStorageRef?:return false
  if(storage.exists(ref))return true
  log.warn("Floor {} references floor plan '{}' but the stored file is missing; serving desks without a plan",e.id,ref)
  return false
 }
 private fun zone(e:Zone)=ZoneResponse(e.id!!,e.version,e.floor.id!!,e.name,e.code,e.colour,e.description,e.status,e.isDeleted)
 private fun desk(e:Desk,a:DeskAssignment?,titles:Map<Long,String>?=null,booking:DeskBooking?=null)=DeskResponse(e.id!!,e.version,e.floor.id!!,e.zone?.id,e.zone?.name,e.space?.id,e.space?.name,e.code,e.displayName,e.mode,derivedAvailability(e,a,booking),e.x,e.y,e.width,e.height,e.rotation,e.capacity,e.telephoneExtension,e.accessible,e.equipmentTags,e.notes,e.status,e.isDeleted,a?.let{assignment(it,titles)})
 /**
  * The one authoritative, date-aware availability for a desk. [active] is the
  * assignment in force on the date the caller asked about and [booking] is the
  * booking holding the desk today; both are computed date-aware upstream, so an
  * expired assignment or a lapsed booking frees the desk with no scheduler
  * involved. Priority: manual UNAVAILABLE, then permanent assignment, then a
  * checked-in booking, then a scheduled booking, else AVAILABLE — a stale
  * ASSIGNED / RESERVED / CHECKED_IN column with nothing behind it reads AVAILABLE.
  */
 private fun derivedAvailability(e:Desk,active:DeskAssignment?,booking:DeskBooking?=null):DeskAvailability = when {
  e.mode==DeskMode.UNAVAILABLE||e.availability==DeskAvailability.UNAVAILABLE->DeskAvailability.UNAVAILABLE
  active!=null->DeskAvailability.ASSIGNED
  booking?.status==BookingStatus.CHECKED_IN->DeskAvailability.CHECKED_IN
  booking!=null->DeskAvailability.RESERVED
  else->DeskAvailability.AVAILABLE}
 private fun assignment(a:DeskAssignment,titles:Map<Long,String>?=null):AssignmentResponse{val show=namesVisible();val f=a.desk.floor;val title=if(titles!=null)titles[a.staff.id!!]?:a.staff.title else positions.findFirstByStaff_IdAndIsDeletedFalse(a.staff.id!!)?.title?:a.staff.title;return AssignmentResponse(a.id!!,a.version,a.desk.id!!,a.desk.code,f.id!!,f.name,f.building.name,f.building.office.name,a.desk.zone?.name,a.desk.telephoneExtension,a.staff.id!!,if(show)a.staff.name else null,if(show)a.staff.employeeCode else null,a.staff.department?.id,a.staff.department?.name,title,a.effectiveFrom,a.effectiveTo,a.primaryAssignment,a.assignmentReason,a.releaseReason)}
 private fun assignmentAudit(a:DeskAssignment)="staff=${a.staff.id},desk=${a.desk.id},floor=${a.desk.floor.id},from=${a.effectiveFrom},to=${a.effectiveTo},reason=${a.assignmentReason}"
 private fun code(v:String)=text(v,"Code").uppercase();private fun text(v:String,label:String)=v.trim().takeIf{it.isNotEmpty()}?:throw BadRequestException("$label is required");private fun validZone(v:String)=runCatching{ZoneId.of(v.trim()).id}.getOrElse{throw BadRequestException("Invalid time zone")}.let{if(it=="Asia/Calcutta")"Asia/Kolkata" else it};private fun norm(v:Int)=((v%360)+360)%360;private fun version(actual:Long,sent:Long?){if(sent==null||actual!=sent)throw ConflictException("Record changed since it was opened. Reload and try again")};private fun duplicate(label:String):Nothing=throw ConflictException("$label already exists in this location");private fun cross():Nothing=throw BadRequestException("Referenced records must belong to the same company")

 // ---- batched read helpers ----------------------------------------------------------------
 // `flags` turns the include-archived toggle into the set of is_deleted values a query may return.
 private fun flags(includeDeleted:Boolean)=if(includeDeleted)listOf(false,true) else listOf(false)
 /** Runs the all-company query for a super admin and the company-scoped query for everyone else. */
 private fun <T> scopedList(includeDeleted:Boolean,all:(Collection<Boolean>)->List<T>,scoped:(Collection<Long>,Collection<Boolean>)->List<T>):List<T>{val f=flags(includeDeleted);val ids=principalCompanies();return if(ids==null)all(f) else scoped(ids,f)}

 /**
  * Companies whose workplace records the caller may read: null for a super
  * admin (everything), otherwise the caller's own company plus the companies
  * above it in the group.
  *
  * Premises registered against the holding company are shared group assets —
  * everyone beneath it works in them, so everyone can see them. Visibility only
  * flows downward: a sister concern's own offices stay private to it and are not
  * exposed to its siblings. Writes remain restricted to the owning company.
  */
 private fun principalCompanies():Collection<Long>?{val p=SecurityUtils.currentPrincipal();if(p.role==Role.SUPER_ADMIN)return null;return sharedWith(p.companyId?:throw ForbiddenException())}
 private fun readScope(companyId:Long){val ids=principalCompanies()?:return;if(companyId !in ids)throw ForbiddenException()}
 private fun readableFloor(id:Long)=floors.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Floor",id)}.also{readScope(company(it))}

 /** The company itself plus every company above it, up to the holding company. */
 private fun sharedWith(companyId:Long):Set<Long>{
  val ids=linkedSetOf(companyId)
  var current=companies.findById(companyId).orElse(null)?.parentCompany
  while(current!=null){val id=current.id?:break;if(!ids.add(id))break;current=current.parentCompany}
  return ids
 }
 /** Active assignments and position titles for a set of desks, resolved in two queries rather than per desk. */
 private fun assignmentContext(list:List<Desk>):MapContext{val ids=list.mapNotNull{it.id};if(ids.isEmpty())return MapContext(emptyMap(),emptyMap());val today=LocalDate.now(clock);val active=assignments.activeForDesks(ids,today)
  // Today's holding bookings per desk, preferring a checked-in one over a merely scheduled one.
  val held=bookings.holdingForDesks(ids,today,BOOKING_HOLDING).groupBy{it.desk.id!!}.mapValues{(_,v)->v.maxByOrNull{if(it.status==BookingStatus.CHECKED_IN)1 else 0}!!}
  return MapContext(active.associateBy{it.desk.id!!},positionTitles(active.mapNotNull{it.staff.id}),held)}
 private val BOOKING_HOLDING=listOf(BookingStatus.SCHEDULED,BookingStatus.CHECKED_IN)
 /** Today's holding booking for a single desk, for single-record reads. */
 private fun currentBooking(d:Desk)=bookings.holdingForDesks(listOf(d.id!!),LocalDate.now(clock),BOOKING_HOLDING).maxByOrNull{if(it.status==BookingStatus.CHECKED_IN)1 else 0}
 private fun positionTitles(staffIds:List<Long>):Map<Long,String>{val ids=staffIds.distinct();if(ids.isEmpty())return emptyMap();return positions.findAllByStaff_IdInAndIsDeletedFalse(ids).mapNotNull{p->p.staff?.id?.let{it to p.title}}.toMap()}
 private fun desk(e:Desk,ctx:MapContext)=desk(e,ctx.byDesk[e.id],ctx.titles,ctx.bookings[e.id])

 // ---- single-record reads -----------------------------------------------------------------
 fun getOffice(id:Long)=office(ownedOffice(id))
 fun getBuilding(id:Long)=building(ownedBuilding(id))
 fun getFloor(id:Long)=floor(readableFloor(id))

 // ---- seams for the floor plan detection module ---------------------------------------------
 /** Floor the caller may view, honouring the group's shared-premises rules. */
 fun requireReadableFloor(id:Long):Floor = readableFloor(id)
 /** Floor the caller may modify: its own company only, never an inherited one. */
 fun requireManageableFloor(id:Long):Floor = ownedFloor(id)
 /** Records a floor-scoped audit entry on behalf of the detection module. */
 fun recordFloorAudit(floorId:Long,action:AuditAction,detail:String){val f=floors.findById(floorId).orElseThrow{ResourceNotFoundException("Floor",floorId)};record(company(f),"Floor",floorId,action,null,detail)}
 fun getZone(id:Long)=zone(ownedZone(id))
 fun getDesk(id:Long)=ownedDesk(id).let{desk(it,current(it),null,currentBooking(it))}

 /**
  * Floor-scoped search over desk code, staff name, employee code, department, position, zone and
  * telephone extension. Matching runs on the server so a viewer never needs the full staff list.
  */
 fun searchFloor(floorId:Long,query:String):List<WorkplaceSearchResult>{
  readableFloor(floorId);val q=query.trim().lowercase();if(q.isEmpty())return emptyList()
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
 private fun archiveDesk(e:Desk){
  if(hasActiveOrFutureAssignment(e.id!!))throw ConflictException("Release the active or scheduled assignment before archiving desk ${e.code}")
  unlinkPromotedDesk(e)
  e.markDeleted();desks.save(e);record(company(e),"desks",e.id,AuditAction.DELETE,null,"desk=${e.code}");bumpRevision(e.floor)}
 /** An assignment that is active today or begins in the future still owns the desk. */
 private fun hasActiveOrFutureAssignment(deskId:Long)=assignments.overlappingDesk(deskId,LocalDate.now(clock),FAR).isNotEmpty()
 /** Clears the promotion link on any detected object that produced this desk, so it becomes a promotion candidate again. Soft delete never fires the FK's ON DELETE SET NULL. */
 private fun unlinkPromotedDesk(e:Desk){val links=detectedObjects.findAllByDeskIdAndIsDeletedFalse(e.id!!);if(links.isEmpty())return;links.forEach{it.deskId=null};detectedObjects.saveAll(links);record(company(e),"DetectedObject",e.id,AuditAction.UPDATE,"deskLink=${e.id}","deskLink=null;objects=${links.size}")}
 /** Archives a zone: its desks stay on the floor but lose the zone label, its detection link is cleared, and the map revision advances. */
 private fun archiveZone(e:Zone){val zoneDesks=desks.findByFloor(e.floor.id!!,flags(false)).filter{it.zone?.id==e.id};if(zoneDesks.isNotEmpty()){zoneDesks.forEach{it.zone=null};desks.saveAll(zoneDesks)};val zoneSpaces=spaces.findAllByZone_IdAndIsDeletedFalse(e.id!!);if(zoneSpaces.isNotEmpty()){zoneSpaces.forEach{it.zone=null};spaces.saveAll(zoneSpaces)};unlinkPromotedZone(e);e.markDeleted();zones.save(e);bumpRevision(e.floor)}
 /** Clears the promotion link on any detected object that produced this zone. */
 private fun unlinkPromotedZone(e:Zone){val links=detectedObjects.findAllByZoneIdAndIsDeletedFalse(e.id!!);if(links.isEmpty())return;links.forEach{it.zoneId=null};detectedObjects.saveAll(links);record(company(e.floor),"DetectedObject",e.id,AuditAction.UPDATE,"zoneLink=${e.id}","zoneLink=null;objects=${links.size}")}

 // ---- scheduled assignment lifecycle -------------------------------------------------------
 /**
  * Raises the activation notice on the day an assignment starts and the expiry notice on the day a
  * dated assignment ends. Delivery is de-duplicated by the notification service, so a repeated run
  * on the same day is harmless.
  */
 @Scheduled(cron="\${oms.workplace.assignment-notice-cron:0 5 6 * * *}")
 @Transactional fun raiseAssignmentNotices(){
  val today=LocalDate.now(clock)
  assignments.startingOn(today).forEach{syncAvailability(it.desk);notify(it.assignedBy,NotificationType.WORKPLACE_ASSIGNMENT_ACTIVATED,"${it.staff.name} is now seated at desk ${it.desk.code}",deskLink(it.desk),it.id)}
  // An assignment with effectiveTo==today is already inactive today (active requires effectiveTo>date),
  // so re-sync the stored availability column and free the desk.
  assignments.endingOn(today).forEach{syncAvailability(it.desk);notify(it.assignedBy,NotificationType.WORKPLACE_ASSIGNMENT_EXPIRING,"Desk ${it.desk.code} assignment for ${it.staff.name} ended",deskLink(it.desk),it.id)}
 }
 private fun deskLink(d:Desk)="/workplaces/floors/${d.floor.id}/map?deskId=${d.id}"
 private fun notify(recipient:User,type:NotificationType,message:String,link:String,id:Long?)=notifications.deliver(recipient,type,message,link,"Workplace",id)

}
