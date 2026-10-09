/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Morphe Manager's simple mode applies every patch whose `use` is true, so a patch outside it is
 * one most people never see. People kept asking for features that already existed because they
 * sat outside it. Every switch-gated patch is in now, with its switch off until the reader turns it
 * on, and a patch kept out needs a reason written here.
 */
class DefaultSelectionPolicyTest {
    private val keptOut = mapOf(
        "Change version code" to "Raises the version code for good: going back means uninstalling, " +
            "and every later build needs it too.",
        "Open developer options" to "A developer tool for overriding Instagram's server flags, where a " +
            "wrong flag breaks parts of the app.",
        "Pure black dark mode" to "Rewrites Instagram's colors when you patch, with no switch to turn it off.",
        "View DM photos and videos anonymously" to "A test feature until the sender's side is checked " +
            "with two accounts.",
    )

    private fun shippedPatches() = run {
        val catalog = File("../patches-list.json").takeIf { it.isFile } ?: File("patches-list.json")
        assertTrue("could not find the patch list from ${File(".").absolutePath}", catalog.isFile)
        JsonParser.parseString(catalog.readText()).asJsonObject.getAsJsonArray("patches")
            .map { it.asJsonObject }
    }

    @Test
    fun `every patch outside the default selection has a written reason`() {
        assertTrue("a reason can't be blank", keptOut.values.none { it.isBlank() })
        val outside = shippedPatches().filter { !it.get("use").asBoolean }.map { it.get("name").asString }
        assertEquals(
            "a patch left out of the default selection needs a reason here, and one put back needs its entry removed",
            keptOut.keys.sorted(),
            outside.sorted(),
        )
    }

    @Test
    fun `the declarations agree with the catalog`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        assertTrue("could not find the patch sources from ${File(".").absolutePath}", root.isDirectory)
        val header = Regex(
            """(?:bytecodePatch|resourcePatch|rawResourcePatch)\(\s*\n(?:\s*//[^\n]*\n)*\s*name = "([^"]+)",(.*?)\n\) \{""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val declared = root.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> header.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] } }
            .toList()
        assertEquals(
            "every shipped patch is found among the declarations",
            shippedPatches().map { it.get("name").asString }.sorted(),
            declared.map { it.first }.sorted(),
        )
        val outside = declared.filter { (_, rest) -> "default = false," in rest }.map { it.first }
        assertEquals("regenerate the catalog: :patches:generatePatchesList", keptOut.keys.sorted(), outside.sorted())
    }

    /**
     * A description is what Manager shows beside the checkbox, so it's where a reader learns that a
     * patch they got by default does nothing until they turn it on, and where.
     */
    @Test
    fun `every description says where its switch is or that there isn't one`() {
        val unexplained = shippedPatches().filter { patch ->
            val description = patch.get("description").asString
            "HushGram settings" !in description && "no switch" !in description
        }.map { it.get("name").asString }
        assertEquals("a description that doesn't say where its switch is", emptyList<String>(), unexplained.sorted())
    }
}
