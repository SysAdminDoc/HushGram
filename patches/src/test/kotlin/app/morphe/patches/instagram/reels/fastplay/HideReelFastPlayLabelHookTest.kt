/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fastplay

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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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

class HideReelFastPlayLabelHookTest {
    private val part = "Lfixture/FastPlayPart;"
    private val composer = "Lfixture/Composer;"
    private val label = "Lfixture/Label;"
    private val component = "Lfixture/Component;"
    private val state = "Lfixture/State;"
    private val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    private val SHOW = "Landroid/widget/Toast;->show()V"
    private val SET_GRAVITY = "Landroid/widget/Toast;->setGravity(III)V"

    /** The hook the patch writes is in the FastPlayLabel the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val owner = FAST_PLAY_LABEL.substringBefore("->")
        val declared = ExtensionDex.classDef(owner).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FAST_PLAY_LABEL is not in the extension: $declared", FAST_PLAY_LABEL.substringAfter("->") in declared)
    }

    /**
     * A fast play part shaped like 454's 0Bcw: the draw returns a plain component on one branch, the label
     * builder's answer on another and null on the last. Only the label's return passes through the hook,
     * cast back to the builder's type, and nothing stock is taken out.
     */
    @Test
    fun onlyTheLabelReturnIsFiltered() {
        val context = PatchContexts.of(classes())
        val stock = context.render().code()

        val found = context.findFastPlayLabel()
        assertEquals("the draw's class", part, found.render.definingClass)
        assertEquals("the label builder's type", label, found.type)
        context.hideFastPlayLabel(found)

        val patched = context.render().code()
        assertFiltered("fixture", patched, label)
        assertTrue("stock instructions kept in order", isKept(stock, patched))
        assertEquals("stock returns", 3, stock.count { it.opcode == Opcode.RETURN_OBJECT })
        assertEquals("the null and plain returns don't pass through the hook", 2,
            patched.indices.count { patched[it].opcode == Opcode.RETURN_OBJECT && patched.getOrNull(it - 3)?.referenceText() != FAST_PLAY_LABEL })
        assertEquals("the label builder is untouched", 0,
            context.mutableClassDefBy(part).methods.single { it.name == "label" }.code().count { it.referenceText() == FAST_PLAY_LABEL })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(drawLoads = false) to "expected one fast play draw loading \"$FAST_PLAY_RENDER\", found 0",
            classes(labelLoads = false) to "to call its label builder loading \"$FAST_PLAY_LABEL_RENDER\" once, found 0",
            classes(callsLabelTwice = true) to "to call its label builder loading \"$FAST_PLAY_LABEL_RENDER\" once, found 2",
            classes(keepsLabel = true) to "doesn't return its label builder's answer straight away",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hideFastPlayLabel(context.findFastPlayLabel()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == FAST_PLAY_LABEL } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the draw and its label return are found, and the label alone is filtered. */
    @Test
    fun eachDeclaredBuildFiltersTheLabel() {
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
    fun eachOtherBuildFiltersTheLabelToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** The lock message hook is in the FastPlayLabel the bundle ships, public and static (#702). */
    @Test
    fun theLockMessageHookIsInTheExtension() {
        val owner = LOCK_TOAST.substringBefore("->")
        val declared = ExtensionDex.classDef(owner).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$LOCK_TOAST is not in the extension: $declared", LOCK_TOAST.substringAfter("->") in declared)
    }

    /**
     * A lock lambda shaped like 456's 0BR6.invoke on 386214574: a field picks a branch, the lock one shows its
     * toast on v2 and the exit one on v4 through range calls, each placed at the top center first; a third
     * branch shows another toast lower down, and a call elsewhere places its toast by a field. Each lock show
     * goes to the hook on the toast's own register, in order, and the other toasts are left alone.
     */
    @Test
    fun eachLockToastIsHandedToTheHook() {
        val context = PatchContexts.of(lockClasses())
        val stock = context.lockInvoke().code()
        val elsewhere = context.mutableClassDefBy(other).methods.single().code()

        val found = context.findLockToasts()
        assertEquals("the toasts found", listOf("invoke", "invoke"), found.map { it.method.name })
        assertEquals("the toasts' registers", listOf(2, 4), found.map { it.register })
        context.hideLockToasts(found)

        val shows = stock.indices.filter { stock[it].referenceText() == SHOW }
        assertEquals("stock shows", 3, shows.size)
        assertHandedOver("invoke", stock, context.lockInvoke().code(), listOf(shows[0] to 2, shows[1] to 4))
        assertEquals("a toast placed elsewhere is untouched", elsewhere.map { it.referenceText() },
            context.mutableClassDefBy(other).methods.single().code().map { it.referenceText() })
    }

    /**
     * A lock lambda shaped like 0BPD.invoke on 386214562: each branch picks its message and the two meet at
     * one toast on v2, placed at the top center and shown once. That show is found once and handed over once.
     */
    @Test
    fun aFoldedLambdaHandsItsOneSharedShowOver() {
        val context = PatchContexts.of(lockClasses(shared = true))
        val stock = context.lockInvoke().code()

        val found = context.findLockToasts()
        assertEquals("one show for both toasts", listOf("invoke"), found.map { it.method.name })
        assertEquals("the toast's register", 2, found.single().register)
        context.hideLockToasts(found)

        val shows = stock.indices.filter { stock[it].referenceText() == SHOW }
        assertEquals("stock shows", 2, shows.size)
        assertHandedOver("invoke", stock, context.lockInvoke().code(), listOf(shows[0] to 2))
    }

    /** A build whose lock toasts the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildWhoseLockToastsThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            lockClasses(lockGravity = 0x51) to "expected one method showing a toast placed at gravity 0x31, found 0",
            lockClasses(placesBefore = true) to "expected one method showing a toast placed at gravity 0x31, found 0",
            lockClasses(elsewhereAtTop = true) to "expected one method showing a toast placed at gravity 0x31, found 2",
            lockClasses(otherAtTop = true) to "to show the speed lock toasts once or once each, found 3",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hideLockToasts(context.findLockToasts()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == LOCK_TOAST } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the lock toasts are found in one method and handed to the hook. */
    @Test
    fun eachDeclaredBuildHandsItsLockToastsOver() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                lockBuilds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHandsItsLockToastsOverToo() {
        for (base in Fixtures.otherBuilds()) lockBuilds(base, base.parentFile.name)
    }

    /**
     * Every class calling Toast.setGravity, read from [bundle]. The shows found are all in one method, and
     * each shows a toast an instance call built from a Context and a string resource (`const` 0x7f..), as both
     * lock messages are; each goes to the hook on its toast's register, and nothing else is written.
     */
    private fun lockBuilds(bundle: File, label: String) {
        val placing = mutableMapOf<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in placing) continue
                val calls = classDef.methods.any { method -> method.code().any { it.referenceText() == SET_GRAVITY } }
                if (calls) placing[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        assertTrue("$label: classes calling $SET_GRAVITY", placing.isNotEmpty())
        val classes = placing.values.toList()
        val context = PatchContexts.of(classes)

        val found = context.findLockToasts()
        assertTrue("$label: one or two shows, found ${found.size}", found.size in 1..2)
        val method = found.map { it.method }.distinctBy { "${it.definingClass}->${it.name}" }.single()
        val stock = method.code()
        for (show in found) {
            val made = (show.at downTo 0).first { at ->
                stock[at].opcode == Opcode.MOVE_RESULT_OBJECT && (stock[at] as OneRegisterInstruction).registerA == show.register
            }
            val maker = (stock[made - 1] as ReferenceInstruction).reference as MethodReference
            assertEquals("$label: the toast at ${show.at} is built from a Context and a string",
                listOf("Landroid/content/Context;", "I"), maker.parameterTypes.map(Any::toString))
            assertTrue("$label: a string resource is loaded before the toast at ${show.at}", (0 until made).any { at ->
                stock[at].opcode == Opcode.CONST && ((stock[at] as NarrowLiteralInstruction).narrowLiteral ushr 24) == 0x7f
            })
        }
        context.hideLockToasts(found)

        val patched = context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        }.code()
        assertHandedOver("$label ${method.definingClass}->${method.name}", stock, patched, found.map { it.at to it.register })
        assertEquals(
            "$label: methods hooked",
            1,
            classes.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() == LOCK_TOAST } },
        )
    }

