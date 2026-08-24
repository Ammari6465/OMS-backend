package com.sunrich.oms.workplace.detection

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface DetectedObjectRepository : JpaRepository<DetectedObject, Long> {
    @EntityGraph(attributePaths = ["floor"])
    fun findAllByFloor_IdAndIsDeletedFalseOrderByIdAsc(floorId: Long): List<DetectedObject>
    fun countByFloor_IdAndIsDeletedFalse(floorId: Long): Long
    fun existsByFloor_IdAndTypeAndIsDeletedFalse(floorId: Long, type: DetectedObjectType): Boolean
    /** Live detections promoted into the given desk. Soft-deleting the desk does not fire the DB ON DELETE SET NULL, so the promotion link is cleared here instead. */
    fun findAllByDeskIdAndIsDeletedFalse(deskId: Long): List<DetectedObject>
    /** Live detections promoted into the given zone. */
    fun findAllByZoneIdAndIsDeletedFalse(zoneId: Long): List<DetectedObject>
    /** Live detections promoted into the given workplace space. */
    fun findAllBySpaceIdAndIsDeletedFalse(spaceId: Long): List<DetectedObject>
}
