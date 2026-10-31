/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.carousel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carousel arrows' bridge: the pager's animated page change found by its code on a stand-in shaped like
 * 453 and on every build of the version, the bridge written to call it, Instagram's code left as it was,
 * and a build that differs stopping the patch before anything changes.
 */
class CarouselArrowsHookTest {
    private val spring = "$REBOUND_VIEW_PAGER->spring(${REBOUND_VIEW_PAGER}DFZ)V"

    /** The bridge is in the CarouselTargets the bundle ships, public and static, and does nothing until patched. */
    @Test
    fun theBridgeIsInTheExtension() {
        val turn = ExtensionDex.classDef(CAROUSEL_TARGETS).methods.single { it.name == "turn" }
        assertTrue(AccessFlags.PUBLIC.isSet(turn.accessFlags) && AccessFlags.STATIC.isSet(turn.accessFlags))
        assertEquals(CAROUSEL_TURN, "$CAROUSEL_TARGETS->${turn.name}(${turn.parameterTypes.joinToString("")})${turn.returnType}")
        assertEquals(listOf(Opcode.RETURN_VOID), turn.code().map { it.opcode })
    }

    /** The animated page change is found, not the jump or the slide that doesn't animate, and the bridge hands it the pager, page and speed. */
    @Test
    fun theBridgeCallsThePagersAnimatedPageChange() {
        val context = PatchContexts.of(classes())
        val before = context.pagerCode()
        val turn = context.findCarouselTurn()
        assertEquals("slide", turn.name)
        context.apply { applyCarouselArrows(turn) }

        val body = context.bridge()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), body.take(3).map { it.opcode })
        assertEquals(REBOUND_VIEW_PAGER, body[0].referenceText())
        assertEquals("$REBOUND_VIEW_PAGER->slide(IF)V", body[1].referenceText())
        assertEquals("the pager, the page and the speed, as handed over", listOf(0, 1, 2), body[1].arguments())
        assertEquals("Instagram's pager is untouched", before, context.pagerCode())
    }

    @Test
    fun aBuildThatDiffersFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape.NO_PAGER to "no $REBOUND_VIEW_PAGER",
            Shape.PRIVATE_PAGER to "$REBOUND_VIEW_PAGER isn't public",
            Shape.NO_TURN to "found 0",
            Shape.TWO_TURNS to "found 2",
            Shape.OTHER_SPRING to "found 0",
            Shape.PRIVATE_TURN to "the page change, isn't public",
        )
        for ((shape, message) in cases) {
            val context = PatchContexts.of(classes(shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findCarouselTurn() }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(message))
            assertEquals("$shape: the bridge was written", listOf(Opcode.RETURN_VOID), context.bridge().map { it.opcode })
        }
    }

    /** An extension without the bridge stops the patch rather than turning on arrows that can't turn a page. */
    @Test
    fun aMissingBridgeStopsThePatch() {
        val empty = ImmutableClassDef(CAROUSEL_TARGETS, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null)
        val context = PatchContexts.of(classes().filter { it.type != CAROUSEL_TARGETS } + empty)
        val turn = context.findCarouselTurn()
        val failure = assertThrows(PatchException::class.java) { context.applyCarouselArrows(turn) }
        assertTrue(failure.message, failure.message!!.contains("has no static void turn(Object, int, float)"))
    }

    /**
     * In each build of the version the pager is public and has one public animated page change, handing
     * the page and the speed to the pager's own spring call, and the bridge names it. Read as the patcher
     * reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildFindsThePageChange() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val found = classesOf(apk, setOf(REBOUND_VIEW_PAGER))
                assertEquals("$what: the pager", setOf(REBOUND_VIEW_PAGER), found.keys)
                val context = PatchContexts.of(found.values + ExtensionDex.classDef(CAROUSEL_TARGETS))
                val before = context.pagerCode()
                val turn = context.findCarouselTurn()
                assertEquals("$what: a page and a speed", listOf("I", "F"), turn.parameterTypes.map(Any::toString))
                val code = turn.code()
                assertEquals("$what: animated", 1, (code[2] as NarrowLiteralInstruction).narrowLiteral)
                context.apply { applyCarouselArrows(turn) }
                assertEquals("$what: the bridge", "$REBOUND_VIEW_PAGER->${turn.name}(IF)V", context.bridge()[1].referenceText())
                assertEquals("$what: Instagram's pager is untouched", before, context.pagerCode())
            }
        }
    }

    private enum class Shape { PLAIN, NO_PAGER, PRIVATE_PAGER, NO_TURN, TWO_TURNS, OTHER_SPRING, PRIVATE_TURN }

    /**
     * A pager shaped like 453's: the animated page change, a slide to a page that doesn't animate, a jump
     * taking the page alone, and the static spring call they share.
     */
    private fun classes(shape: Shape = Shape.PLAIN): List<ClassDef> {
        fun slide(name: String, animate: Int, flags: Int = AccessFlags.PUBLIC.value, target: String = spring) =
            method(REBOUND_VIEW_PAGER, name, listOf("I", "F"), "V", 4, flags = flags, body = """
                int-to-float v3, p1
                float-to-double v1, p2
                const/4 v0, $animate
                invoke-static { p0, v1, v2, v3, v0 }, $target
                return-void
            """)
        val methods = mutableListOf(
            slide(
                "slide",
                if (shape == Shape.NO_TURN) 0 else 1,
                if (shape == Shape.PRIVATE_TURN) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value,
                if (shape == Shape.OTHER_SPRING) "$REBOUND_VIEW_PAGER->spring(${REBOUND_VIEW_PAGER}DFI)V" else spring,
            ),
            slide("settle", 0),
            method(REBOUND_VIEW_PAGER, "jump", listOf("I"), "V", 4, body = """
                const-wide/16 v0, 0
                int-to-float v2, p1
                const/4 v3, 0
                invoke-static { p0, v0, v1, v2, v3 }, $spring
                return-void
            """),
            method(REBOUND_VIEW_PAGER, "spring", listOf(REBOUND_VIEW_PAGER, "D", "F", "Z"), "V", 0, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, body = """
                return-void
            """),
        )
        if (shape == Shape.TWO_TURNS) methods += slide("slideToo", 1)
        val flags = if (shape == Shape.PRIVATE_PAGER) AccessFlags.FINAL.value else AccessFlags.PUBLIC.value
        val found = mutableListOf(ExtensionDex.classDef(CAROUSEL_TARGETS))
        if (shape != Shape.NO_PAGER) {
            found += ImmutableClassDef(REBOUND_VIEW_PAGER, flags, "Landroid/view/ViewGroup;", null, null, null, null, methods)
        }
        return found
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value,
    ): Method {
        val self = if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val total = registers + self + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun BytecodePatchContext.bridge(): List<Instruction> =
        mutableClassDefBy(CAROUSEL_TARGETS).methods.single { it.name == "turn" }.code()

    private fun BytecodePatchContext.pagerCode(): Map<String, List<String>> =
        mutableClassDefBy(REBOUND_VIEW_PAGER).methods.associate { m ->
            "${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}" to m.code().map { it.describe() }
        }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /** An instruction as text: its opcode constant, its arguments and its reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode)
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        referenceText()?.let { append(" $it") }
    }
}
