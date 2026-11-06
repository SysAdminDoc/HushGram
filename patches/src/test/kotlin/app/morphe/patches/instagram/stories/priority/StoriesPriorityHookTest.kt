/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.priority

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.stories.seen.StoryTrayOrderHookTest
import app.morphe.patches.instagram.stories.seen.TRAY_ORDER
import app.morphe.patches.instagram.stories.seen.TRAY_RESPONDED
import app.morphe.patches.instagram.stories.seen.findStoryRings
import app.morphe.patches.instagram.stories.seen.findStoryTray
import app.morphe.patches.instagram.stories.seen.orderStoryTray
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stories priority (#451) hands the lists the reel store gives out for the tray to StoryPriority, after
 * View stories anonymously' sort whichever of the two patches is applied first, and reads each reel's
 * username through the User model's getter.
 */
class StoriesPriorityHookTest {
    private val trays = StoryTrayOrderHookTest()

    @Test
    fun theHookAndTheStubsAreInTheExtension() {
        val methods = ExtensionDex.classDef(STORY_PRIORITY).methods
        val hook = methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == PRIORITY_ORDER.substringAfter("->") }
        assertTrue("the hook isn't public and static", AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags))
        val seen = methods.single { it.name == "seen" }
        assertEquals(listOf(OBJECT, OBJECT), seen.parameterTypes.map(Any::toString))
        assertEquals("I", seen.returnType)
        val username = methods.single { it.name == "username" }
        assertEquals(listOf(OBJECT), username.parameterTypes.map(Any::toString))
        assertEquals(STRING, username.returnType)
    }

    /** On each build the tray's two lists are hooked alone, in either order of the two patches, and the stubs read the reel and its user. */
    @Test
    fun eachDeclaredBuildPutsTheListedAccountsFirst() = with(trays) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var builds = 0
        for (version in versions) {
            val bundles = Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } +
                Fixtures.otherBuilds().filter { it.parentFile.name.contains("-$version-") }
            for (bundle in bundles) {
                val classes = extended(bundle)
                for (variant in listOf("priority alone", "sort then priority", "priority then sort")) {
                    val at = "${bundle.name} $variant"
                    val context = PatchContexts.of(classes)
                    val sort = variant != "priority alone"
                    if (variant == "sort then priority") context.sort()
                    val tray = try { context.priority() } catch (e: PatchException) { throw AssertionError("$at: ${e.message}", e) }
                    if (variant == "priority then sort") context.sort()

                    val store = context.mutableClassDefBy(tray.store)
                    for (site in tray.sites) {
                        val code = store.methods.single {
                            it.name == site.lister && it.parameterTypes.map(Any::toString) == site.parameters
                        }.code().filter { it.opcode != Opcode.NOP }
                        val returning = code.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
                        // The monitor-exit right before the return: 454's lister lets go of the store earlier on another path too.
                        val exit = code.subList(0, returning).indexOfLast { it.opcode == Opcode.MONITOR_EXIT }
                        val expected = (if (sort) listOf(TRAY_ORDER) else emptyList()) + PRIORITY_ORDER
                        val hooks = code.subList(exit + 1, returning).chunked(2)
                        assertEquals("$at ${site.lister}: the hooks between the monitor-exit and the return",
                            expected, hooks.map { it[0].referenceText() })
                        assertTrue("$at ${site.lister}: each answer is taken back",
                            hooks.all { it[1].opcode == Opcode.MOVE_RESULT_OBJECT })
                    }
                    assertEquals("$at: priority calls", 2, context.calls(PRIORITY_ORDER))
                    assertEquals("$at: only the tray's own hook hears of a tray response", if (variant == "priority alone") 0 else 1, context.calls(TRAY_RESPONDED))
                    assertEquals("$at: sort calls", if (sort) 2 else 0, context.calls(TRAY_ORDER))

                    val references = context.mutableClassDefBy(STORY_PRIORITY).methods.filter { it.name == "seen" || it.name == "username" }
                        .flatMap { it.code() }.mapNotNull { it.referenceText() }
                    for (required in listOf(tray.identity.kind.toString(), tray.identity.owner.toString(), tray.identity.reelId.toString(), "USER_REEL")) {
                        assertTrue("$at: the bridges omit $required", required in references)
                    }
                    assertTrue("$at: the username isn't read from the User model",
                        references.any { it.startsWith("$USER->") && it.endsWith("()$STRING") })
                    assertEquals("$at: native seen is read once", 1,
                        references.count { it == "${tray.reelClass}->${tray.seenCheck}($USER_SESSION)Z" })
                }
                builds++
            }
        }
        assertEquals("a retained build has no fixture", Fixtures.BUILDS, builds)
    }

    /** A build whose username can't be found is refused before anything is written. */
    @Test
    fun aBuildWithoutTheUserModelFails() = with(trays) {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val classes = extended(bundle)
        val trimmed = PatchContexts.of(classes.filter { it.type != USER })
        assertThrows(PatchException::class.java) {
            val rings = trimmed.findStoryRings()
            trimmed.findStoryTray(rings.reelClass, STORY_PRIORITY, PRIORITY_ORDER)
        }
        assertEquals("a refusal must not hook a list", 0, trimmed.calls(PRIORITY_ORDER))
    }

    private fun StoryTrayOrderHookTest.extended(bundle: File): List<ClassDef> =
        (trayClasses(bundle) + ExtensionDex.classDef(STORY_PRIORITY) + userModel(bundle)).map { ImmutableClassDef.of(it) }.distinctBy { it.type }

    private fun userModel(bundle: File): List<ClassDef> =
        app.morphe.patches.instagram.FixtureDex.classes(bundle, setOf(USER)).values.toList()

    private fun BytecodePatchContext.sort() {
        val rings = findStoryRings()
        orderStoryTray(findStoryTray(rings.reelClass))
    }

    private fun BytecodePatchContext.priority(): app.morphe.patches.instagram.stories.seen.StoryTrayTargets {
        val rings = findStoryRings()
        val tray = findStoryTray(rings.reelClass, STORY_PRIORITY, PRIORITY_ORDER)
        prioritizeStories(tray, findPriorityUsername(tray))
        return tray
    }

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"
        const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
    }
}
