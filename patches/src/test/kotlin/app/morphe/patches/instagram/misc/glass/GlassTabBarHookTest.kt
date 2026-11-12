/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.glass

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.reels.fullscreen.STATUS_BAR_CHANGED
import app.morphe.patches.instagram.reels.fullscreen.barHeightStores
import app.morphe.patches.instagram.reels.fullscreen.findBarHeights
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Glass tab bar writes no hook of its own: it switches its status method on, and the extension finds
 * the tab bar, the screens under it, the Reels viewer and the sheets by the ids Instagram's layout
 * gives them. So each build has to declare every one of those ids, or the pill quietly never shows.
 */
class GlassTabBarHookTest {
    private val glass = "$EXTENSION_PACKAGE/misc/GlassTabBar;"
    private val ids = listOf(
        "TAB_BAR", "TAB_BAR_SHADOW", "CONTENT", "SWIPE_PAGER", "REELS_ROOT", "TOUCH_STRIP", "SCRUBBER", "REEL_INFO",
        "REEL_MEDIA", "REEL_FOOTERS", "SEARCH_TAB", "REELS_TAB", "SHEET_HOLDER", "SHEET_DIMMER", "STORY_ROOT",
    ).map { ExtensionDex.stringConstant(glass, it) }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val bundle = declaredBundles().first()
        val context = PatchContexts.of(FixtureDex.classes(bundle, cameraTypes(bundle)).values + ExtensionDex.classDef(SETTINGS_STATUS))
        glassTabBarPatch.execute(context)
        assertEquals(1, context.status("glassTabBar"))
        assertEquals("another patch's status is still off", 0, context.status("savedOnProfile"))
    }

    @Test
    fun theCameraHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(GLASS_TAB_BAR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CAMERA_NAV_BAR_HEIGHT_HOOK is not in the extension: $declared", CAMERA_NAV_BAR_HEIGHT_HOOK.substringAfter("->") in declared)
    }

    /**
     * #646: in each build, the camera's 9:16 layout built from a Context asks the extension about the
     * navigation bar's height it read from where Instagram keeps it, right before it builds itself from
     * that height, and is otherwise as it was.
     */
    @Test
    fun eachBuildGivesTheCameraTheNavBar() {
        for (bundle in declaredBundles() + Fixtures.otherBuilds()) {
            val label = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val context = PatchContexts.of(FixtureDex.classes(bundle, cameraTypes(bundle)).values)
            val nav = context.findBarHeights().barHeightStores()[1].field
            val method = context.mutableClassDefBy(CAMERA_LAYOUT).methods.single {
                it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;", "Z", "Z")
            }
            val before = method.code()
            val read = before.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC && (it.reference() as MethodReference).definingClass == nav.definingClass &&
                    (it.reference() as MethodReference).let { call -> call.parameterTypes.isEmpty() && call.returnType == "I" } &&
                    context.classDefBy(nav.definingClass).methods.single { m -> m.name == (it.reference() as MethodReference).name && m.parameterTypes.isEmpty() }
                        .code().any { held -> held.opcode == Opcode.SGET && held.reference().toString() == nav.toString() }
            }
            assertTrue("$label: the camera's layout reads the navigation bar's height", read >= 0)
            val height = (before[read + 1] as OneRegisterInstruction).registerA
            val build = before.indexOfFirst { (it.reference() as? MethodReference)?.let { call -> call.definingClass == CAMERA_LAYOUT && call.name == "<init>" } == true }
            val native = NeutralNativePath(method)

            context.giveTheCameraTheNavBar(nav)

            val code = method.code()
            val what = "$label: the camera's layout"
            assertEquals("$what: two instructions added", before.size + 2, code.size)
            assertEquals("$what asks right before it builds itself", CAMERA_NAV_BAR_HEIGHT_HOOK, code[build].reference().toString())
            assertEquals("$what: an invoke-static", Opcode.INVOKE_STATIC, code[build].opcode)
            assertEquals("$what: on the height it read", listOf(height), code[build].arguments())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[build + 1].opcode)
            assertEquals("$what: in the height's register", height, (code[build + 1] as OneRegisterInstruction).registerA)
            assertTrue("$what: handed on", height in code[build + 2].arguments())
            assertEquals("$what: to its own constructor", before[build].reference().toString(), code[build + 2].reference().toString())
            native.assertPreserved(what, method, setOf(build, build + 1))
        }
    }

    /** A layout whose constructor jumps to where it builds itself refuses rather than put the ask where the jump skips it. */
    @Test
    fun aJumpToTheBuildRefuses() {
        val bundle = declaredBundles().first()
        val context = PatchContexts.of(FixtureDex.classes(bundle, cameraTypes(bundle)).values)
        val nav = context.findBarHeights().barHeightStores()[1].field
        val method = context.mutableClassDefBy(CAMERA_LAYOUT).methods.single {
            it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;", "Z", "Z")
        }
        val build = method.code().indexOfFirst { (it.reference() as? MethodReference)?.let { call -> call.definingClass == CAMERA_LAYOUT && call.name == "<init>" } == true }
        method.addInstructionsWithLabels(build, "goto :build", ExternalLabel("build", method.getInstruction(build)))
        val failure = assertThrows(PatchException::class.java) { context.giveTheCameraTheNavBar(nav) }
        assertTrue(failure.message, failure.message!!.contains("jumps"))
    }

    private fun declaredBundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        assertEquals("a declared build has no fixture", versions, versions.filter { v -> bundles.any { it.name.contains("-$v-") } }.toSet())
        return bundles
    }

    /** The insets listener's classes, the class keeping the heights and the camera's 9:16 layout. */
    private fun cameraTypes(bundle: File): Set<String> {
        val types = FixtureDex.classesHolding(bundle, STATUS_BAR_CHANGED).map { it.type }.toSet()
        val holder = PatchContexts.of(FixtureDex.classes(bundle, types).values).findBarHeights().barHeightStores()[1].field.definingClass
        return types + holder + CAMERA_LAYOUT
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.arguments(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /** An extension without the status method fails at patch time, naming it, instead of going in and doing nothing. */
    @Test
    fun anExtensionWithoutTheStatusMethodRefuses() {
        val status = ExtensionDex.classDef(SETTINGS_STATUS)
        val without = ImmutableClassDef(
            status.type, status.accessFlags, status.superclass, status.interfaces, status.sourceFile, status.annotations,
            status.fields, status.methods.filterNot { it.name == "glassTabBar" },
        )
        val failure = assertThrows(PatchException::class.java) { glassTabBarPatch.execute(PatchContexts.of(listOf(without))) }
        assertTrue(failure.message, failure.message!!.contains("glassTabBar()"))
    }

    @Test
    fun eachDeclaredBuildDeclaresTheIds() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                declares(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildDeclaresTheIdsToo() {
        for (base in Fixtures.otherBuilds()) declares(base, base.parentFile.name)
    }

    /** The search tells a real id from one the build doesn't declare. */
    @Test
    fun anIdTheBuildDoesntDeclareIsMissing() {
        val wrong = ids.first() + "_hushgram"
        val table = ViewIdFixture.resourceTable(Fixtures.otherBuilds().first())
        assertEquals(listOf(wrong), ViewIdFixture.missing(table, listOf(ids.first(), wrong)))
    }

    /** Only a whole id counts: the start of tab_bar_shadow is no id, though every byte of it is in the table. */
    @Test
    fun aPieceOfALongerIdIsMissing() {
        val shadow = ExtensionDex.stringConstant(glass, "TAB_BAR_SHADOW")
        val piece = shadow.dropLast(1)
        val table = ViewIdFixture.resourceTable(Fixtures.otherBuilds().first())
        assertEquals(listOf(piece), ViewIdFixture.missing(table, listOf(shadow, piece)))
    }

    private fun declares(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(), ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), ids))
    }

    private fun BytecodePatchContext.status(name: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral
}