    /**
     * Each `Toast.show()` at a key of [handed] in [stock] became a one-register range call to the hook on the
     * register beside it, the toast that stock call showed; every other instruction, any other show included,
     * is as it was.
     */
    private fun assertHandedOver(what: String, stock: List<Instruction>, patched: List<Instruction>, handed: List<Pair<Int, Int>>) {
        assertEquals("$what: instructions", stock.size, patched.size)
        for ((at, register) in handed) {
            assertEquals("$what: a stock show at $at", SHOW, stock[at].referenceText())
            val stockRegister = when (val call = stock[at]) {
                is FiveRegisterInstruction -> call.registerC
                is RegisterRangeInstruction -> call.startRegister
                else -> error("$what: the stock show reads no register")
            }
            assertEquals("$what: the toast's register at $at", stockRegister, register)
            assertEquals("$what: the hook's call at $at", Opcode.INVOKE_STATIC_RANGE, patched[at].opcode)
            assertEquals("$what: the hook at $at", LOCK_TOAST, patched[at].referenceText())
            val range = patched[at] as RegisterRangeInstruction
            assertEquals("$what: registers handed to the hook at $at", 1, range.registerCount)
            assertEquals("$what: the toast handed over at $at", register, range.startRegister)
        }
        val replaced = handed.map { it.first }.toSet()
        assertEquals("$what: toasts still shown", stock.count { it.referenceText() == SHOW } - replaced.size,
            patched.count { it.referenceText() == SHOW })
        for (index in stock.indices) {
            if (index in replaced) continue
            assertEquals("$what: instruction $index", stock[index].opcode, patched[index].opcode)
            assertEquals("$what: instruction $index's reference", stock[index].referenceText(), patched[index].referenceText())
        }
    }

