package com.sunrich.oms.workplace

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.user.User
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate

enum class DeskMode { ASSIGNED, RESERVABLE, DROP_IN, UNAVAILABLE }
enum class DeskAvailability { AVAILABLE, ASSIGNED, RESERVED, CHECKED_IN, UNAVAILABLE }

@Entity @Table(name="workplace_offices", uniqueConstraints=[UniqueConstraint(name="uq_workplace_office_company_code",columnNames=["company_id","office_code"])], indexes=[Index(name="idx_workplace_office_company",columnList="company_id,is_deleted")])
class Office(
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="company_id",nullable=false) var company:Company,
 @Column(nullable=false,length=200) var name:String,
 @Column(name="office_code",nullable=false,length=50) var code:String,
 @Column(name="address_text",length=500) var address:String?=null,
 @Column(length=100) var city:String?=null,@Column(length=100) var country:String?=null,
 @Column(name="time_zone",nullable=false,length=60) var timeZone:String="UTC",
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) var status:EntityStatus=EntityStatus.ACTIVE
):BaseEntity(){@Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="office_id") var id:Long?=null}

@Entity @Table(name="workplace_buildings",uniqueConstraints=[UniqueConstraint(name="uq_workplace_building_office_code",columnNames=["office_id","building_code"])],indexes=[Index(name="idx_workplace_building_office",columnList="office_id,is_deleted")])
class Building(
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="office_id",nullable=false) var office:Office,
 @Column(nullable=false,length=200) var name:String,@Column(name="building_code",nullable=false,length=50) var code:String,
 @Column(length=1000) var description:String?=null,
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) var status:EntityStatus=EntityStatus.ACTIVE
):BaseEntity(){@Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="building_id") var id:Long?=null}

@Entity @Table(name="workplace_floors",indexes=[Index(name="idx_workplace_floor_building",columnList="building_id,is_deleted")])
class Floor(
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="building_id",nullable=false) var building:Building,
 @Column(nullable=false,length=200) var name:String,@Column(name="display_order",nullable=false) var displayOrder:Int=0,
 @Column(name="plan_storage_ref",length=255) var planStorageRef:String?=null,@Column(name="plan_original_name",length=255) var planOriginalName:String?=null,
 @Column(name="plan_media_type",length=100) var planMediaType:String?=null,@Column(name="plan_width") var planWidth:Int?=null,@Column(name="plan_height") var planHeight:Int?=null,
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) var status:EntityStatus=EntityStatus.ACTIVE
):BaseEntity(){@Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="floor_id") var id:Long?=null}

@Entity @Table(name="workplace_zones",uniqueConstraints=[UniqueConstraint(name="uq_workplace_zone_floor_code",columnNames=["floor_id","zone_code"])],indexes=[Index(name="idx_workplace_zone_floor",columnList="floor_id,is_deleted")])
class Zone(
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="floor_id",nullable=false) var floor:Floor,
 @Column(nullable=false,length=200) var name:String,@Column(name="zone_code",nullable=false,length=50) var code:String,
 @Column(name="display_colour",nullable=false,length=20) var colour:String="#64748b",@Column(length=1000) var description:String?=null,
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) var status:EntityStatus=EntityStatus.ACTIVE
):BaseEntity(){@Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="zone_id") var id:Long?=null}

@Entity @Table(name="workplace_desks",uniqueConstraints=[UniqueConstraint(name="uq_workplace_desk_floor_code",columnNames=["floor_id","desk_code"])],indexes=[Index(name="idx_workplace_desk_floor",columnList="floor_id,is_deleted"),Index(name="idx_workplace_desk_zone",columnList="zone_id")])
class Desk(
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="floor_id",nullable=false) var floor:Floor,
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="zone_id") var zone:Zone?=null,
 @Column(name="desk_code",nullable=false,length=80) var code:String,@Column(name="display_name",length=200) var displayName:String?=null,
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) var mode:DeskMode=DeskMode.ASSIGNED,
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) var availability:DeskAvailability=DeskAvailability.AVAILABLE,
 @Column(nullable=false,precision=7,scale=4) var x:BigDecimal,@Column(nullable=false,precision=7,scale=4) var y:BigDecimal,
 @Column(nullable=false,precision=7,scale=4) var width:BigDecimal=BigDecimal("3"),@Column(nullable=false,precision=7,scale=4) var height:BigDecimal=BigDecimal("2"),
 @Column(nullable=false) var rotation:Int=0,@Column(nullable=false) var capacity:Int=1,
 @Column(name="telephone_extension",length=30) var telephoneExtension:String?=null,@Column(nullable=false) var accessible:Boolean=false,
 @Column(name="equipment_tags",length=1000) var equipmentTags:String?=null,@Column(length=2000) var notes:String?=null,
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) var status:EntityStatus=EntityStatus.ACTIVE
):BaseEntity(){@Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="desk_id") var id:Long?=null}

@Entity @Table(name="workplace_desk_assignments",indexes=[Index(name="idx_workplace_assignment_staff",columnList="staff_id,effective_from,effective_to"),Index(name="idx_workplace_assignment_desk",columnList="desk_id,effective_from,effective_to")])
class DeskAssignment(
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="desk_id",nullable=false) var desk:Desk,
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="staff_id",nullable=false) var staff:Staff,
 @Column(name="effective_from",nullable=false) var effectiveFrom:LocalDate,
 @Column(name="effective_to") var effectiveTo:LocalDate?=null,
 @Column(name="is_primary",nullable=false) var primaryAssignment:Boolean=true,
 @Column(name="assignment_reason",length=1000) var assignmentReason:String?=null,
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="assigned_by",nullable=false) var assignedBy:User
):BaseEntity(){
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="assignment_id") var id:Long?=null
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="released_by") var releasedBy:User?=null
 @Column(name="release_reason",length=1000) var releaseReason:String?=null
}
