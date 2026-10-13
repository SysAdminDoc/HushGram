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
        "Install next to Instagram" to "Moves the build to a package of its own, so it installs as a second app " +
            "with its own data and sign-in instead of updating the Instagram already on the phone.",
        "Open developer options" to "A developer tool for overriding Instagram's server flags, where a " +
            "wrong flag breaks parts of the app.",
        "Pure black dark mode" to "Rewrites Instagram's colors when you patch, with no switch to turn it off.",
        "Save deleted messages" to "Keeps the text of other people's messages on the phone, which is a " +
            "choice to make, so it stays out of the default selection until picked.",
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

    /**
     * "Starts off" or "Starts on" is a promise about the switch the patch turns on, so it has to be
     * that switch's default. Hide suggested stories and Hide Meta AI said "Starts off" while their
     * switches started on. It's checked against each family's main switch, the first one
     * PatchFamily lists for it.
     */
    @Test
    fun `a description that says how its switch starts matches the switch`() {
        val settings = listOf("../extensions", "extensions")
            .map { File(it, "instagram/src/main/java/app/hushgram/extension/instagram/settings") }
            .firstOrNull { it.isDirectory }
        assertTrue("could not find the extension's settings from ${File(".").absolutePath}", settings != null)
        val defaults = Regex("""public static final \w+Setting (\w+) =\s*new \w+Setting\("[^"]+",\s*(TRUE|FALSE)""")
            .findAll(File(settings, "Settings.java").readText())
            .associate { it.groupValues[1] to (it.groupValues[2] == "TRUE") }
        val masters = Regex("""\n {4}[A-Z_]+\(FamilyNames\.\w+, "(\w+)", (?:null|"[^"]*")((?:,\s*Settings\.\w+)*)""")
            .findAll(File(settings, "PatchFamily.java").readText())
            .mapNotNull { family ->
                Regex("""Settings\.(\w+)""").find(family.groupValues[2])?.let { family.groupValues[1] to it.groupValues[1] }
            }.toMap()
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val name = Regex("""(?:bytecodePatch|resourcePatch|rawResourcePatch)\(\s*\n(?:\s*//[^\n]*\n)*\s*name = "([^"]+)",""")
        val switchesOf = root.walkTopDown().filter { it.extension == "kt" }.mapNotNull { file ->
            val text = file.readText()
            name.findAll(text).map { it.groupValues[1] }.toList().singleOrNull()?.let { only ->
                only to Regex("""enableStatus\("(\w+)"\)""").findAll(text).mapNotNull { masters[it.groupValues[1]] }.toSet()
            }
        }.toMap()
        val checked = mutableMapOf<String, Boolean>()
        val wrong = shippedPatches().mapNotNull { patch ->
            val description = patch.get("description").asString
            val says = when {
                "Starts off." in description -> false
                "Starts on." in description -> true
                else -> return@mapNotNull null
            }
            val patchName = patch.get("name").asString
            val known = switchesOf[patchName].orEmpty().filter { defaults[it] != null }
            if (known.isNotEmpty()) checked[patchName] = says
            val off = known.filter { defaults[it] != says }
            if (off.isEmpty()) null else "$patchName says it starts ${if (says) "on" else "off"}, but $off doesn't"
        }
        assertEquals("a description that disagrees with its switch's default", emptyList<String>(), wrong)
        // A pattern that stops matching would leave nothing to compare and pass on its own.
        assertEquals("Hide suggested stories wasn't checked against its switch", true, checked["Hide suggested stories"])
        assertTrue("no \"Starts off\" description was checked against its switch", checked.containsValue(false))
    }
}
