/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.util

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BuildIdentityMetadataTest {
    private fun inputs() = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("sourceSha256", "0".repeat(64))
        addProperty("catalogSha256", "1".repeat(64))
        addProperty("toolchainSha256", "2".repeat(64))
        addProperty("id", "hg1:cd44116f6ee1923f60ea7b97e6f131740dd97f13e5e65330ba3242d39f94039b")
    }

    @Test fun fixedProductionInputsHaveTheIndependentReferenceIdentity() {
        assertEquals(inputs()["id"].asString, BuildIdentityMetadata.requireInputs(inputs()))
    }

    @Test fun everyInputCategoryAndTheIdentityAreRequired() {
        for (field in listOf("sourceSha256", "catalogSha256", "toolchainSha256", "id", "schemaVersion")) {
            val missing = inputs().apply { remove(field) }
            assertThrows(IllegalArgumentException::class.java) { BuildIdentityMetadata.requireInputs(missing) }
        }
    }

    @Test fun changedCodeCatalogAndToolchainCannotKeepTheOldIdentity() {
        for (field in listOf("sourceSha256", "catalogSha256", "toolchainSha256")) {
            val changed = inputs().apply { addProperty(field, "f".repeat(64)) }
            assertThrows(IllegalArgumentException::class.java) { BuildIdentityMetadata.requireInputs(changed) }
        }
    }

    @Test fun identityMetadataCannotCarryUnrelatedPrivateFields() {
        val changed = inputs().apply { addProperty("account_id", "not a production input") }
        assertThrows(IllegalArgumentException::class.java) { BuildIdentityMetadata.requireInputs(changed) }
    }

    @Test fun unsupportedAndCoercedSchemaNumbersAreRefused() {
        for (value in listOf("\"1\"", "1.0", "1.5", "2", "null", "1e0", "1E0", "10e-1")) {
            val changed = inputs().apply { add("schemaVersion", com.google.gson.JsonParser.parseString(value)) }
            assertThrows(IllegalArgumentException::class.java) { BuildIdentityMetadata.requireInputs(changed) }
        }
    }

    @Test fun missingMalformedOrForeignManifestValuesCannotBeInjected() {
        for (value in listOf(null, "Unknown", "", "0.0.4", "hg1:" + "A".repeat(64), "hg1:" + "0".repeat(63),
            "hg1:" + "0".repeat(64) + " token", "https://example.invalid/account/123")) {
            assertThrows(IllegalArgumentException::class.java) { BuildIdentityMetadata.requireId(value) }
        }
        assertEquals(inputs()["id"].asString, BuildIdentityMetadata.requireId(inputs()["id"].asString))
    }
}
