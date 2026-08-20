package com.sunrich.oms.workplace.detection

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface DetectedObjectRepository : JpaRepository<DetectedObject, Long> {
    @EntityGraph(attributePaths = ["floor"])
    fun findAllByFloor_IdAndIsDeletedFalseOrderByIdAsc(floorId: Long): List<DetectedObject>
    fun countByFloor_IdAndIsDeletedFalse(floorId: Long): Long
    fun existsByFloor_IdAndTypeAndIsDeletedFalse(floorId: Long, type: DetectedObjectType): Boolean
}
