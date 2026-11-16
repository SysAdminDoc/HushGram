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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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
     * A long press navigator shaped like 454's 0AIS: one static call builds and shows the lock toast, another
     * the one when the lock ends (that one through a range call). Each show goes to the hook on the toast's own
     * register, with nothing else changed, and a toast shown anywhere else is left alone.
     */
    @Test
    fun eachLockToastIsHandedToTheHook() {
        val context = PatchContexts.of(lockClasses())
        val stock = listOf("lockUp", "exitLockUp", "other").associateWith { context.navigatorMethod(it).code() }

        val found = context.findLockToasts()
        assertEquals("the toasts found", listOf("lockUp", "exitLockUp"), found.map { it.method.name })
        assertEquals("the toast's registers", listOf(1, 2), found.map { it.register })
        context.hideLockToasts(found)

        for ((name, register) in listOf("lockUp" to 1, "exitLockUp" to 2)) assertHandedOver(name, stock.getValue(name), context.navigatorMethod(name).code(), register)
        assertEquals("a toast shown elsewhere is untouched", stock.getValue("other").map { it.referenceText() }, context.navigatorMethod("other").code().map { it.referenceText() })
    }

    /**
     * A build where Redex folds both lock toasts into one lambda, like 0288.invoke on 386014714: one instance
     * call loads each marker on its own branch and the branches meet at one show. That show is found once
     * and handed to the hook once.
     */
    @Test
    fun aFoldedLambdaHandsItsOneSharedShowOver() {
        val context = PatchContexts.of(foldedClasses())
        val stock = context.foldedInvoke().code()

        val found = context.findLockToasts()
        assertEquals("one show for both toasts", listOf("invoke"), found.map { it.method.name })
        assertEquals("the toast's register", 3, found.single().register)
        context.hideLockToasts(found)

        assertHandedOver("invoke", stock, context.foldedInvoke().code(), 3)
    }

    /**
     * A folded lambda that keeps a show on each marker's branch, like 0241.invoke on 455's 386113970: the exit
     * branch shows its toast on v3 and jumps past the lock branch, which shows its own on v2 through a range
     * call. Both shows are found, in order, and each is handed to the hook on its own register.
     */
    @Test
    fun aFoldedLambdaWithAShowOnEachBranchHandsBothOver() {
        val context = PatchContexts.of(foldedClasses(showEach = true))
        val stock = context.foldedInvoke().code()

        val found = context.findLockToasts()
        assertEquals("a show for each toast", listOf("invoke", "invoke"), found.map { it.method.name })
        assertEquals("the toasts' registers", listOf(3, 2), found.map { it.register })
        context.hideLockToasts(found)

        assertHandedOver("invoke", stock, context.foldedInvoke().code(), 3, 2)
    }

    /** A build whose lock toasts the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildWhoseLockToastsThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            lockClasses(exitLoads = false) to "expected one speed lock toast loading \"$EXIT_LOCK_TOAST_SHOW\", found 0",
            lockClasses(showsTwice = true) to "to show its toast once, found 2",
            lockClasses(shows = false) to "to show its toast once, found 0",
            foldedClasses(showFirst = true) to "shows its toast before it loads its marker",
            foldedClasses(showEach = true, lockShowsFirst = true) to "to show its toast once, or once after each marker, found 2",
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

    /** In each declared build both lock toasts are found, each in its own method, and handed to the hook. */
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

    private fun lockBuilds(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, LOCK_TOAST_SHOW) + FixtureDex.classesLoading(bundle, EXIT_LOCK_TOAST_SHOW)
        val classes = FixtureDex.withStringPools(bundle, loading.distinctBy { it.type })
        val context = PatchContexts.of(classes)

        // Two static calls on most builds, one folded lambda holding both markers on others (386014714), its
        // one show shared or (455's 386113970) one on each marker's branch.
        val markers = setOf(LOCK_TOAST_SHOW, EXIT_LOCK_TOAST_SHOW)
        val loaders = classes.flatMap { it.methods }
            .filter { method -> method.code().any { (it.reference() as? StringReference)?.string in markers } }
            .map { "${it.definingClass}->${it.name}" }
        assertTrue("$label: one or two methods load the markers, found $loaders", loaders.size in 1..2)

        val found = context.findLockToasts()
        val byMethod = found.groupBy { "${it.method.definingClass}->${it.method.name}" }
        assertEquals("$label: shows found in each method loading a marker", loaders.sorted(), byMethod.keys.sorted())
        val stock = byMethod.mapValues { (_, shows) -> shows.first().method.code() }
        context.hideLockToasts(found)

        for ((where, shows) in byMethod) {
            val method = shows.first().method
            val patched = context.mutableClassDefBy(method.definingClass).methods.single {
                it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
            }.code()
            assertHandedOver("$label $where", stock.getValue(where), patched, *shows.map { it.register }.toIntArray())
        }
        assertEquals(
            "$label: methods hooked",
            loaders.size,
            classes.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() == LOCK_TOAST } },
        )
    }

    /**
     * Each `Toast.show()` in [stock], in order, became a one-register range call to the hook on its entry in
     * [registers], the toast that stock call showed; every other instruction is as it was.
     */
    private fun assertHandedOver(what: String, stock: List<Instruction>, patched: List<Instruction>, vararg registers: Int) {
        val shows = stock.indices.filter { stock[it].referenceText() == "Landroid/widget/Toast;->show()V" }
        assertEquals("$what: stock shows", registers.size, shows.size)
        assertEquals("$what: instructions", stock.size, patched.size)
        for ((at, register) in shows.zip(registers.toList())) {
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
        assertEquals("$what: toasts still shown", 0, patched.count { it.referenceText() == "Landroid/widget/Toast;->show()V" })
        for (index in stock.indices) {
            if (index in shows) continue
            assertEquals("$what: instruction $index", stock[index].opcode, patched[index].opcode)
            assertEquals("$what: instruction $index's reference", stock[index].referenceText(), patched[index].referenceText())
        }
    }

    /**
     * A long press navigator shaped like 454's 0AIS: static, one parameter calls that load their marker, get a
     * toast, set its gravity, show it and answer Unit. The lock one shows on v1, the exit one on v2 through a
     * range call, and an unrelated call shows its own toast.
     */
    private fun lockClasses(exitLoads: Boolean = true, showsTwice: Boolean = false, shows: Boolean = true): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        fun body(marker: String, toast: Int, range: Boolean, again: Boolean = false, show: Boolean = true): String {
            val call = if (range) "invoke-virtual/range {v$toast .. v$toast}, Landroid/widget/Toast;->show()V"
            else "invoke-virtual {v$toast}, Landroid/widget/Toast;->show()V"
            return """
                const-string v0, "$marker"
                invoke-static {v0}, Lfixture/Trace;->begin(Ljava/lang/String;)V
                invoke-static {p0}, Lfixture/Toasts;->make(Ljava/lang/Object;)Landroid/widget/Toast;
                move-result-object v$toast
                const/16 v3, 0x31
                const/4 v0, 0x0
                invoke-virtual {v$toast, v3, v0, v0}, Landroid/widget/Toast;->setGravity(III)V
                ${if (show) call else ""}
                ${if (again) call else ""}
                sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                return-object v0
            """
        }
        return listOf(
            classDef(navigator, listOf(
                method(navigator, "lockUp", listOf(navigatorOwner), "Ljava/lang/Object;", 4, static, body(LOCK_TOAST_SHOW, 1, range = false, show = shows)),
                method(navigator, "exitLockUp", listOf(navigatorOwner), "Ljava/lang/Object;", 4, static,
                    body(if (exitLoads) EXIT_LOCK_TOAST_SHOW else "other", 2, range = true, again = showsTwice)),
                method(navigator, "other", listOf(navigatorOwner), "Ljava/lang/Object;", 4, static, body("other", 1, range = false)),
            )),
        )
    }

    /**
     * A folded lock lambda shaped like 386014714's 0288.invoke: a field picks the exit or the lock branch,
     * each loads its marker, and both then build one toast on v3 and show it. With [showFirst] the show
     * comes before either marker. With [showEach], shaped like 386113970's 0241.invoke, each branch builds
     * and shows its own toast, the exit one on v3 before it jumps past the lock branch and the lock one on
     * v2 through a range call; with [lockShowsFirst] too the lock branch shows before it loads its marker.
     */
    private fun foldedClasses(showFirst: Boolean = false, showEach: Boolean = false, lockShowsFirst: Boolean = false): List<ClassDef> {
        val show = """
            invoke-static {p0}, Lfixture/Toasts;->make(Ljava/lang/Object;)Landroid/widget/Toast;
            move-result-object v3
            invoke-virtual {v3}, Landroid/widget/Toast;->show()V
        """
        val lockShow = """
            invoke-static {p0}, Lfixture/Toasts;->make(Ljava/lang/Object;)Landroid/widget/Toast;
            move-result-object v2
            invoke-virtual/range {v2 .. v2}, Landroid/widget/Toast;->show()V
        """
        val lockMarker = """
            const-string v1, "$LOCK_TOAST_SHOW"
            invoke-static {v1}, Lfixture/Trace;->begin(Ljava/lang/String;)V
        """
        val each = """
            iget v0, p0, $folded->which:I
            if-eqz v0, :lock
            const-string v1, "$EXIT_LOCK_TOAST_SHOW"
            invoke-static {v1}, Lfixture/Trace;->begin(Ljava/lang/String;)V
            $show
            goto :done
            :lock
            ${if (lockShowsFirst) lockShow + lockMarker else lockMarker + lockShow}
            :done
            sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
            return-object v0
        """
        val body = if (showEach) each else """
            ${if (showFirst) show else ""}
            iget v0, p0, $folded->which:I
            if-eqz v0, :lock
            const-string v1, "$EXIT_LOCK_TOAST_SHOW"
            invoke-static {v1}, Lfixture/Trace;->begin(Ljava/lang/String;)V
            goto :make
            :lock
            const-string v1, "$LOCK_TOAST_SHOW"
            invoke-static {v1}, Lfixture/Trace;->begin(Ljava/lang/String;)V
            :make
            ${if (showFirst) "" else show}
            sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
            return-object v0
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value
        return listOf(classDef(folded, listOf(method(folded, "invoke", emptyList(), "Ljava/lang/Object;", 4, flags, body))))
    }

    private val folded = "Lfixture/FoldedLockLambda;"

    private fun BytecodePatchContext.foldedInvoke(): Method = mutableClassDefBy(folded).methods.single { it.name == "invoke" }

    private val navigator = "Lfixture/LongPressNavigator;"
    private val navigatorOwner = "Lfixture/Owner;"

    private fun BytecodePatchContext.navigatorMethod(name: String): Method = mutableClassDefBy(navigator).methods.single { it.name == name }

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

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}
