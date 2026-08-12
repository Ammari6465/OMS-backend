package com.sunrich.oms.common.dto

import org.springframework.data.domain.Page

/**
 * Serializable, framework-agnostic pagination envelope.
 * Wraps a Spring Data [Page] so we never leak JPA types over the wire.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val first: Boolean,
    val last: Boolean,
    val numberOfElements: Int,
    val empty: Boolean
) {
    companion object {
        fun <T> from(page: Page<T>): PageResponse<T> = PageResponse(
            content = page.content,
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            first = page.isFirst,
            last = page.isLast,
            numberOfElements = page.numberOfElements,
            empty = page.isEmpty
        )

        fun <S, T> from(page: Page<S>, mapper: (S) -> T): PageResponse<T> = PageResponse(
            content = page.content.map(mapper),
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            first = page.isFirst,
            last = page.isLast,
            numberOfElements = page.numberOfElements,
            empty = page.isEmpty
        )
    }
}
