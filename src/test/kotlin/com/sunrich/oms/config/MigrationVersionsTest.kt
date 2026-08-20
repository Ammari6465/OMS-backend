package com.sunrich.oms.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Flyway refuses to start when two migrations share a version, which takes the
 * whole application down at boot rather than at build time. Two people adding a
 * migration on the same day is enough to trigger it, so the collision is caught
 * here instead of by a failed deployment.
 */
class MigrationVersionsTest {

    private val migrations = File("src/main/resources/db/migration")

    @Test
    fun `every migration has a unique version`() {
        val byVersion = scripts().groupBy { version(it) }
        val duplicates = byVersion.filterValues { it.size > 1 }

        assertThat(duplicates)
            .withFailMessage(
                "Flyway will refuse to start. Renumber one of: %s",
                duplicates.values.flatten().joinToString()
            )
            .isEmpty()
    }

    @Test
    fun `migration filenames follow the versioned naming convention`() {
        val malformed = scripts().filterNot { it.matches(Regex("^V\\d{8}__[a-z0-9_]+\\.sql$")) }

        assertThat(malformed)
            .withFailMessage("Expected V<yyyyMMdd>__snake_case.sql, found: %s", malformed.joinToString())
            .isEmpty()
    }

    private fun scripts() = migrations.listFiles { file -> file.extension == "sql" }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()
        .also { assertThat(it).isNotEmpty() }

    private fun version(name: String) = name.substringAfter('V').substringBefore("__")
}
