package com.sunrich.oms.record

import com.sunrich.oms.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table

@Entity
@Table(
    name = "app_records",
    indexes = [Index(name = "idx_app_records_collection", columnList = "collection_name,is_deleted")]
)
class AppRecord(
    @Column(name = "collection_name", nullable = false, length = 50)
    var collectionName: String,

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    var payload: String
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    var id: Long? = null
}
