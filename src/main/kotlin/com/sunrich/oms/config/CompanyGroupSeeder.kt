package com.sunrich.oms.config

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.CompanyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order

/**
 * Establishes the real Sunrich group structure: the holding company
 * "Sunrich Companies" plus its sister concerns, all linked through
 * [Company.parentCompany].
 *
 * Idempotent and data-preserving. Legacy demo company names are renamed to the
 * corresponding real group company so their IDs, users, staff, departments and
 * other relationships remain intact. No company is deleted. Any remaining
 * pre-existing company without a parent is adopted by the holding company so
 * the group always has exactly one root.
 */
@Configuration
class CompanyGroupSeeder {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Order(1)
    fun seedCompanyGroup(companies: CompanyRepository): ApplicationRunner = ApplicationRunner {
        val parent = companies.findFirstByNameIgnoreCaseAndIsDeletedFalse(GROUP_PARENT_NAME)
            ?: findLegacy(companies, GROUP_PARENT_NAME)?.apply {
                name = GROUP_PARENT_NAME
                parentCompany = null
            }?.let(companies::save)
            ?: companies.save(
                Company(
                    name = GROUP_PARENT_NAME,
                    regNumber = "SRG-000",
                    headOffice = "Colombo, Sri Lanka",
                    status = EntityStatus.ACTIVE,
                    parentCompany = null
                )
            ).also { log.info("Seeded group holding company: {}", it.name) }

        // The holding company must never sit under anything else.
        if (parent.parentCompany != null) {
            parent.parentCompany = null
            companies.save(parent)
        }

        SISTER_CONCERNS.forEachIndexed { index, name ->
            val existing = companies.findFirstByNameIgnoreCaseAndIsDeletedFalse(name)
                ?: findLegacy(companies, name)
            if (existing == null) {
                companies.save(
                    Company(
                        name = name,
                        regNumber = "SRG-%03d".format(index + 1),
                        status = EntityStatus.ACTIVE,
                        parentCompany = parent
                    )
                )
                log.info("Seeded sister concern: {}", name)
            } else {
                val changed = existing.name != name || existing.parentCompany?.id != parent.id
                if (changed) {
                    existing.name = name
                    existing.parentCompany = parent
                    companies.save(existing)
                    log.info("Mapped existing company '{}' into {}", name, GROUP_PARENT_NAME)
                }
            }
        }

        // Adopt any other stray root so the group keeps a single top company.
        companies.findAllByParentCompanyIsNullAndIsDeletedFalse()
            .filter { it.id != parent.id }
            .forEach {
                it.parentCompany = parent
                companies.save(it)
                log.info("Adopted orphan company '{}' into {}", it.name, GROUP_PARENT_NAME)
            }
    }

    private fun findLegacy(companies: CompanyRepository, canonicalName: String): Company? =
        LEGACY_NAMES[canonicalName].orEmpty().asSequence()
            .mapNotNull(companies::findFirstByNameIgnoreCaseAndIsDeletedFalse)
            .firstOrNull()

    companion object {
        const val GROUP_PARENT_NAME = "Sunrich Companies"

        /** Names used by the original demo dataset, mapped without changing IDs. */
        val LEGACY_NAMES = mapOf(
            GROUP_PARENT_NAME to listOf("Sunrich Global Enterprises"),
            "Sunrich Logistics" to listOf("Sunrich Logistics & Supply Chain"),
            "Sunrich Technology" to listOf("Sunrich Technologies")
        )

        /** Ordered as published by the group. */
        val SISTER_CONCERNS = listOf(
            "Atlantic Global Shipping",
            "Admiral Shipping",
            "Sunrich Shipchandlers",
            "Sunrich Logistics",
            "Skyway Marketing",
            "Sunrich Ship Management",
            "Sunrich Impex",
            "Sunrich Ship Building",
            "Sunrich Chartering",
            "Sunrich Properties and Investment",
            "Sunrich Rice",
            "Sunrich Travels",
            "Sunrich Safaris",
            "Sunrich 360°",
            "Skyway Global Trading",
            "Sunrich Villas",
            "Sunrich Meraki",
            "Sunrich Tiles",
            "Sunrich Technology",
            "Sunrich Foundation"
        )
    }
}