    /**
     * A lock lambda shaped like 0BR6.invoke on 386214574: a field picks the lock branch, the exit branch or
     * another toast's. The lock one builds its toast on v2 from a string resource and places it at
     * [lockGravity] before showing it; the exit one does the same on v4 through range calls; the other one
     * places its toast low (at the top too with [otherAtTop]). With [shared], shaped like 0BPD.invoke on
     * 386214562, the lock and exit branches only pick their string and meet at one toast on v2. With
     * [placesBefore] the lock and exit toasts are placed one instruction before their gravity is loaded. A
     * static call in another class places its toast by a field, or at the top with [elsewhereAtTop].
     */
    private fun lockClasses(
        lockGravity: Int = 0x31,
        shared: Boolean = false,
        placesBefore: Boolean = false,
        otherAtTop: Boolean = false,
        elsewhereAtTop: Boolean = false,
    ): List<ClassDef> {
        fun gravity(register: Int) = "const/16 v$register, 0x${lockGravity.toString(16)}"
        fun place(call: String) =
            if (placesBefore) "${gravity(0)}\nconst/4 v3, 0x0\n$call" else "const/4 v3, 0x0\n${gravity(0)}\n$call"
        val lock = """
            const v0, 0x7f131980
            invoke-virtual {p0, v8, v0}, $lambda->make(Landroid/content/Context;I)Landroid/widget/Toast;
            move-result-object v2
            const/16 v1, 0x40
            ${place("invoke-virtual {v2, v0, v3, v1}, $SET_GRAVITY")}
            invoke-virtual {v2}, $SHOW
            goto :done
        """
        val exit = """
            const v0, 0x7f131983
            invoke-virtual {p0, v8, v0}, $lambda->make(Landroid/content/Context;I)Landroid/widget/Toast;
            move-result-object v4
            const/4 v6, 0x0
            const/16 v7, 0x40
            ${if (placesBefore) "${gravity(5)}\nconst/4 v0, 0x0\ninvoke-virtual/range {v4 .. v7}, $SET_GRAVITY" else "${gravity(5)}\ninvoke-virtual/range {v4 .. v7}, $SET_GRAVITY"}
            invoke-virtual/range {v4 .. v4}, $SHOW
            goto :done
        """
        val meeting = """
            const v0, 0x7f131982
            goto :make
            :exit
            const v0, 0x7f131985
            :make
            invoke-virtual {p0, v8, v0}, $lambda->make(Landroid/content/Context;I)Landroid/widget/Toast;
            move-result-object v2
            const/16 v1, 0x40
            ${place("invoke-virtual {v2, v0, v3, v1}, $SET_GRAVITY")}
            invoke-virtual {v2}, $SHOW
            goto :done
        """
        val body = """
            iget-object v8, p0, $lambda->context:Landroid/content/Context;
            iget v0, p0, $lambda->which:I
            const/4 v1, 0x2
            if-eq v0, v1, :other
            if-eqz v0, :exit
            ${if (shared) meeting else "$lock\n:exit\n$exit"}
            :other
            invoke-static {p0}, Lfixture/Toasts;->other(Ljava/lang/Object;)Landroid/widget/Toast;
            move-result-object v2
            const/4 v3, 0x0
            const/16 v0, ${if (otherAtTop) "0x31" else "0x51"}
            invoke-virtual {v2, v0, v3, v3}, $SET_GRAVITY
            invoke-virtual {v2}, $SHOW
            :done
            sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
            return-object v0
        """
        val elsewhere = """
            invoke-static {p0}, Lfixture/Toasts;->other(Ljava/lang/Object;)Landroid/widget/Toast;
            move-result-object v2
            const/4 v0, 0x0
            ${if (elsewhereAtTop) "const/16 v1, 0x31" else "iget v1, p0, $other->gravity:I"}
            invoke-virtual {v2, v1, v0, v0}, $SET_GRAVITY
            invoke-virtual {v2}, $SHOW
            return-void
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value
        return listOf(
            classDef(lambda, listOf(method(lambda, "invoke", emptyList(), "Ljava/lang/Object;", 9, flags, body))),
            classDef(other, listOf(method(other, "show", listOf(other), "V", 3, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, elsewhere))),
        )
    }

    private val lambda = "Lfixture/LockLambda;"
    private val other = "Lfixture/OtherToast;"

    private fun BytecodePatchContext.lockInvoke(): Method = mutableClassDefBy(lambda).methods.single { it.name == "invoke" }

    private fun builds(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, FAST_PLAY_RENDER) + FixtureDex.classesLoading(bundle, FAST_PLAY_LABEL_RENDER)
        val classes = FixtureDex.withStringPools(bundle, loading.distinctBy { it.type })
        val context = PatchContexts.of(classes)

        val found = context.findFastPlayLabel()
        val stock = found.render.code()
        assertEquals("$label: one label call", 1, stock.count { it.referenceText()?.let { text -> text.endsWith(")${found.type}") && text.startsWith(found.render.definingClass) } == true })
        context.hideFastPlayLabel(found)

        val patched = context.mutableClassDefBy(found.render.definingClass).methods.single {
            it.name == found.render.name && it.parameterTypes.map(Any::toString) == found.render.parameterTypes.map(Any::toString)
        }.code()
        assertFiltered(label, patched, found.type)
        assertTrue("$label: stock instructions kept in order", isKept(stock, patched))
        assertEquals(
            "$label: methods hooked",
            1,
            classes.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() == FAST_PLAY_LABEL } },
        )
    }

    /**
     * One hook call: a range call over the label call's result register, its answer back in that register,
     * cast to [type], and returned. The label call's move-result goes straight on to it, or to a goto that
     * leads to it when a try range covers the return.
     */
    private fun assertFiltered(what: String, code: List<Instruction>, type: String) {
        val calls = code.indices.filter { code[it].referenceText() == FAST_PLAY_LABEL }
        assertEquals("$what: hook calls", 1, calls.size)
        val at = calls.single()
        val range = code[at] as RegisterRangeInstruction
        val register = range.startRegister
        assertEquals("$what: the hook's call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
        assertEquals("$what: registers handed to the hook", 1, range.registerCount)
        assertEquals("$what: the hook's answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals("$what: the answer's register", register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[at + 2].opcode)
        assertEquals("$what: the cast's register", register, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast's type", type, ((code[at + 2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[at + 3].opcode)
        assertEquals("$what: the return's register", register, (code[at + 3] as OneRegisterInstruction).registerA)

        val labelCall = code.indices.single {
            val reference = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
            (code[it].opcode == Opcode.INVOKE_DIRECT || code[it].opcode == Opcode.INVOKE_DIRECT_RANGE) && reference?.returnType == type
        }
        assertEquals("$what: the label's result", Opcode.MOVE_RESULT_OBJECT, code[labelCall + 1].opcode)
        assertEquals("$what: the label's register", register, (code[labelCall + 1] as OneRegisterInstruction).registerA)
        val next = code[labelCall + 2]
        assertTrue("$what: after the label call comes ${next.opcode}", labelCall + 2 == at || next.opcode in gotos)
    }

    /** Whether every stock instruction is still there in order, a goto of any width read as one and the replaced return as a goto. */
    private fun isKept(stock: List<Instruction>, patched: List<Instruction>): Boolean {
        fun Instruction.kind() = if (opcode in gotos) Opcode.GOTO else opcode
        val wanted = stock.filter { it.opcode != Opcode.NOP }
        val have = patched.filter { it.opcode != Opcode.NOP }
        var at = 0
        for (instruction in have) {
            if (at < wanted.size && instruction.kind() == wanted[at].kind() &&
                instruction.referenceText() == wanted[at].referenceText()) {
                at++
            } else if (at < wanted.size && wanted[at].opcode == Opcode.RETURN_OBJECT && instruction.kind() == Opcode.GOTO) {
                at++
            }
        }
        return at == wanted.size
    }

    /**
     * A fast play part shaped like 454's 0Bcw: the draw loads its marker, returns a plain component when a field
     * says so, the label builder's answer when the state's ordinal is over zero, and null otherwise. The label
     * builder loads its own marker.
     */
    private fun classes(
        drawLoads: Boolean = true,
        labelLoads: Boolean = true,
        callsLabelTwice: Boolean = false,
        keepsLabel: Boolean = false,
    ): List<ClassDef> {
        val instance = AccessFlags.PUBLIC.value
        val labelCall = "invoke-direct {p0, p1, v3}, $part->label($composer$state)$label"
        val drawBody = """
            const/4 v4, 0x0
            ${if (drawLoads) "const-string v0, \"$FAST_PLAY_RENDER\"" else "const-string v0, \"other\""}
            invoke-static {v0}, Lfixture/Trace;->begin(Ljava/lang/String;)V
            iget-object v3, p0, $part->state:$state
            iget-boolean v0, p0, $part->plain:Z
            if-eqz v0, :fast
            new-instance v0, $component
            invoke-direct {v0}, $component-><init>()V
            return-object v0
            :fast
            invoke-virtual {v3}, Ljava/lang/Enum;->ordinal()I
            move-result v0
            if-le v0, v4, :none
            ${if (callsLabelTwice) "$labelCall\nmove-result-object v1" else ""}
            $labelCall
            move-result-object v0
            ${if (keepsLabel) "move-object v1, v0\nreturn-object v1" else "return-object v0"}
            :none
            const/4 v0, 0x0
            return-object v0
        """
        val labelBody = """
            ${if (labelLoads) "const-string v0, \"$FAST_PLAY_LABEL_RENDER\"" else "const-string v0, \"other\""}
            invoke-static {v0}, Lfixture/Trace;->begin(Ljava/lang/String;)V
            new-instance v0, $label
            invoke-direct {v0}, $label-><init>()V
            return-object v0
        """
        return listOf(
            classDef(part, listOf(
                method(part, "draw", listOf(composer), component, 5, instance, drawBody),
                method(part, "label", listOf(composer, state), label, 1, AccessFlags.PRIVATE.value, labelBody),
            )),
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

    private fun BytecodePatchContext.render(): Method = mutableClassDefBy(part).methods.single { it.name == "draw" }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
