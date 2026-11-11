/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.ambient

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ambient light switches its status method on and writes one hook: the call telling the extension each
 * rounded layout's corner radius, first in that layout's setCornerRadius (#630). The extension finds a
 * post's media frame by its class name and a reel's frame by its view id, so each build has to have
 * those and the layout's setter, or the glow quietly never shows or keeps black corners.
 */
class AmbientLightHookTest {
    private val light = "$EXTENSION_PACKAGE/feed/AmbientLight;"
    private val reelFrame = ExtensionDex.stringConstant(light, "REEL_FRAME")
    private val storyFrame = ExtensionDex.stringConstant(light, "STORY_FRAME")
    private val mediaFrame = "L" + ExtensionDex.stringConstant(light, "MEDIA_FRAME").replace('.', '/') + ";"
    private val key = "setCornerRadius(I)V"

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS), layout()!!))
        ambientLightPatch.execute(context)
        assertEquals(1, context.status("ambientLight"))
        assertEquals("another patch's status is still off", 0, context.status("carouselArrows"))
        assertEquals(CORNER_RADIUS, context.setter().code()[0].referenceText())
    }

    /** An extension without the status method fails at patch time, naming it, instead of going in and doing nothing. */
    @Test
    fun anExtensionWithoutTheStatusMethodRefuses() {
        val status = ExtensionDex.classDef(SETTINGS_STATUS)
        val without = ImmutableClassDef(
            status.type, status.accessFlags, status.superclass, status.interfaces, status.sourceFile, status.annotations,
            status.fields, status.methods.filterNot { it.name == "ambientLight" },
        )
        val failure = assertThrows(PatchException::class.java) { ambientLightPatch.execute(PatchContexts.of(listOf(without, layout()!!))) }
        assertTrue(failure.message, failure.message!!.contains("ambientLight()"))
    }

    /** The hook the patch writes is the extension's, public and static. */
    @Test
    fun theRadiusHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(light).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CORNER_RADIUS is not in the extension", CORNER_RADIUS in declared)
    }

    /**
     * The call goes first in the layout's setCornerRadius(I) with the layout and the radius, every stock
     * instruction after it, and the float setter beside it and the rest of the layout stay as they were.
     */
    @Test
    fun theRadiusCallGoesFirstInTheLayoutsSetter() {
        val context = PatchContexts.of(listOf(layout()!!))
        context.assertHooked("stand-in")
    }

    @Test
    fun aLayoutThatDiffersFailsBeforeAnythingChanges() {
        val cases = listOf(
            Layout.NONE to "has no $ROUNDED_LAYOUT",
            Layout.NO_SETTER to "has no setCornerRadius(I)V",
            Layout.JUMPS_TO_START to "jumps back to its start",
        )
        for ((shape, message) in cases) {
            val context = PatchContexts.of(listOfNotNull(layout(shape)))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.hookCornerRadius() }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(message))
            assertEquals("$shape: something was written", 0, context.radiusCalls())
        }
    }

    /** A second run finds its own call and refuses, so the setter never tells the extension twice. */
    @Test
    fun aSecondRunRefuses() {
        val context = PatchContexts.of(listOf(layout()!!))
        context.hookCornerRadius()
        val failure = assertThrows(PatchException::class.java) { context.hookCornerRadius() }
        assertTrue(failure.message, failure.message!!.contains("already calls the extension"))
        assertEquals(1, context.radiusCalls())
    }

    /**
     * In each build of the version the rounded layout has one setCornerRadius(I) of its own, and the call
     * goes first in it with the layout and the radius, every stock instruction of the class kept. Read as
     * the patcher reads an APK, and copied.
     */
    @Test
    fun eachBuildTakesTheRadiusCall() {
        for (apk in builds()) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val found = classesOf(apk, setOf(ROUNDED_LAYOUT))
                assertEquals("$name ($read): the rounded layout", setOf(ROUNDED_LAYOUT), found.keys)
                PatchContexts.of(found.values).assertHooked("$name ($read)")
            }
        }
    }

    /** Every build of the version declares the reel frame's id and has the media frame class the extension looks for by name. */
    @Test
    fun eachBuildHasTheReelFrameAndTheMediaFrame() {
        assertEquals("Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;", mediaFrame)
        for (apk in builds()) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            assertEquals("$name: ids the build doesn't declare", emptyList<String>(), ViewIdFixture.missing(ViewIdFixture.resourceTable(apk), listOf(reelFrame, storyFrame)))
            assertEquals("$name: the media frame", setOf(mediaFrame), FixtureDex.classes(apk, setOf(mediaFrame)).keys)
        }
    }

    /** The search tells the real id from one the build doesn't declare. */
    @Test
    fun anIdTheBuildDoesntDeclareIsMissing() {
        val wrong = reelFrame + "_hushgram"
        val table = ViewIdFixture.resourceTable(Fixtures.otherBuilds().first())
        assertEquals(listOf(wrong), ViewIdFixture.missing(table, listOf(reelFrame, wrong)))
    }

    /** Hooks the layout and checks the call is first in its int setter, with p0 and p1, and nothing else moved. */
    private fun BytecodePatchContext.assertHooked(what: String) {
        val before = layoutCode()
        val set = setter()
        val original = NeutralNativePath(set)
        hookCornerRadius()

        val code = set.code()
        val registers = set.implementation!!.registerCount
        assertEquals("$what: the call first", CORNER_RADIUS, code[0].referenceText())
        assertEquals("$what: the layout and the radius", listOf(registers - 2, registers - 1), code[0].arguments())
        original.assertPreserved("$what setCornerRadius", set, setOf(0))
        assertEquals("$what: the rest of the layout", before - key, layoutCode() - key)
        assertEquals("$what: one call in the layout", 1, radiusCalls())
    }

    private fun builds() = run {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        bundles
    }

    private enum class Layout { PLAIN, NONE, NO_SETTER, JUMPS_TO_START }

    /** A rounded layout shaped like 454's: an int radius setter that keeps it and redraws, and a float one beside it. */
    private fun layout(shape: Layout = Layout.PLAIN): ClassDef? {
        if (shape == Layout.NONE) return null
        val jump = shape == Layout.JUMPS_TO_START
        val methods = mutableListOf(
            method("F", """
                float-to-int v0, p1
                iput v0, p0, $ROUNDED_LAYOUT->radius:I
                return-void
            """),
        )
        if (shape != Layout.NO_SETTER) {
            methods += method("I", """
                ${if (jump) ":start" else ""}
                iput p1, p0, $ROUNDED_LAYOUT->radius:I
                ${if (jump) "if-eqz p1, :start" else ""}
                invoke-virtual { p0 }, Landroid/view/View;->invalidate()V
                return-void
            """)
        }
        return ImmutableClassDef(ROUNDED_LAYOUT, AccessFlags.PUBLIC.value, "Landroid/widget/FrameLayout;", null, null, null, null, methods)
    }

    /** A setCornerRadius taking [parameter], with one local of its own. */
    private fun method(parameter: String, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                ROUNDED_LAYOUT, "setCornerRadius", listOf(ImmutableMethodParameter(parameter, null, null)), "V", AccessFlags.PUBLIC.value,
                null, null, ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun BytecodePatchContext.setter(): MutableMethod = mutableClassDefBy(ROUNDED_LAYOUT).methods.single { it.setsRadius() }

    private fun BytecodePatchContext.layoutCode(): Map<String, List<String>> =
        mutableClassDefBy(ROUNDED_LAYOUT).methods.associate { m ->
            "${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}" to m.code().map { "${it.opcode} ${it.referenceText()}" }
        }

    private fun BytecodePatchContext.radiusCalls(): Int =
        classDefByOrNull(ROUNDED_LAYOUT)?.let { mutableClassDefBy(it.type).methods }.orEmpty()
            .sumOf { m -> m.code().count { it.referenceText() == CORNER_RADIUS } }

    private fun BytecodePatchContext.status(name: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> =
        (this as? RegisterRangeInstruction)?.let { (it.startRegister until it.startRegister + it.registerCount).toList() }.orEmpty()
}
