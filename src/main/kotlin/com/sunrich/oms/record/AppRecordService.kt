package com.sunrich.oms.record

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AppRecordService(
    private val repository: AppRecordRepository,
    private val objectMapper: ObjectMapper
) {
    private val allowedCollections = setOf(
        "companies", "departments", "staff", "positions", "audit", "notifications", "settings"
    )
    private val mapType = object : TypeReference<MutableMap<String, Any?>>() {}

    @Transactional(readOnly = true)
    fun list(collection: String, includeDeleted: Boolean): List<Map<String, Any?>> {
        validateCollection(collection)
        return repository.findAllByCollectionNameOrderByIdAsc(collection)
            .filter { includeDeleted || !it.isDeleted }
            .map(::toResponse)
    }

    @Transactional
    fun create(collection: String, payload: Map<String, Any?>): Map<String, Any?> {
        validateCollection(collection)
        val clean = payload.filterKeys { it !in serverFields }
        return toResponse(repository.save(AppRecord(collection, objectMapper.writeValueAsString(clean))))
    }

    @Transactional
    fun update(collection: String, id: Long, payload: Map<String, Any?>): Map<String, Any?> {
        val record = find(collection, id)
        val existing = readPayload(record)
        existing.putAll(payload.filterKeys { it !in serverFields })
        record.payload = objectMapper.writeValueAsString(existing)
        return toResponse(repository.save(record))
    }

    @Transactional
    fun delete(collection: String, id: Long) {
        val record = find(collection, id)
        record.markDeleted()
        repository.save(record)
    }

    @Transactional
    fun restore(collection: String, id: Long): Map<String, Any?> {
        val record = find(collection, id)
        record.restore()
        return toResponse(repository.save(record))
    }

    private fun find(collection: String, id: Long): AppRecord {
        validateCollection(collection)
        return repository.findByIdAndCollectionName(id, collection)
            ?: throw ResourceNotFoundException("$collection record", id)
    }

    private fun validateCollection(collection: String) {
        if (collection !in allowedCollections) throw BadRequestException("Unsupported data collection: $collection")
    }

    private fun readPayload(record: AppRecord): MutableMap<String, Any?> =
        objectMapper.readValue(record.payload, mapType)

    private fun toResponse(record: AppRecord): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to record.id,
        "isDeleted" to record.isDeleted,
        "createdAt" to record.createdAt,
        "updatedAt" to record.updatedAt,
        "createdBy" to record.createdBy,
        "updatedBy" to record.updatedBy
    ).apply { putAll(readPayload(record)) }

    companion object {
        private val serverFields = setOf(
            "id", "isDeleted", "createdAt", "updatedAt", "createdBy", "updatedBy", "deletedAt", "version"
        )
    }
}
