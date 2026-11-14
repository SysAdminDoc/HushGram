/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.skip

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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DoubleTapSkipReelsHookTest {
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val useCase = "Lfixture/SkipUseCase;"
    private val navigator = "Lfixture/SkipNavigator;"
    private val turner = "Lfixture/SkipTurner;"
    private val way = "Lfixture/SkipWay;"
    private val hooks = listOf(SKIP, SECONDS, SECONDS_DOUBLE, EDGE, EDGE_DOUBLE)
    private val flags = setOf(SKIP_FLAG, SECONDS_FLAG, EDGE_FLAG)

    /** Every hook the patch writes is in the ReelSkip the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_SKIP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        (hooks + SKIPPED).forEach { assertTrue("$it is not in the extension: $declared", it.substringAfter("->") in declared) }
    }

    /**
     * Each way the navigator answers after it seeks is handed to the arrows' hook just before the
     * navigator reads it, in the same register, and the read another case of the merged lambda makes,
     * which the marker never reaches, isn't (#691). The flags' hooks stay as they were.
     */
    @Test
    fun eachWayASkipGoesIsHandedToTheArrows() {
        val context = PatchContexts.of(classes().filter { it.type == useCase } + turnClasses())

        val turns = context.findSkipTurns()
        context.skipReels()
        context.showSkipTurns(turns)

        assertEquals(listOf(EDGE, SECONDS), context.hooksIn(turner, "turn"))
        assertEquals(setOf("$way->A02:$way", "$way->A03:$way"), assertHandsTheWays("synthetic", context.method(turner, "turn")))
        val code = context.method(turner, "turn").code()
        val reads = code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT }
        assertEquals("the other case's read keeps no hook", 5, reads.size)
        assertTrue("the other case's read keeps no hook", code[reads.last() - 1].referenceText() != SKIPPED)
    }

    /** A navigator the arrows can't be put in fails at patch time, saying what it found. */
    @Test
    fun aNavigatorTheArrowsDontFitFails() {
        val cases = listOf(
            turnClasses(marker = false) to "expected one method holding the $SKIP_DIRECTION marker, found none",
            turnClasses(oneWay = true) to "to read the two ways a skip goes once each",
            turnClasses(named = false) to "to read the two ways a skip goes once each",
            turnClasses(jumpedOnto = true) to "jumps onto its read of A02",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findSkipTurns() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /**
     * Each call to the arrows' hook in [method] is handed the register a read of a way just filled,
     * and the navigator's own read of the same way into that register follows. Answers the ways handed.
     */
    private fun assertHandsTheWays(what: String, method: Method): Set<String> {
        val code = method.code()
        val calls = code.indices.filter { code[it].referenceText() == SKIPPED }
        assertEquals("$what: arrow hooks", 2, calls.size)
        return calls.mapTo(mutableSetOf()) { at ->
            val range = code[at] as RegisterRangeInstruction
            assertEquals("$what: the arrows' call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            assertEquals("$what: registers the arrows are handed", 1, range.registerCount)
            val read = code[at - 1]
            val own = code[at + 1]
            assertEquals("$what: what comes before the arrows", Opcode.SGET_OBJECT, read.opcode)
            assertEquals("$what: what comes after the arrows", Opcode.SGET_OBJECT, own.opcode)
            assertEquals("$what: the way the arrows are handed", range.startRegister, (read as OneRegisterInstruction).registerA)
            assertEquals("$what: the navigator's own read", range.startRegister, (own as OneRegisterInstruction).registerA)
            assertEquals("$what: the way read twice", read.referenceText(), own.referenceText())
            read.referenceText()!!
        }
    }

    /** A build reading the navigator's numbers as floats gets the float hooks there, and the use case its own. */
    @Test
    fun floatReadsGetTheFloatHooks() {
        val context = PatchContexts.of(classes(fraction = "F"))

        context.skipReels()

        assertEquals(listOf(SKIP, SECONDS_DOUBLE), context.hooksIn(useCase, "state"))
        assertEquals(listOf(EDGE, SECONDS), context.hooksIn(navigator, "go"))
        assertAnswered("use case", context.method(useCase, "state"))
        assertAnswered("navigator", context.method(navigator, "go"))
    }

    /** A build reading them as doubles gets the double hooks, two registers each. */
    @Test
    fun doubleReadsGetTheDoubleHooks() {
        val context = PatchContexts.of(classes(fraction = "D"))

        context.skipReels()

        assertEquals(listOf(SKIP, SECONDS_DOUBLE), context.hooksIn(useCase, "state"))
        assertEquals(listOf(EDGE_DOUBLE, SECONDS_DOUBLE), context.hooksIn(navigator, "go"))
        assertAnswered("navigator", context.method(navigator, "go"))
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val skip = SKIP_FLAG.toString(16)
        val seconds = SECONDS_FLAG.toString(16)
        val edge = EDGE_FLAG.toString(16)
        val cases = listOf(
            classes(skipRead = false) to "nothing reads $skip",
            classes(edgeRead = false) to "nothing reads $edge",
            classes(navigatorSeconds = false) to "$navigator->go reads $edge without $seconds",
            classes(navigatorSeconds = false, useCaseSeconds = false) to "nothing reads $seconds",
            classes(fraction = "J") to "which answers J, not F or D",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.skipReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText()?.startsWith(REEL_SKIP) == true } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build every read of the three flags is answered, and nothing else is. */
    @Test
    fun eachDeclaredBuildAnswersEveryRead() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAnswersEveryReadToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                // The ways a skip goes are an enum naming them in its initializer.
                val aWay = classDef.superclass == "Ljava/lang/Enum;" && classDef.methods.any { it.loadsAWay() }
                if (aWay || classDef.methods.any { it.loadsAFlag() || it.loadsTheTurnMarker() }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(holders)

        val reads = context.findReelSkip()
        val turns = context.findSkipTurns()
        context.skipReels()
        context.showSkipTurns(turns)

        val turned = context.mutableClassDefBy(turns.type).methods
            .single { it.name == turns.name && it.parameterTypes.map(CharSequence::toString) == turns.parameters }
        assertEquals("$label: ways handed to the arrows", turns.fields, assertHandsTheWays(label, turned))
        assertEquals(
            "$label: methods handing ways to the arrows",
            1,
            holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() == SKIPPED } },
        )

        assertEquals("$label: gate reads", 1, reads.count { it.second == SKIP })
        assertEquals("$label: edge reads", 1, reads.count { it.second in setOf(EDGE, EDGE_DOUBLE) })
        assertEquals("$label: seconds reads", 2, reads.count { it.second in setOf(SECONDS, SECONDS_DOUBLE) })
        val methods = reads.map { (load, _) -> Triple(load.type, load.name, load.parameters) }.distinct()
        assertEquals("$label: methods reading", 2, methods.size)
        for ((type, name, parameters) in methods) {
            val patched = context.mutableClassDefBy(type).methods
                .single { it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters }
            val expected = reads.filter { (load, _) -> load.type == type && load.name == name && load.parameters == parameters }
            assertEquals("$label: $type->$name hooks", expected.size, patched.code().count { it.referenceText() in hooks })
            assertAnswered("$label: $type->$name", patched)
        }
        assertEquals(
            "$label: methods hooked",
            2,
            holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() in hooks } },
        )
    }

    /**
     * Each hook call follows a read's move-result: a range call over that register (two for a
     * double), its answer back in the same register, the matching width each side.
     */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        val calls = code.indices.filter { code[it].referenceText() in hooks }
        assertTrue("$what: no hooks", calls.isNotEmpty())
        for (at in calls) {
            val hook = code[at].referenceText()!!
            val wide = hook.endsWith(")D")
            val result = if (wide) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT
            val register = (code[at - 1] as OneRegisterInstruction).registerA
            assertEquals("$what: what comes before $hook", result, code[at - 1].opcode)
            assertEquals("$what: $hook's call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            val range = code[at] as RegisterRangeInstruction
            assertEquals("$what: what $hook is handed", register, range.startRegister)
            assertEquals("$what: how many registers $hook is handed", if (wide) 2 else 1, range.registerCount)
            assertEquals("$what: $hook's answer", result, code[at + 1].opcode)
            assertEquals("$what: $hook's register", register, (code[at + 1] as OneRegisterInstruction).registerA)
        }
    }

    /**
     * A use case shaped like 454's 05KP.A0V, which reads the gate and the seconds through a cast
     * config, and a navigator shaped like 0DXI.invoke (floats) or 0C1F.invoke (doubles, then cast
     * down), which reads the edge and the seconds through a static helper.
     */
    private fun classes(
        fraction: String = "F",
        skipRead: Boolean = true,
        useCaseSeconds: Boolean = true,
        edgeRead: Boolean = true,
        navigatorSeconds: Boolean = true,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val takes = listOf("Ljava/lang/Object;")
        val useCaseBody = """
            invoke-static {p0}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v4
            ${if (skipRead) """
            const-wide v0, 0x${SKIP_FLAG.toString(16)}L
            check-cast v4, $configs
            invoke-interface {v4, v0, v1}, $configs->Bbu(J)Z
            move-result v0
            if-eqz v0, :none
            """ else ""}
            ${if (useCaseSeconds) """
            const-wide v0, 0x${SECONDS_FLAG.toString(16)}L
            check-cast v4, $configs
            invoke-interface {v4, v0, v1}, $configs->C5Q(J)D
            move-result-wide v0
            double-to-float v2, v0
            """ else ""}
            :none
            return-object v4
        """
        fun read(flag: Long, into: Int): String {
            val wide = fraction != "F"
            return """
                const-wide v0, 0x${flag.toString(16)}L
                invoke-static {p0, v0, v1}, Lfixture/Mc;->A00(Ljava/lang/Object;J)$fraction
                ${if (wide) "move-result-wide v$into" else "move-result v$into"}
                ${if (fraction == "D") "double-to-float v$into, v$into" else ""}
            """
        }
        val navigatorBody = listOfNotNull(
            if (edgeRead) read(EDGE_FLAG, 2) else null,
            if (navigatorSeconds) read(SECONDS_FLAG, 4) else null,
            "return-object p0",
        ).joinToString("\n")
        return listOf(
            classDef(useCase, listOf(method(useCase, "state", takes, "Ljava/lang/Object;", 5, static, useCaseBody))),
            classDef(navigator, listOf(method(navigator, "go", takes, "Ljava/lang/Object;", 6, static, navigatorBody))),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;"): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, emptyList(), null, null, emptyList(), methods)

    private fun BytecodePatchContext.method(type: String, name: String): Method =
        mutableClassDefBy(type).methods.single { it.name == name }

    private fun BytecodePatchContext.hooksIn(type: String, name: String): List<String> =
        method(type, name).code().mapNotNull { it.referenceText() }.filter { it in hooks }

    /**
     * An enum of the ways a skip goes, [way], and a navigator, [turner], shaped like 454's
     * 0DXI.invoke: a merged lambda whose double tap case loads the [SKIP_DIRECTION] marker, reads
     * the strip and the seconds, seeks back or ahead and answers BACKWARD (A02) or FORWARD (A03).
     * Another case, which the marker doesn't reach, answers FORWARD too.
     */
    private fun turnClasses(
        marker: Boolean = true,
        oneWay: Boolean = false,
        named: Boolean = true,
        jumpedOnto: Boolean = false,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val ahead = if (oneWay) "A02" else "A03"
        val body = """
            if-eqz p0, :other
            const-string v0, "${if (marker) "android_purge_26_q3_$SKIP_DIRECTION" else "android_purge_26_q3_ClipsGestureNavigator_onDoubleTap"}"
            invoke-static {v0}, Lfixture/Trace;->A01(Ljava/lang/String;)V
            const-wide v0, 0x${EDGE_FLAG.toString(16)}L
            invoke-static {p0, v0, v1}, Lfixture/Mc;->A00(Ljava/lang/Object;J)F
            move-result v2
            const-wide v0, 0x${SECONDS_FLAG.toString(16)}L
            invoke-static {p0, v0, v1}, Lfixture/Mc;->A00(Ljava/lang/Object;J)F
            move-result v4
            cmpg-float v5, v2, v4
            ${if (jumpedOnto) "if-eqz v5, :back" else ""}
            if-gez v5, :ahead
            invoke-virtual {p0}, Ljava/lang/Object;->hashCode()I
            :back
            sget-object v3, $way->A02:$way
            :answer
            return-object v3
            :ahead
            invoke-virtual {p0}, Ljava/lang/Object;->hashCode()I
            sget-object v3, $way->$ahead:$way
            goto :answer
            :other
            sget-object v3, $way->A03:$way
            return-object v3
        """
        val initializer = """
            const-string v0, "${if (named) "FORWARD" else "AHEAD"}"
            invoke-static {v0}, Lfixture/Trace;->A01(Ljava/lang/String;)V
            const-string v0, "BACKWARD"
            invoke-static {v0}, Lfixture/Trace;->A01(Ljava/lang/String;)V
            return-void
        """
        val takes = listOf("Ljava/lang/Object;")
        return listOf(
            classDef(turner, listOf(method(turner, "turn", takes, "Ljava/lang/Object;", 6, static, body))),
            classDef(way, listOf(method(way, "<clinit>", emptyList(), "V", 1, AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, initializer)), "Ljava/lang/Enum;"),
        )
    }

    private fun Method.loadsAFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in flags
    }

    private fun Method.loadsAWay(): Boolean = code().any { it.loadsString("BACKWARD") }

    private fun Method.loadsTheTurnMarker(): Boolean = code().any { it.loadsString("android_purge_26_q3_$SKIP_DIRECTION") }

    private fun Instruction.loadsString(value: String): Boolean =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            ((this as ReferenceInstruction).reference as StringReference).string == value

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
