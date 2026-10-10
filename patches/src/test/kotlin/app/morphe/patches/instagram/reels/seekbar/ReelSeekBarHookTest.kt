/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.seekbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelSeekBarHookTest {
    /** The hooks the patch writes are in the ReelSeekBar the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(MIN_SECONDS, LAZY, PROGRESS, BIND)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Three reads of the minimum, two shared with the ads' minimum, one of the hidden kind, shared too, and the bar's listener. */
    @Test
    fun everyReadIsFoundAndCheckedAgainstTheMarkers() {
        val sites = context().findReelSeekBarSites()

        assertEquals(ORGANIC_MIN_SECONDS_READS, sites.lengths.size)
        assertEquals(setOf(UTIL, PROGRESS_STATE), sites.lengths.map { it.type }.toSet())
        assertEquals(listOf(false, true, true), sites.lengths.map { it.shared }.sorted())
        assertEquals(ROW, sites.lazy.type)
        assertTrue("the hidden kind's read is the ads' too", sites.lazy.shared)
        assertEquals(SEEK_BAR, sites.progress.definingClass)
        assertEquals(BINDER, sites.bind.method.definingClass)
        assertEquals("the container", sites.bind.method.parameterRegisterNumber(1), sites.bind.view)
    }

    /**
     * In both of the util's checks the ordinary reel's branch reads the minimum on its own and
     * answers it through the extension, then goes on after the shared read; the ad's branch still
     * lands on the shared read, which has no hook. The progress controller's own read is answered
     * in place, and the bar's listener hands the extension the bar and its position first.
     */
    @Test
    fun onlyTheOrdinaryReelsBranchIsAnswered() {
        val context = context()

        context.applyReelSeekBar(context.findReelSeekBarSites())

        assertAnsweredOnItsOwn("A0L", context.method(UTIL, "A0L"), ORGANIC_MIN_SECONDS, AD_MIN_SECONDS, MIN_SECONDS)
        assertAnsweredOnItsOwn("A0M", context.method(UTIL, "A0M"), ORGANIC_MIN_SECONDS, AD_MIN_SECONDS, MIN_SECONDS)
        assertAnsweredOnItsOwn("progress state", context.method(PROGRESS_STATE, "A00"), ORGANIC_MIN_SECONDS, null, MIN_SECONDS)
        assertAnsweredOnItsOwn("scrubber row", context.method(ROW, "A00"), ORGANIC_LAZY, AD_LAZY, LAZY)
        assertProgressHookedFirst("stand-in", context.method(SEEK_BAR, PROGRESS_CHANGED))
        assertBindHookedAfterTheAdRead("stand-in", context.method(BINDER, "invoke"), context.method(ROW, "A00"))
    }

    /**
     * Without a binder that can be tied to the seek bar and to the row's own ad answer, the label
     * couldn't tell an ordinary reel's bar from an ad's or another screen's, so the patch stops
     * before anything changes.
     */
    @Test
    fun aBinderThatCantBeTiedToAnOrdinaryReelFailsBeforeAnythingChanges() {
        fun binderWith(binder: ClassDef) = standIns().map { if (it.type == BINDER) binder else it }
        fun rowWith(row: ClassDef) = standIns().map { if (it.type == ROW) row else it }
        val onlyWhenFalse = "doesn't read its ordinary reel's hidden kind only when $AD_FIELD is false"
        val cases = listOf(
            Triple("no binder", standIns().filter { it.type != BINDER }, "tagging a view $SCRUBBER_TAG, found 0"),
            Triple("two binders", standIns() + binder(type = "Lfixture/OtherBinder;"), "tagging a view $SCRUBBER_TAG, found 2"),
            Triple(
                "the binder reads another boolean of the item",
                binderWith(binder(adRead = "iget-boolean v0, v0, $ITEM_TYPE->A18:Z")), "to read $AD_FIELD once, found 0",
            ),
            Triple(
                "the binder reads the ad answer twice",
                binderWith(binder(between = "iget-boolean v0, v0, $AD_FIELD")), "to read $AD_FIELD once, found 2",
            ),
            Triple(
                "something jumps to just after the binder's read",
                binderWith(binder(head = "if-nez p1, :after", between = ":after\nnop")), "jumps to just after its read of $AD_FIELD",
            ),
            Triple("the binder writes over its container", binderWith(binder(between = "move-object p2, v1")), "writes over the view it tags"),
            Triple("the binder tags a view of its own", binderWith(binder(tagged = "v1")), "tags a view of its own"),
            Triple(
                "the binder isn't the seek bar primitive's",
                standIns().map { if (it.type == PRIMITIVE) primitive(returns = "Landroid/view/View;") else it },
                "reads no field of the primitive that makes $SEEK_BAR",
            ),
            Triple("the row reads no boolean of its item", rowWith(row(adRead = "const/4 v5, 0x0")), "to read one boolean of $ITEM_TYPE, found 0"),
            Triple(
                "the row reads two booleans of its item",
                rowWith(row(adRead = "iget-boolean v5, p1, $AD_FIELD\niget-boolean v4, p1, $ITEM_TYPE->A18:Z")),
                "to read one boolean of $ITEM_TYPE, found 2",
            ),
            Triple("the row reads the ordinary reel's flag when the field is true", rowWith(row(adTest = "if-nez v5, :ordinary")), onlyWhenFalse),
            Triple("the row decides by something else", rowWith(row(adTest = "if-eqz p3, :ordinary")), onlyWhenFalse),
            Triple(
                "the row writes over the field before testing it",
                rowWith(row(adRead = "iget-boolean v5, p1, $AD_FIELD\nconst/4 v5, 0x0")), "writes over $AD_FIELD before testing it",
            ),
        )
        for ((what, classes, reason) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains(reason))
            assertNothingWritten(what, context)
        }
    }

    /**
     * An own read before a shared read in one method: the writes are planned on the method as it
     * was found and made last first, so neither moves the other's place.
     */
    @Test
    fun twoReadsInOneMethodKeepTheirPlaces() {
        val mixed = classOf(
            MIXED,
            method(
                MIXED, "A00", listOf("Z"), "J", 9,
                """
                    invoke-static {}, $CONFIGS->A00()Ljava/lang/Object;
                    move-result-object v4
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    invoke-static {v4, v0, v1}, $WRAPPER->A08(Ljava/lang/Object;J)J
                    move-result-wide v2
                    if-nez p0, :ordinary
                    const-wide v0, $AD_MIN_SECONDS_HEX
                    :read
                    check-cast v4, $CONFIG
                    invoke-interface {v4, v0, v1}, $CONFIG->CbE(J)J
                    move-result-wide v5
                    add-long/2addr v5, v2
                    return-wide v5
                    :ordinary
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    goto :read
                """,
            ),
        )
        val context = PatchContexts.of(listOf(mixed))
        val loads = context.findFlagLoads("test", ORGANIC_MIN_SECONDS, "J")
        assertEquals(listOf(false, true), loads.map { it.shared })

        context.answerFlagLoads(loads.map { it to MIN_SECONDS })

        val method = context.method(MIXED, "A00")
        val code = method.implementation!!.instructions.toList()
        assertEquals("hooks", 2, code.count { it.calls(MIN_SECONDS) })
        val own = code.indexOfFirst { it.isLoadOf(ORGANIC_MIN_SECONDS) }
        assertTrue("the own read answers in place", code[own + 3].calls(MIN_SECONDS))
        val ordinary = code.indexOfLast { it.isLoadOf(ORGANIC_MIN_SECONDS) }
        assertAnsweredOnItsOwn("mixed", method, ORGANIC_MIN_SECONDS, AD_MIN_SECONDS, MIN_SECONDS, ordinary)
    }

    /** A fourth read, or one fewer, is an update the patch hasn't seen, and nothing is written. */
    @Test
    fun anotherCountOfMinimumReadsFailsBeforeAnythingChanges() {
        val extra = classOf(
            EXTRA,
            method(
                EXTRA, "A00", emptyList(), "J", 4,
                """
                    invoke-static {}, $CONFIGS->A00()Ljava/lang/Object;
                    move-result-object v2
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    invoke-static {v2, v0, v1}, $WRAPPER->A08(Ljava/lang/Object;J)J
                    move-result-wide v0
                    return-wide v0
                """,
            ),
        )
        for ((what, classes) in listOf("four reads" to standIns() + extra, "two reads" to standIns().filter { it.type != PROGRESS_STATE })) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains("expected 3 reads of the shortest reel"))
            assertNothingWritten(what, context)
        }
    }

    /** No read of the hidden kind, or two, fails before anything changes. */
    @Test
    fun anotherCountOfHiddenKindReadsFailsBeforeAnythingChanges() {
        val second = classOf(
            EXTRA,
            method(
                EXTRA, "A00", emptyList(), "Z", 4,
                """
                    invoke-static {}, $CONFIGS->A00()Ljava/lang/Object;
                    move-result-object v2
                    const-wide v0, $ORGANIC_LAZY_HEX
                    check-cast v2, $CONFIG
                    invoke-interface {v2, v0, v1}, $CONFIG->BXd(J)Z
                    move-result v0
                    return v0
                """,
            ),
        )
        val cases = listOf(
            "two reads" to standIns() + second,
            "none" to standIns().map { if (it.type == ROW) row(lazyFlag = 0x81092d001033cbL) else it },
        )
        for ((what, classes) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains("expected 1 read of the hidden seek bar flag"))
            assertNothingWritten(what, context)
        }
    }

    /** Without the markers that tie the reads to the seek bar, they could be anything's, so the patch stops. */
    @Test
    fun aMissingMarkerFailsBeforeAnythingChanges() {
        val cases = listOf(
            "no shouldShowAttachedScrubber" to standIns().map { if (it.type == UTIL) util(marker = "ClipsExperimentUtil_shouldShowSomethingElse") else it },
            "no calculateShouldShowAttachedScrubber" to standIns().filter { it.type != ITEM },
            "no getUiState" to standIns().map { if (it.type == ROW) row(marker = "ClipsScrubberRowUseCase_getOther") else it },
            "calculate asks another method" to standIns().map { if (it.type == ITEM) item(calls = "A0K") else it },
            "the row asks no reader of the minimum" to standIns().map { if (it.type == ROW) row(calls = "A0K") else it },
        )
        for ((what, classes) in cases) {
            val context = PatchContexts.of(classes)
            assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertNothingWritten(what, context)
        }
    }

    @Test
    fun noSeekBarClassFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(standIns().filter { it.type != SEEK_BAR })
        val refusal = assertThrows(PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
        assertTrue(refusal.message, refusal.message!!.contains("has no $SEEK_BAR"))
        assertNothingWritten("no seek bar", context)
    }

    /** Two methods named onProgressChanged leave the patch unsure which is the listener's. */
    @Test
    fun anAmbiguousOnProgressChangedFailsBeforeAnythingChanges() {
        val overload = method(
            SEEK_BAR, PROGRESS_CHANGED, listOf("Landroid/widget/SeekBar;", "I"), "V", 3, "return-void", static = false,
        )
        val context = PatchContexts.of(standIns().map { if (it.type == SEEK_BAR) seekBar(overload) else it })
        val refusal = assertThrows(PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
        assertTrue(refusal.message, refusal.message!!.contains("expected one $PROGRESS_CHANGED"))
        assertNothingWritten("two listeners", context)
    }

    /** A class of that name that isn't a SeekBar would fail verification with the bar handed over as one. */
    @Test
    fun aSeekBarThatIsNoSeekBarFailsThePatch() {
        val context = PatchContexts.of(standIns().map { if (it.type == APPCOMPAT_SEEK_BAR) classOf(APPCOMPAT_SEEK_BAR, superclass = "Landroid/widget/ProgressBar;") else it })
        assertThrows(PatchException::class.java) { context.findReelSeekBarSites() }
    }

    /** A load that goes nowhere near a read is an update the patch hasn't seen. */
    @Test
    fun aLoadFeedingNoReadFailsThePatch() {
        val stray = classOf(
            EXTRA,
            method(
                EXTRA, "A00", emptyList(), "J", 2,
                """
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    return-wide v0
                """,
            ),
        )
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(stray)).findFlagLoads("test", ORGANIC_MIN_SECONDS, "J") }
    }

    /**
     * In each declared build every read is found and checked against the markers, and after the
     * patch each ordinary reel's branch answers through the extension while the ads' reads stay as
     * they were. On 449 that's the minimum in ClipsExperimentUtil's two checks (shared with the ads'
     * minimum) and in the progress controller's limits (its own), the hidden kind in the scrubber
     * row's state (shared with the ads' flag), and VideoScrubberSeekBar.onProgressChanged.
     */
    @Test
    fun eachDeclaredBuildKeepsTheSeekBar() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type == SEEK_BAR || classDef.type == APPCOMPAT_SEEK_BAR ||
                            classDef.methods.any {
                                it.loadsAny(ORGANIC_MIN_SECONDS, ORGANIC_LAZY) || it.holdsMarker() ||
                                    it.holdsString(SCRUBBER_TAG) || it.returnType == SEEK_BAR
                            }
                        ) {
                            holders += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(holders)

                val sites = context.findReelSeekBarSites()
                assertEquals("${bundle.name}: reads of the minimum", 3, sites.lengths.size)
                assertEquals("${bundle.name}: shared reads of the minimum", 2, sites.lengths.count { it.shared })
                assertTrue("${bundle.name}: the hidden kind's read is shared", sites.lazy.shared)
                context.applyReelSeekBar(sites)

                for (load in sites.lengths) {
                    assertAnsweredOnItsOwn(
                        "${bundle.name} ${load.type}->${load.name}", context.method(load),
                        ORGANIC_MIN_SECONDS, if (load.shared) AD_MIN_SECONDS else null, MIN_SECONDS,
                    )
                }
                assertAnsweredOnItsOwn(
                    "${bundle.name} ${sites.lazy.type}->${sites.lazy.name}", context.method(sites.lazy),
                    ORGANIC_LAZY, AD_LAZY, LAZY,
                )
                assertProgressHookedFirst(bundle.name, context.method(SEEK_BAR, PROGRESS_CHANGED))
                assertBindHookedAfterTheAdRead(bundle.name, context.method(sites.bind.method), context.method(sites.lazy))
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun Method.loadsAny(vararg flags: Long): Boolean = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in flags
    } == true

    private fun Method.holdsMarker(): Boolean = implementation?.instructions?.any { instruction ->
        val string = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        string != null && string.startsWith("android_purge_") &&
            listOf(SHOULD_SHOW_ATTACHED, CALCULATE_SHOULD_SHOW, SCRUBBER_ROW_STATE).any { string.endsWith("_$it") }
    } == true

    private fun Method.holdsString(text: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
    } == true

    private fun BytecodePatchContext.method(type: String, name: String): Method = mutableClassDefBy(type).methods.single { it.name == name }

    private fun BytecodePatchContext.method(method: Method): Method = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

    private fun BytecodePatchContext.method(load: FlagLoad): Method = mutableClassDefBy(load.type).methods.single {
        it.name == load.name && it.parameterTypes.map(CharSequence::toString) == load.parameters
    }

    /**
     * From the load of [flag] (the one at [loadAt], or the method's only one): a cast if the read
     * has one, the read, its move-result, the call to [hook] with that register and the answer back
     * in it. With [other], the read is shared, so a goto follows that lands just past [other]'s own
     * read, and that read has no hook after it. The method calls [hook] once, or as many times as it
     * loads [flag].
     */
    private fun assertAnsweredOnItsOwn(what: String, method: Method, flag: Long, other: Long?, hook: String, loadAt: Int? = null) {
        val flow = ControlFlow.of(method)
        val code = flow.instructions
        val load = loadAt ?: code.indices.single { code[it].isLoadOf(flag) }
        val id = (code[load] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", code.count { it.isLoadOf(flag) }, code.count { it.calls(hook) })
        var at = load + 1
        if (code[at].opcode == Opcode.CHECK_CAST) at++
        val read = code[at]
        assertTrue("$what: the read after the load is ${read.opcode}", read.reference() is MethodReference)
        assertEquals("$what: the read takes the id last", listOf(id, id + 1), read.arguments().takeLast(2))
        val wide = (read.reference() as MethodReference).returnType == "J"
        val moveResult = if (wide) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT
        assertEquals("$what: the read's answer", moveResult, code[at + 1].opcode)
        val register = (code[at + 1] as OneRegisterInstruction).registerA
        val call = code[at + 2]
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertTrue("$what: the hook", call.calls(hook))
        assertEquals("$what: the hook's register", register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the hook's width", if (wide) 2 else 1, call.registerCount)
        assertEquals("$what: the answer", moveResult, code[at + 3].opcode)
        assertEquals("$what: the answer's register", register, (code[at + 3] as OneRegisterInstruction).registerA)
        if (other == null) {
            assertFalse("$what: an own read jumps away", code[at + 4].opcode in GOTOS)
            return
        }
        assertTrue("$what: the own read goes back with a goto, not ${code[at + 4].opcode}", code[at + 4].opcode in GOTOS)
        val landing = flow.normal[at + 4].single()
        val otherLoad = code.indices.single { code[it].isLoadOf(other) }
        var shared = flow.normal[otherLoad].single()
        while (code[shared].opcode in GOTOS || code[shared].opcode == Opcode.CHECK_CAST) shared = flow.normal[shared].single()
        assertTrue("$what: the shared read", code[shared].reference() is MethodReference)
        assertEquals("$what: the own read lands past the shared one", shared + 2, landing)
        assertFalse("$what: the shared read is answered too", code[shared + 2].calls(hook))
    }

    /**
     * The binder calls [BIND] once, right after its one read of the boolean of the reel item that
     * [row] decides between the ordinary reel's flag and the ad's by, with the container it's
     * handed (its second argument) and the register the read went to.
     */
    private fun assertBindHookedAfterTheAdRead(what: String, binder: Method, row: Method) {
        val item = row.parameterTypes[1].toString()
        val rowField = row.implementation!!.instructions.single {
            it.opcode == Opcode.IGET_BOOLEAN && (it.reference() as FieldReference).definingClass == item
        }.reference().toString()
        val code = binder.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { it.calls(BIND) })
        val call = code.indexOfFirst { it.calls(BIND) }
        val read = code[call - 1]
        assertEquals("$what: the read before the hook", Opcode.IGET_BOOLEAN, read.opcode)
        assertEquals("$what: the field the row decides by", rowField, read.reference().toString())
        assertEquals("$what: reads of the field", 1, code.count { it.reference()?.toString() == rowField })
        assertEquals(
            "$what: the container and the answer",
            listOf(binder.parameterRegisterNumber(1), (read as OneRegisterInstruction).registerA),
            code[call].arguments(),
        )
    }

    /** The bar's listener calls [PROGRESS] first, with `this` and the position, and only there. */
    private fun assertProgressHookedFirst(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { it.calls(PROGRESS) })
        val first = code.first()
        assertEquals("$what: the first instruction", Opcode.INVOKE_STATIC, first.opcode)
        assertTrue("$what: the first instruction", first.calls(PROGRESS))
        assertEquals("$what: this and the position", listOf(method.localRegisterCount(), method.parameterRegisterNumber(1)), first.arguments())
    }

    private fun assertNothingWritten(what: String, context: BytecodePatchContext) {
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                assertFalse(
                    "$what: ${classDef.type}->${method.name} was changed",
                    method.implementation?.instructions?.any { it.calls(REEL_SEEK_BAR) } == true,
                )
            }
        }
    }

    private fun Instruction.isLoadOf(flag: Long) = opcode == Opcode.CONST_WIDE && (this as WideLiteralInstruction).wideLiteral == flag

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.calls(hook: String) = (reference() as? MethodReference)?.toString()?.startsWith(hook) == true

    private fun Instruction.arguments(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private companion object {
        const val UTIL = "Lfixture/ClipsExperimentUtil;"
        const val PROGRESS_STATE = "Lfixture/ClipsProgressState;"
        const val ROW = "Lfixture/ClipsScrubberRow;"
        const val ITEM = "Lfixture/ClipsItemUseCase;"
        const val MIXED = "Lfixture/Mixed;"
        const val EXTRA = "Lfixture/Extra;"
        const val CONFIGS = "Lfixture/Configs;"
        const val WRAPPER = "Lfixture/MobileConfigs;"
        const val MODE = "Lfixture/ScrubberMode;"
        const val CONFIG = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val APPCOMPAT_SEEK_BAR = "Landroidx/appcompat/widget/AppCompatSeekBar;"
        const val ITEM_TYPE = "Lfixture/ClipsItem;"
        const val BINDER = "Lfixture/ScrubberBinder;"
        const val PRIMITIVE = "Lfixture/ScrubberPrimitive;"

        /** The reel item's field saying it's an ad, which the row and the binder read. */
        const val AD_FIELD = "$ITEM_TYPE->A17:Z"

        /** The ads' minimum and hidden kind, read in the same places as the ordinary reels'. */
        const val AD_MIN_SECONDS = 0x82092100001495L
        const val AD_LAZY = 0x8109210015339dL

        val ORGANIC_MIN_SECONDS_HEX = "0x${ORGANIC_MIN_SECONDS.toString(16)}L"
        val AD_MIN_SECONDS_HEX = "0x${AD_MIN_SECONDS.toString(16)}L"
        val ORGANIC_LAZY_HEX = "0x${ORGANIC_LAZY.toString(16)}L"
        val AD_LAZY_HEX = "0x${AD_LAZY.toString(16)}L"

        val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

        fun context(): BytecodePatchContext = PatchContexts.of(standIns())

        fun standIns(): List<ClassDef> = listOf(
            util(), progressState(), row(), item(), seekBar(), classOf(APPCOMPAT_SEEK_BAR, superclass = "Landroid/widget/SeekBar;"),
            binder(), primitive(),
        )

        fun method(
            type: String,
            name: String,
            parameters: List<String>,
            returnType: String,
            registers: Int,
            body: String,
            static: Boolean = true,
        ): Method = MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
                null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

        fun classOf(type: String, vararg methods: Method, superclass: String = "Ljava/lang/Object;"): ClassDef =
            ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null,
                methods.toList(),
            )

        /**
         * Shaped like 449's ClipsExperimentUtil: A0L, the hidden-kind check, and A0M, the attached
         * bar check under its marker. Each picks the ordinary reel's minimum or the ad's in a
         * branch and reads whichever it picked in one place.
         */
        fun util(marker: String = SHOULD_SHOW_ATTACHED): ClassDef = classOf(
            UTIL,
            method(
                UTIL, "A0L", listOf(SESSION, "J", "Z"), "Z", 9,
                """
                    const/4 v4, 0x0
                    invoke-static {p0, p1, p2, p3}, $UTIL->A0K(${SESSION}JZ)Z
                    move-result v0
                    if-eqz v0, :none
                    const/4 v3, 0x1
                    xor-int/lit8 v0, p3, 0x1
                    invoke-static {p0}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v2
                    if-eqz v0, :ad
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    :read
                    check-cast v2, $CONFIG
                    invoke-interface {v2, v0, v1}, $CONFIG->CbE(J)J
                    move-result-wide v1
                    cmp-long v0, p1, v1
                    if-gez v0, :none
                    return v3
                    :ad
                    const-wide v0, $AD_MIN_SECONDS_HEX
                    goto :read
                    :none
                    return v4
                """,
            ),
            method(
                UTIL, "A0M", listOf(SESSION, "J", "Z", "Z"), "Z", 10,
                """
                    const-string v0, "android_purge_26_q2_$marker"
                    invoke-static {v0}, $CONFIGS->trace(Ljava/lang/String;)V
                    const/4 v4, 0x1
                    const/4 v3, 0x0
                    invoke-static {p0}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v2
                    if-eqz p3, :ad
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    :read
                    check-cast v2, $CONFIG
                    invoke-interface {v2, v0, v1}, $CONFIG->CbE(J)J
                    move-result-wide v1
                    cmp-long v0, p1, v1
                    if-gez v0, :yes
                    return v3
                    :yes
                    return v4
                    :ad
                    const-wide v0, $AD_MIN_SECONDS_HEX
                    goto :read
                """,
            ),
        )

        /** Shaped like 449's progress controller limits: the minimum read on its own, through a static wrapper. */
        fun progressState(): ClassDef = classOf(
            PROGRESS_STATE,
            method(
                PROGRESS_STATE, "A00", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;", 8,
                """
                    invoke-static {p0}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v3
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    invoke-static {v3, v0, v1}, $WRAPPER->A08(Ljava/lang/Object;J)J
                    move-result-wide v5
                    return-object v3
                """,
            ),
        )

        /**
         * Shaped like 449's scrubber row state under its marker: it reads whether the reel item is
         * an ad, asks A0L with that, then reads the ad's hidden-kind flag or, when the item isn't an
         * ad, after a goto, the ordinary reel's, in one place. [adRead] reads the item's field and
         * [adTest] tests it on the way to the two flags.
         */
        fun row(
            marker: String = SCRUBBER_ROW_STATE,
            calls: String = "A0L",
            lazyFlag: Long = ORGANIC_LAZY,
            adRead: String = "iget-boolean v5, p1, $AD_FIELD",
            adTest: String = "if-eqz v5, :ordinary",
        ): ClassDef = classOf(
            ROW,
            method(
                ROW, "A00", listOf("Lcom/instagram/clips/intf/ClipsViewerConfig;", ITEM_TYPE, SESSION, "Z"),
                "Ljava/lang/Integer;", 11,
                """
                    const-string v0, "android_purge_26_q3_$marker"
                    invoke-static {v0}, $CONFIGS->trace(Ljava/lang/String;)V
                    $adRead
                    const-wide/16 v0, 0x5
                    invoke-static {p2, v0, v1, v5}, $UTIL->$calls(${SESSION}JZ)Z
                    move-result v0
                    if-eqz v0, :plain
                    invoke-static {p2}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v6
                    $adTest
                    const-wide v0, $AD_LAZY_HEX
                    :read
                    check-cast v6, $CONFIG
                    invoke-interface {v6, v0, v1}, $CONFIG->BXd(J)Z
                    move-result v0
                    if-eqz v0, :plain
                    sget-object v2, $MODE->LAZY:Ljava/lang/Integer;
                    return-object v2
                    :ordinary
                    const-wide v0, 0x${lazyFlag.toString(16)}L
                    goto :read
                    :plain
                    sget-object v2, $MODE->ATTACHED:Ljava/lang/Integer;
                    return-object v2
                """,
            ),
        )

        /**
         * Shaped like 449's seek bar container binder (063c.invoke): handed the container as its
         * second argument, it tags it clips_scrubber_ and the reel's id, then reads whether the
         * primitive's reel item is an ad and marks the container either way.
         */
        fun binder(
            type: String = BINDER,
            head: String = "",
            adRead: String = "iget-boolean v0, v0, $AD_FIELD",
            between: String = "",
            tagged: String = "p2",
        ): ClassDef = classOf(
            type,
            method(
                type, "invoke", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;", 6,
                """
                    check-cast p2, Landroid/view/ViewGroup;
                    const/4 v2, 0x0
                    $head
                    iget-boolean v0, p0, $type->A01:Z
                    if-eqz v0, :untagged
                    iget-object v1, p0, $type->A00:$PRIMITIVE
                    iget-object v0, v1, $PRIMITIVE->A04:$ITEM_TYPE
                    invoke-virtual {v0}, $ITEM_TYPE->getId()Ljava/lang/String;
                    move-result-object v1
                    const-string v0, "$SCRUBBER_TAG"
                    invoke-static {v0, v1}, $CONFIGS->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    invoke-virtual {$tagged, v0}, Landroid/view/View;->setTag(Ljava/lang/Object;)V
                    :untagged
                    iget-object v1, p0, $type->A00:$PRIMITIVE
                    iget-object v0, v1, $PRIMITIVE->A04:$ITEM_TYPE
                    $adRead
                    $between
                    if-eqz v0, :organic
                    const v0, 0x7f0b2ea1
                    invoke-virtual {p2, v0, p1}, Landroid/view/View;->setTag(ILjava/lang/Object;)V
                    :done
                    return-object v2
                    :organic
                    const v0, 0x7f0b2ea1
                    invoke-virtual {p2, v0, v2}, Landroid/view/View;->setTag(ILjava/lang/Object;)V
                    goto :done
                """,
                static = false,
            ),
        )

        /** Shaped like 449's ScrubberSeekbarPrimitive: the class with the method that makes the bar. */
        fun primitive(returns: String = SEEK_BAR): ClassDef = classOf(
            PRIMITIVE,
            method(
                PRIMITIVE, "A01", listOf("Landroid/content/Context;"), returns, 2,
                """
                    const/4 v0, 0x0
                    return-object v0
                """,
            ),
        )

        /** Shaped like 449's calculateShouldShowAttachedScrubber: its marker and a call to A0M. */
        fun item(calls: String = "A0M"): ClassDef = classOf(
            ITEM,
            method(
                ITEM, "A00", listOf(SESSION, "J"), "Z", 5,
                """
                    const-string v0, "android_purge_26_q2_$CALCULATE_SHOULD_SHOW"
                    invoke-static {v0}, $CONFIGS->trace(Ljava/lang/String;)V
                    const/4 v0, 0x1
                    invoke-static {p0, p1, p2, v0, v0}, $UTIL->$calls(${SESSION}JZZ)Z
                    move-result v0
                    return v0
                """,
            ),
        )

        /** Shaped like 449's VideoScrubberSeekBar.onProgressChanged: forward to the stored listener, if any. */
        fun seekBar(vararg more: Method): ClassDef = classOf(
            SEEK_BAR,
            method(
                SEEK_BAR, PROGRESS_CHANGED, listOf("Landroid/widget/SeekBar;", "I", "Z"), "V", 6,
                """
                    iget-object v0, p0, $SEEK_BAR->A00:Ljava/lang/Object;
                    if-eqz v0, :tail
                    invoke-virtual {v0}, Ljava/lang/Object;->hashCode()I
                    :tail
                    return-void
                """,
                static = false,
            ),
            *more,
            superclass = APPCOMPAT_SEEK_BAR,
        )
    }
}
