package com.sunrich.oms.record

import org.springframework.data.jpa.repository.JpaRepository

interface AppRecordRepository : JpaRepository<AppRecord, Long> {
    fun findAllByCollectionNameOrderByIdAsc(collectionName: String): List<AppRecord>
    fun findByIdAndCollectionName(id: Long, collectionName: String): AppRecord?
}
