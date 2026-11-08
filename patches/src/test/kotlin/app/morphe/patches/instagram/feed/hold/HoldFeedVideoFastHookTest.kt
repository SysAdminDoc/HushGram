/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.hold

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.tap.FEED_TAP_HANDLER_STRING
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HoldFeedVideoFastHookTest {
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val handler = "Lfixture/FeedTapHandler;"
    private val reader = "Lfixture/HoldReader;"
    private val view = "Landroid/view/View;"
    private val media = "Lcom/instagram/feed/media/Media;"
    private val point = "Lfixture/Point;"
    private val holder = "Lfixture/Holder;"
    private val state = "Lfixture/State;"
    private val hooks = listOf(FAST, FIXED_STRIP, STRIP)
    private val flags = (KIND_FLAGS + listOf(HOLD_FLAG, FAST_FLAG, CAROUSEL_FLAG, FIXED_STRIP_FLAG, STRIP_FLAG)).toSet()

    /** Every hook the patch writes is in the FeedHoldSpeed the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_HOLD).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        hooks.forEach { assertTrue("$it is not in the extension: $declared", it.substringAfter("->") in declared) }
    }

    /**
     * A press entry shaped like 454's 02g8.A05: the hold helper's answer, then the three kinds read in one place
     * through a move-wide, the carousel's read through its own, the speed-up, the fixed strip and the strip's share.
     * Each read gets its hook once, the helper's answer too, and nothing is taken out.
     */
    @Test
    fun everyReadInThePressEntryIsAnswered() {
        val context = PatchContexts.of(classes())
        val stock = context.press().code()

        val found = context.findFeedHold()
        assertEquals("the press entry", handler, found.press.definingClass)
        assertEquals("loads answered", 7, found.loads.size)
        assertEquals("the three kinds share their read", 3, found.loads.count { it.first.shared })
        context.holdFast(found)

        val patched = context.press().code()
        assertEquals(listOf(FAST, FAST, FAST, FAST, FAST, FAST, FIXED_STRIP, STRIP).sorted(),
            patched.mapNotNull { it.referenceText() }.filter { it in hooks }.sorted())
        assertAnswered("press", patched)
        val hookAt = patched.indices.first { patched[it].referenceText() == FAST }
        assertEquals("the helper's answer is the first one passed on", "$reader->on(Ljava/lang/Object;)Z",
            patched[hookAt - 2].referenceText())
        assertTrue("stock instructions kept in order", isKept(stock, patched))
        assertTrue("the helper itself is untouched", context.mutableClassDefBy(reader).methods.single().code()
            .none { it.referenceText()?.startsWith(FEED_HOLD) == true })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(stripRead = false) to "expected one read of ${STRIP_FLAG.toString(16)}, found 0",
            classes(callsReader = false) to "to call $reader->on once, found 0",
            classes(readsHoldTwice = true) to "expected one read of ${HOLD_FLAG.toString(16)}, found 2",
            classes(fastBeforeHold = true) to "reads a hold flag before it calls the hold helper",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.holdFast(context.findFeedHold()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText()?.startsWith(FEED_HOLD) == true } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the press entry and every read of its flags are found and answered. */
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
        val handlers = FixtureDex.classesHolding(bundle, FEED_TAP_HANDLER_STRING)
        assertEquals("$label: classes holding the tap handler's string", 1, handlers.size)
        val named = handlers.flatMap { it.methods }.flatMap { m -> m.code().mapNotNull { it.typeNamed() } }
            .filter { it.startsWith("L") }.toSet()
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.loadsAFlag() }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val classes = (FixtureDex.classes(bundle, named + handlers.single().type).values + holders).distinctBy { it.type }
        val context = PatchContexts.of(classes)

        val found = context.findFeedHold()
        assertEquals("$label: the press entry's class", handlers.single().type, found.press.definingClass)
        assertEquals("$label: loads answered", 7, found.loads.size)
        val stock = found.press.code()
        context.holdFast(found)

        val patched = context.mutableClassDefBy(found.press.definingClass).methods.single {
            it.name == found.press.name && it.parameterTypes.map(Any::toString) == found.press.parameterTypes.map(Any::toString)
        }.code()
        assertEquals("$label: fast hooks", 6, patched.count { it.referenceText() == FAST })
        assertEquals("$label: fixed strip hooks", 1, patched.count { it.referenceText() == FIXED_STRIP })
        assertEquals("$label: strip hooks", 1, patched.count { it.referenceText() == STRIP })
        assertAnswered(label, patched)
        assertTrue("$label: stock instructions kept in order", isKept(stock, patched))
        assertEquals(
            "$label: methods hooked",
            1,
            classes.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() in hooks } },
        )
    }

    /**
     * Each hook call follows a move-result: a range call over that register (two for the strip's double), its answer
     * back in the same register, the matching width each side.
     */
    private fun assertAnswered(what: String, code: List<Instruction>) {
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

    /** Whether every stock instruction is still there in order, a goto of any width read as one. */
    private fun isKept(stock: List<Instruction>, patched: List<Instruction>): Boolean {
        val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
        fun Instruction.kind() = if (opcode in gotos) Opcode.GOTO else opcode
        val wanted = stock.filter { it.opcode != Opcode.NOP }
        var at = 0
        for (instruction in patched.filter { it.opcode != Opcode.NOP }) {
            if (at < wanted.size && instruction.kind() == wanted[at].kind() &&
                instruction.referenceText() == wanted[at].referenceText()) at++
        }
        return at == wanted.size
    }

    /**
     * A tap handler class shaped like 454's 02g8: the tap handler loading its string, and the press entry. The press
     * entry calls the static hold helper, then picks one of three kind flags in a branch and reads it in one place,
     * then reads the carousel's, the speed-up, the fixed strip and the strip's share.
     */
    private fun classes(
        stripRead: Boolean = true,
        callsReader: Boolean = true,
        readsHoldTwice: Boolean = false,
        fastBeforeHold: Boolean = false,
    ): List<ClassDef> {
        val instance = AccessFlags.PUBLIC.value
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        fun read(flag: Long, call: String = "Bbu(J)Z", result: String = "move-result v4") = """
            const-wide v4, 0x${flag.toString(16)}L
            check-cast v14, $configs
            invoke-interface {v14, v4, v5}, $configs->$call
            $result
        """
        fun moved(flag: Long) = """
            const-wide v17, 0x${flag.toString(16)}L
            check-cast v14, $configs
            move-wide/from16 v4, v17
            invoke-interface {v14, v4, v5}, $configs->Bbu(J)Z
            move-result v4
        """
        val pressBody = """
            invoke-static/range {p0 .. p0}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v14
            ${if (fastBeforeHold) read(FAST_FLAG) else ""}
            ${if (callsReader) """
            invoke-static/range {p0 .. p0}, $reader->on(Ljava/lang/Object;)Z
            move-result v4
            if-eqz v4, :none
            """ else ""}
            if-eqz p3, :second
            if-eqz p4, :third
            const-wide v17, 0x${KIND_FLAGS[0].toString(16)}L
            :kind
            check-cast v14, $configs
            move-wide/from16 v4, v17
            invoke-interface {v14, v4, v5}, $configs->Bbu(J)Z
            move-result v4
            if-eqz v4, :none
            ${moved(CAROUSEL_FLAG)}
            if-nez v4, :none
            ${if (fastBeforeHold) "" else read(FAST_FLAG)}
            if-eqz v4, :none
            ${read(FIXED_STRIP_FLAG)}
            if-nez v4, :none
            ${if (stripRead) read(STRIP_FLAG, "C5Q(J)D", "move-result-wide v4") + "\ndouble-to-float v6, v4" else ""}
            :none
            return-void
            :second
            const-wide v17, 0x${KIND_FLAGS[1].toString(16)}L
            goto :kind
            :third
            const-wide v17, 0x${KIND_FLAGS[2].toString(16)}L
            goto :kind
        """
        val tapBody = """
            const-string v0, "$FEED_TAP_HANDLER_STRING"
            return-void
        """
        fun readerMethod(name: String) = method(reader, name, listOf("Ljava/lang/Object;"), "Z", 3, static, """
            invoke-static {p0}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            const-wide v1, 0x${HOLD_FLAG.toString(16)}L
            check-cast v0, $configs
            invoke-interface {v0, v1, v2}, $configs->Bbu(J)Z
            move-result v0
            return v0
        """)
        return listOf(
            classDef(handler, listOf(
                method(handler, "tap", listOf(view, media, point, holder, state, "Ljava/lang/Object;", "I", "Z"), "V", 2, instance, tapBody),
                method(handler, "press", listOf(view, media, point, holder, state), "V", 20, instance, pressBody),
            )),
            classDef(reader, listOfNotNull(readerMethod("on"), if (readsHoldTwice) readerMethod("onToo") else null)),
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

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null, emptyList(), methods)

    private fun BytecodePatchContext.press(): Method = mutableClassDefBy(handler).methods.single { it.name == "press" }

    private fun Method.loadsAFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in flags
    }

    private fun Instruction.typeNamed(): String? = when (val reference = (this as? ReferenceInstruction)?.reference) {
        is MethodReference -> reference.definingClass
        is FieldReference -> reference.definingClass
        is TypeReference -> reference.type
        else -> null
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
