/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.autoscroll

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.instagram.FixtureDex
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The markers, after Instagram's release prefix, of 449's two methods reading the memory besides the check. */
private const val PLAYBACK_STATE = "VideoPlaybackUseCase_getUiState"
private const val PIP_ON_CREATE = "ClipsPiPFragment_onCreate"

class KeepReelsAutoScrollHookTest {
    private val plugin = "Lfixture/AutoscrollPlugin;"
    private val preference = "Lfixture/AutoscrollPreference;"
    private val viewer = "Lfixture/ViewerFragment;"
    private val tabAction = "Lfixture/TabAction;"
    private val prefs = "Lfixture/Prefs;"
    private val memoryClass = "Lfixture/AutoscrollManager;"
    private val memory = "$memoryClass->on:Z"
    private val useCase = "Lfixture/PlaybackUseCase;"
    private val pip = "Lfixture/PipFragment;"
    private val lifecycle = "Lfixture/ManagerLifecycle;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val timer = "Lfixture/AutoscrollTimer;"
    private val duration = "Lfixture/DurationChoice;"
    private val deadlineDescriptor = "Lfixture/LongPreference;"
    private val property = "Lfixture/Property;"
    private val expirationKey = "preference_clips_auto_scroll_expiration_timestamp"
    private val durationMarker = "ClipsOptInAutoscrollPluginImpl_logDurationTap"
    private val timerHook = "$REEL_AUTO_SCROLL->timerSet(J)V"
    private val timerSetter = "$timer->expires($prefs" + "J)V"
    private val clickParameters = listOf(
        "Landroidx/fragment/app/FragmentActivity;", session, "Lfixture/Logger;", "Lkotlin/jvm/functions/Function0;", "I", "J", "Z",
    )

    /**
     * What the stand-in handler saves: its choice copied once, as 449 does, or twice; a constant;
     * the copy flipped; the copy written over on one arm of a branch; or the choice's own register
     * written over before it's copied.
     */
    private val choiceToSave = mapOf(
        "move" to listOf("move/from16 v1, p8"),
        "copiedTwice" to listOf("move/from16 v2, p8", "move v1, v2"),
        "constant" to listOf("const/4 v1, 0x1"),
        "flipped" to listOf("move/from16 v1, p8", "xor-int/lit8 v1, v1, 0x1"),
        "overwrittenOnABranch" to listOf("move/from16 v1, p8", "if-nez v0, :keep", "const/4 v1, 0x1", ":keep"),
        "parameterOverwritten" to listOf("const/16 p8, 0x1", "move/from16 v1, p8"),
    )
    private val hooks = setOf(AUTO_SCROLL_ANSWER, AUTO_SCROLL_SAVED, AUTO_SCROLL_CHOSEN, AUTO_SCROLL_STORED, timerHook)

    /** How the stand-in handler keeps its choice in memory: as it is, not at all, twice, or a constant in its place. */
    private val choiceToKeep = mapOf(
        "choice" to listOf("iput-boolean v1, v2, $memory"),
        "none" to emptyList(),
        "twice" to listOf("iput-boolean v1, v2, $memory", "iput-boolean v1, v2, $memory"),
        "constant" to listOf("const/4 v3, 0x1", "iput-boolean v3, v2, $memory"),
    )

    /** The hooks the patch writes are in the ReelAutoScroll the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in hooks) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * Every return of the check and of the getter is answered, a branch straight to a return
     * included; the handler hands its choice over first thing, from a parameter above v15, and
     * again right after it keeps it in memory and saves it; every read of the memory outside the
     * check is answered right where it's read, and a branch past a read doesn't land on its answer;
     * the long-press action hands its choice over right after it saves it; and the status is on.
     */
    @Test
    fun everyAnswerAndEveryChoiceGoesThroughTheExtension() {
        val context = PatchContexts.of(classes() + ExtensionDex.classDef(SETTINGS_STATUS))

        keepReelsAutoScrollPatch.execute(context)

        val check = context.method(plugin, "check")
        assertEveryReturnAnswered("the check", check, returns = 3)
        val code = check.code()
        val off = code.indices.filter { code[it].opcode == Opcode.RETURN }[1]
        assertEquals("the branch to the second return lands on its answer", off - 2, check.targetOf(4))
        assertEveryReturnAnswered("the getter", context.method(preference, "enabled"), returns = 1, filter = AUTO_SCROLL_SAVED)

        val click = context.method(plugin, "handle")
        assertEquals("the stand-in handler's choice is in v28", 28, click.implementation!!.registerCount - 1)
        assertChoiceFirst("the stand-in", click)
        assertChoiceKept("the stand-in", click, "$preference->setEnabled($prefs" + "Z)V", memory)
        assertChoiceAfterSave("the stand-in", context.method(tabAction, "run"), "$preference->setEnabled($prefs" + "Z)V")
        val state = context.method(useCase, "uiState")
        assertMemoryReadsAnswered("the playback state", state, memory, reads = 1)
        val read = state.code().indexOfFirst { it.referenceText() == memory }
        assertEquals("the branch past the read still lands on the instruction after it", read + 3, state.targetOf(read + 5))
        assertMemoryReadsAnswered("picture in picture", context.method(pip, "onCreate"), memory, reads = 2)
        assertEquals("nothing is added where Instagram resets the memory", 0,
            context.method(lifecycle, "onActivityCreated").code().count { it.referenceText() in hooks })
        assertEquals("the memory's getter isn't changed", 0, context.method(memoryClass, "instance").code().count { it.referenceText() in hooks })
        assertEquals("nothing else calls chosen", 0, context.method(viewer, "onPause").code().count { it.referenceText() == AUTO_SCROLL_CHOSEN })

        val status = context.method(SETTINGS_STATUS, "reelAutoScroll").code()
        assertEquals("SettingsStatus.reelAutoScroll() isn't switched on", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /**
     * The handler's choice may reach the setter through more than one plain move, and the hook
     * still reads it first thing from the handler's own parameter.
     */
    @Test
    fun aChoiceCopiedTwiceIsStillTheChoiceSaved() {
        val context = PatchContexts.of(classes(clickSavesChoice = "copiedTwice") + ExtensionDex.classDef(SETTINGS_STATUS))

        keepReelsAutoScrollPatch.execute(context)

        assertChoiceFirst("copied twice", context.method(plugin, "handle"))
    }

    /** The completed duration choices pass the exact native timestamp after the native save. */
    @Test
    fun everyDurationChoiceIsRememberedAfterItIsSaved() {
        val context = PatchContexts.of(classes() + ExtensionDex.classDef(SETTINGS_STATUS))
        keepReelsAutoScrollPatch.execute(context)
        assertTimerChoices(context.method(duration, "invoke"))
        assertEquals("the expiration setter itself is unchanged", 7, context.method(timer, "expires").code().size)
        assertEquals("the handler's zero reset is not hooked", 0, context.method(plugin, "handle").code().count { it.referenceText() == timerHook })
        assertEquals("the handler still performs its native zero reset", 1, context.method(plugin, "handle").code().count { it.referenceText() == timerSetter })
        assertEquals("the cancellation callback remains stock", 0, context.method("Lfixture/DurationCancel;", "invoke").code().count { it.referenceText() in hooks })
    }

    /** A timer role or timestamp with no proof must fail before any of the other hooks are written. */
    @Test
    fun anUnprovedTimerFailsBeforeAnythingChanges() {
        val fixtures = listOf(
            classes(timerKey = "unrelated_expiration_timestamp"),
            classes(durationMillis = -1),
            classes(jumpIntoDurationSave = true),
            classes(durationCapturedPrefs = false),
            classes(durationConstructed = false),
            classes(timerKeepsDeadline = false),
            classes(durationOverwritesPrefs = true),
            classes(durationOverwritesReceiver = true),
        )
        for (classes in fixtures) {
            val context = PatchContexts.of(classes + ExtensionDex.classDef(SETTINGS_STATUS))
            assertThrows(PatchException::class.java) { keepReelsAutoScrollPatch.execute(context) }
            for (type in classes) for (method in type.methods) {
                val after = context.method(method)
                assertEquals("${method.text()} registers changed", method.implementation!!.registerCount, after.implementation!!.registerCount)
                assertEquals("${method.text()} changed", method.code().map { Triple(it.opcode, it.referenceText(), it.arguments()) },
                    after.code().map { Triple(it.opcode, it.referenceText(), it.arguments()) })
            }
        }
    }

    private fun assertTimerChoices(method: Method, setter: String = timerSetter) {
        val code = method.code()
        val saves = code.indices.filter { code[it].referenceText() == setter }
        assertEquals("the three duration choices save a timestamp", 3, saves.size)
        assertEquals("one hook per completed duration choice", 3, code.count { it.referenceText() == timerHook })
        for (at in saves) {
            assertEquals("remember after the native save at $at", timerHook, code[at + 1].referenceText())
            assertEquals("remember exactly the saved timestamp at $at", code[at].arguments().drop(1), code[at + 1].arguments())
        }
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val oneMarked = "expected one method marked"
        val callers = "expected $preference->setEnabled($prefs" + "Z)V to be called by"
        val notTheChoice = "saves something other than its choice, parameter 6 (v28), to $preference->setEnabled($prefs" + "Z)V"
        val cases = listOf(
            classes(checks = 0) to "$oneMarked $IS_AUTOSCROLL_ACTIVE, found 0",
            classes(checks = 2) to "$oneMarked $IS_AUTOSCROLL_ACTIVE, found 2",
            classes(checkMarker = "android_purge_26_q3_${IS_AUTOSCROLL_ACTIVE}V2") to "$oneMarked $IS_AUTOSCROLL_ACTIVE, found 0",
            classes(checkAnswers = "I") to "isn't an instance ($session) method answering a boolean",
            classes(checkStatic = true) to "isn't an instance ($session) method answering a boolean",
            classes(clicks = 0) to "$oneMarked $AUTOSCROLL_MODE_CLICK, found 0",
            classes(clicks = 2) to "$oneMarked $AUTOSCROLL_MODE_CLICK, found 2",
            classes(clickElsewhere = true) to "$AUTOSCROLL_MODE_CLICK isn't in $plugin",
            classes(clickChoice = "I") to "isn't an instance method taking the choice last and returning nothing",
            classes(clickLoops = true) to "jumps back to its first instruction",
            classes(preferences = 0) to "expected one class whose initializer loads \"$AUTOSCROLL_PREFERENCE\", found 0",
            classes(preferences = 2) to "expected one class whose initializer loads \"$AUTOSCROLL_PREFERENCE\", found 2",
            classes(getterNamed = false) to "doesn't name \"$AUTOSCROLL_GETTER_NAME\"",
            classes(getters = 2) to "to have one static getter answering a boolean, found 2",
            classes(setters = 0) to "to have one static setter taking a boolean, found 0",
            classes(checkReads = false) to "doesn't read $preference->enabled($prefs)Z",
            classes(clickSaves = false) to "doesn't save to $preference->setEnabled($prefs" + "Z)V",
            classes(clickSavesChoice = "constant") to "$notTheChoice at instruction 4",
            classes(clickSavesChoice = "flipped") to "$notTheChoice at instruction 5",
            classes(clickSavesChoice = "overwrittenOnABranch") to "$notTheChoice at instruction 6",
            classes(clickSavesChoice = "parameterOverwritten") to "$notTheChoice at instruction 5",
            classes(pauses = 0) to "$callers $plugin->handle",
            classes(pauseString = "auto_scroll_v2") to "$callers $plugin->handle",
            classes(otherSavers = 1) to "$callers $plugin->handle",
            classes(toggles = 0) to "$callers $plugin->handle",
            classes(toggleKeepsActivity = false) to "$tabAction, which saves to $preference->setEnabled($prefs" + "Z)V, keeps no $MAIN_ACTIVITY",
            classes(toggleTakes = listOf("I")) to "isn't an instance method taking and returning nothing",
            classes(toggleSaves = 2) to "calls $preference->setEnabled($prefs" + "Z)V 2 times, expected once",
            classes(jumpAfterSave = true) to "has no place right after its call to $preference->setEnabled($prefs" + "Z)V",
            classes(checkReadsMemory = 0) to "expected $plugin->check($session)Z to read one boolean field, found 0",
            classes(checkReadsMemory = 2) to "expected $plugin->check($session)Z to read one boolean field, found 2",
            classes(memoryClassThere = false) to "$memoryClass, whose boolean $plugin->check($session)Z reads, isn't in the app",
            classes(memoryMarked = false) to "$memoryClass, whose boolean $plugin->check($session)Z reads, has no static method marked $AUTOSCROLL_MEMORY answering it",
            classes(memoryFlags = 2) to "expected $memoryClass to keep one instance boolean, on, found [on, on1]",
            classes(clickKeeps = "none") to "to keep its choice in $memory once, found 0",
            classes(clickKeeps = "twice") to "to keep its choice in $memory once, found 2",
            classes(clickKeeps = "constant") to "keeps something other than its choice, parameter 6 (v28), in $memory at instruction 10",
            classes(readerOpcode = "iget") to "$useCase->uiState()Z reads $memory with iget at instruction 4, not iget-boolean",
            classes(clickReads = true) to "reads $memory and hands its choice over too",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes + ExtensionDex.classDef(SETTINGS_STATUS))
            val failure = assertThrows(expected, PatchException::class.java) { keepReelsAutoScrollPatch.execute(context) }
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Keep Reels auto scroll on: ") && failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() in hooks } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
            val status = context.method(SETTINGS_STATUS, "reelAutoScroll").code()
            assertFalse("$expected: the status was switched on", status.first() is NarrowLiteralInstruction &&
                (status.first() as NarrowLiteralInstruction).narrowLiteral == 1 && status[1].opcode == Opcode.RETURN)
        }
    }

    /**
     * In each declared build every return of the check and of the getter is answered, the handler
     * hands its choice over first thing and the Reels tab's long-press action right after it saves.
     */
    @Test
    fun eachDeclaredBuildKeepsAutoScroll() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val found = mutableMapOf<String, ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.stringSection.none { it == AUTOSCROLL_PREFERENCE || it == AUTOSCROLL_EXPIRATION ||
                        it.endsWith("_$IS_AUTOSCROLL_ACTIVE") || it.endsWith("_$AUTOSCROLL_MODE_CLICK") || it.endsWith("_$AUTOSCROLL_DURATION_TAP") }) {
                        return@forEach
                    }
                    for (classDef in dex.classes) {
                        val wanted = classDef.methods.any { method ->
                            method.markers().any { it == IS_AUTOSCROLL_ACTIVE || it == AUTOSCROLL_MODE_CLICK || it == AUTOSCROLL_DURATION_TAP } ||
                                (method.name == "<clinit>" && method.code().any { it.string() in listOf(AUTOSCROLL_PREFERENCE, AUTOSCROLL_EXPIRATION) })
                        }
                        if (wanted) found[classDef.type] = ImmutableClassDef.of(classDef)
                    }
                }
                val preferenceClass = found.values.single { type -> type.methods.any { m -> m.name == "<clinit>" && m.code().any { it.string() == AUTOSCROLL_PREFERENCE } } }
                val getter = preferenceClass.methods.single { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.size == 1 }
                val setter = preferenceClass.methods.single {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf(getter.parameterTypes.single().toString(), "Z")
                }
                val plugin = found.values.single { type -> type.methods.any { m -> IS_AUTOSCROLL_ACTIVE in m.markers() } }
                val checkMethod = plugin.methods.single { IS_AUTOSCROLL_ACTIVE in it.markers() }
                val memory = checkMethod.code().single { it.opcode == Opcode.IGET_BOOLEAN }.reference() as FieldReference
                val memoryText = memory.toString()
                val timerClass = found.values.single { type -> type.methods.any { m -> m.name == "<clinit>" && m.code().any { it.string() == AUTOSCROLL_EXPIRATION } } }
                val expirationSetter = timerClass.methods.single { it.name != "<clinit>" }
                val boxingHelper = expirationSetter.code().mapNotNull { it.reference() as? MethodReference }.last()
                found.putAll(FixtureDex.classes(bundle, setOf(boxingHelper.definingClass)))
                // The timer's and the preference's state: the static fields the preference class
                // keeps, and those of the same types the check reads from the timer's class.
                val descriptorTypes = preferenceClass.staticFields.map { it.type }.toSet()
                val stateClasses = checkMethod.code().filter { it.opcode == Opcode.SGET_OBJECT }.map { it.reference() as FieldReference }
                    .filter { it.type in descriptorTypes }.map { it.definingClass }.toSet() + preferenceClass.type
                assertEquals("${bundle.name}: the timer's and the preference's classes", 2, stateClasses.size)
                val stateReaders = mutableSetOf<String>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        var wanted = classDef.type == memory.definingClass
                        for (method in classDef.methods) {
                            for (instruction in method.code()) {
                                if (instruction.calls(setter) || instruction.referenceText() == memoryText) wanted = true
                                val field = instruction.reference() as? FieldReference ?: continue
                                if (instruction.opcode.setsRegister() && field.definingClass in stateClasses && classDef.type !in stateClasses) {
                                    stateReaders += method.text()
                                }
                            }
                        }
                        if (wanted && classDef.type !in found) found[classDef.type] = ImmutableClassDef.of(classDef)
                    }
                }
                assertEquals("${bundle.name}: what reads the timer or the preference besides their own classes", setOf(checkMethod.text()), stateReaders)
                val context = PatchContexts.of(FixtureDex.withStringPools(bundle, found.values) + ExtensionDex.classDef(SETTINGS_STATUS))
                val sites = context.findReelAutoScroll()
                assertEquals("${bundle.name}: the getter", "${getter.definingClass}->${getter.name}", "${sites.getter.definingClass}->${sites.getter.name}")

                keepReelsAutoScrollPatch.execute(context)

                val check = context.mutableClassDefBy(plugin.type).methods.single { IS_AUTOSCROLL_ACTIVE in it.markers() }
                assertEveryReturnAnswered("${bundle.name}: the check", check, returns = null)
                val checkCode = check.code()
                assertTrue("${bundle.name}: no branch in the check lands on a return's answer",
                    checkCode.indices.filter { checkCode[it].opcode == Opcode.RETURN }.any { it - 2 in check.jumpTargets() })
                assertTrue("${bundle.name}: the check still reads the getter", checkCode.any { it.calls(getter) })
                assertEveryReturnAnswered("${bundle.name}: the getter", context.method(getter), returns = 1, filter = AUTO_SCROLL_SAVED)
                val click = context.mutableClassDefBy(plugin.type).methods.single { AUTOSCROLL_MODE_CLICK in it.markers() }
                assertChoiceFirst(bundle.name, click)
                assertChoiceKept(bundle.name, click, setter.text(), memoryText)
                val callback = found.values.flatMap { it.methods.toList() }.single { AUTOSCROLL_DURATION_TAP in it.markers() && it.code().any { code -> code.calls(expirationSetter) } }
                assertTimerChoices(context.method(callback), expirationSetter.text())
                assertEquals("${bundle.name}: native expiration setter is unchanged", expirationSetter.code().map { Triple(it.opcode, it.referenceText(), it.arguments()) },
                    context.method(expirationSetter).code().map { Triple(it.opcode, it.referenceText(), it.arguments()) })
                val cancellation = found.values.flatMap { it.methods.toList() }.single { AUTOSCROLL_DURATION_TAP in it.markers() && it.text() != callback.text() }
                assertEquals("${bundle.name}: cancellation is unchanged", cancellation.code().map { Triple(it.opcode, it.referenceText(), it.arguments()) },
                    context.method(cancellation).code().map { Triple(it.opcode, it.referenceText(), it.arguments()) })

                val readers = found.values.flatMap { type -> type.methods.filter { m -> m.code().any { it.referenceText() == memoryText && it.opcode.setsRegister() } } }
                assertTrue("${bundle.name}: the check reads the memory", readers.any { it.text() == checkMethod.text() })
                val outside = readers.filter { it.text() != checkMethod.text() }
                val known = listOf(PIP_ON_CREATE, PLAYBACK_STATE)
                assertEquals("${bundle.name}: what reads the memory besides the check", known, outside.map { m -> known.single { it in m.markers() } }.sorted())
                outside.forEach { assertMemoryReadsAnswered("${bundle.name}: ${it.text()}", context.method(it), memoryText, reads = 1) }
                assertEquals("${bundle.name}: reads answered", outside.map { "${it.definingClass}->${it.name}" }.sorted(),
                    sites.memoryReads.map { "${it.method.definingClass}->${it.method.name}" }.sorted())
                val resets = found.values.flatMap { type -> type.methods.filter { m -> m.code().any { it.referenceText() == memoryText && !it.opcode.setsRegister() } } }
                    .filter { it.text() != click.text() }
                assertTrue("${bundle.name}: Instagram resets the memory somewhere", resets.isNotEmpty())
                resets.forEach { reset ->
                    assertEquals("${bundle.name}: ${reset.text()} resets the memory and isn't changed", 0, context.method(reset).code().count { it.referenceText() in hooks })
                }

                val savers = found.values.flatMap { type -> type.methods.filter { m -> m.code().any { it.calls(setter) } }.map { type to it } }
                assertEquals("${bundle.name}: the setter's callers", 3, savers.size)
                val (_, toggle) = savers.single { (type, _) -> type.fields.any { it.type == MAIN_ACTIVITY } }
                assertEquals("${bundle.name}: the long-press action", sites.toggle.toString(), "${toggle.definingClass}->${toggle.name}()")
                assertChoiceAfterSave(bundle.name, context.method(toggle), setter.text())
                val (_, pause) = savers.single { (_, method) -> method.name == "onPause" }
                assertEquals("${bundle.name}: onPause isn't hooked", 0, context.method(pause).code().count { it.referenceText() in hooks })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Each return is the end of a [filter] call and its result, on the return's register; the
     * filter is called once per return and no other hook is called; nothing lands between the
     * filter and its return.
     */
    private fun assertEveryReturnAnswered(what: String, method: MutableMethod, returns: Int?, filter: String = AUTO_SCROLL_ANSWER) {
        val code = method.code()
        val at = code.indices.filter { code[it].opcode == Opcode.RETURN }
        assertTrue("$what: no returns", at.isNotEmpty())
        if (returns != null) assertEquals("$what: returns", returns, at.size)
        assertEquals("$what: $filter calls", at.size, code.count { it.referenceText() == filter })
        assertEquals("$what: other hooks", 0, code.count { it.referenceText() in hooks - filter })
        val targets = method.jumpTargets()
        for (index in at) {
            val register = (code[index] as OneRegisterInstruction).registerA
            assertEquals("$what: the filter before return $index", filter, code[index - 2].referenceText())
            assertEquals("$what: the answer's register at $index", listOf(register), code[index - 2].arguments())
            assertEquals("$what: the result at $index", Opcode.MOVE_RESULT, code[index - 1].opcode)
            assertEquals("$what: the result's register at $index", register, (code[index - 1] as OneRegisterInstruction).registerA)
            assertTrue("$what: a jump skips the answer of return $index", (index - 1..index).none { it in targets })
        }
    }

    /** The handler's first instruction hands its last parameter, the choice, to chosen. */
    private fun assertChoiceFirst(what: String, click: MutableMethod) {
        val code = click.code()
        assertEquals("$what: the handler's first call", AUTO_SCROLL_CHOSEN, code.first().referenceText())
        assertEquals("$what: the handler's choice", listOf(click.implementation!!.registerCount - 1), code.first().arguments())
        assertEquals("$what: one chosen in the handler", 1, code.count { it.referenceText() == AUTO_SCROLL_CHOSEN })
        assertTrue("$what: something jumps to the handler's start", 0 !in click.jumpTargets())
    }

    /** Right after the one call saving the choice, the choice it saved goes to chosen. */
    private fun assertChoiceAfterSave(what: String, toggle: MutableMethod, setter: String) {
        val code = toggle.code()
        val save = code.indices.single { code[it].referenceText() == setter }
        assertEquals("$what: right after the save", AUTO_SCROLL_CHOSEN, code[save + 1].referenceText())
        assertEquals("$what: the saved choice", listOf(code[save].arguments()[1]), code[save + 1].arguments())
        assertEquals("$what: one chosen in the action", 1, code.count { it.referenceText() == AUTO_SCROLL_CHOSEN })
        assertTrue("$what: a jump lands on the hook", save + 1 !in toggle.jumpTargets())
    }

    /**
     * Right after each place the handler keeps its choice, the write to [field] and each call to
     * [setter], the choice kept there goes to stored, and nothing jumps onto that call.
     */
    private fun assertChoiceKept(what: String, click: MutableMethod, setter: String, field: String) {
        val code = click.code()
        val keeps = code.indices.filter {
            code[it].referenceText() == setter || (code[it].referenceText() == field && code[it].opcode == Opcode.IPUT_BOOLEAN)
        }
        assertEquals("$what: the handler keeps its choice in memory and in the preference", 2, keeps.size)
        val targets = click.jumpTargets()
        for (at in keeps) {
            val held = if (code[at].opcode == Opcode.IPUT_BOOLEAN) (code[at] as TwoRegisterInstruction).registerA else code[at].arguments()[1]
            assertEquals("$what: right after keeping at $at", AUTO_SCROLL_STORED, code[at + 1].referenceText())
            assertEquals("$what: the choice kept at $at", listOf(held), code[at + 1].arguments())
            assertTrue("$what: a jump lands on stored after $at", at + 1 !in targets)
        }
        assertEquals("$what: stored calls", keeps.size, code.count { it.referenceText() == AUTO_SCROLL_STORED })
    }

    /**
     * Each of the [reads] reads of [field] in this method is followed by answer, on the register
     * read into, and its result back in that register, with nothing jumping onto either.
     */
    private fun assertMemoryReadsAnswered(what: String, method: MutableMethod, field: String, reads: Int) {
        val code = method.code()
        val at = code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].referenceText() == field }
        assertEquals("$what: reads", reads, at.size)
        val targets = method.jumpTargets()
        for (index in at) {
            val register = (code[index] as TwoRegisterInstruction).registerA
            assertEquals("$what: answer after the read at $index", AUTO_SCROLL_ANSWER, code[index + 1].referenceText())
            assertEquals("$what: the register read at $index", listOf(register), code[index + 1].arguments())
            assertEquals("$what: the result at $index", Opcode.MOVE_RESULT, code[index + 2].opcode)
            assertEquals("$what: the result's register at $index", register, (code[index + 2] as OneRegisterInstruction).registerA)
            assertTrue("$what: a jump lands on the answer of the read at $index", (index + 1..index + 2).none { it in targets })
        }
        assertEquals("$what: answer calls", at.size, code.count { it.referenceText() == AUTO_SCROLL_ANSWER })
        assertEquals("$what: other hooks", 0, code.count { it.referenceText() in hooks - AUTO_SCROLL_ANSWER })
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The plugin with its check, which reads the getter on one path, returns false through a branch
     * straight to a return on another and true on a third, and its handler, which saves the choice
     * it takes last; the preference class, its getter and setter; the Reels viewer's onPause,
     * clearing the preference; and the Reels tab's long-press action, which saves its choice.
     */
    private fun classes(
        checks: Int = 1,
        checkMarker: String = "android_purge_26_q3_$IS_AUTOSCROLL_ACTIVE",
        checkAnswers: String = "Z",
        checkStatic: Boolean = false,
        checkReads: Boolean = true,
        clicks: Int = 1,
        clickElsewhere: Boolean = false,
        clickChoice: String = "Z",
        clickLoops: Boolean = false,
        clickSaves: Boolean = true,
        clickSavesChoice: String = "move",
        preferences: Int = 1,
        getterNamed: Boolean = true,
        getters: Int = 1,
        setters: Int = 1,
        pauses: Int = 1,
        pauseString: String = AUTO_SCROLL_SURFACE,
        otherSavers: Int = 0,
        toggles: Int = 1,
        toggleKeepsActivity: Boolean = true,
        toggleTakes: List<String> = emptyList(),
        toggleSaves: Int = 1,
        jumpAfterSave: Boolean = false,
        checkReadsMemory: Int = 1,
        memoryClassThere: Boolean = true,
        memoryMarked: Boolean = true,
        memoryFlags: Int = 1,
        clickKeeps: String = "choice",
        clickReads: Boolean = false,
        readerOpcode: String = "iget-boolean",
        timerKey: String = expirationKey,
        durationMillis: Long = 3600000,
        jumpIntoDurationSave: Boolean = false,
        durationCapturedPrefs: Boolean = true,
        durationConstructed: Boolean = true,
        timerKeepsDeadline: Boolean = true,
        durationOverwritesPrefs: Boolean = false,
        durationOverwritesReceiver: Boolean = false,
    ): List<ClassDef> {
        val setter = "$preference->setEnabled($prefs" + "Z)V"
        val read = if (checkReads) "invoke-static { v0 }, $preference->enabled($prefs)Z" else "invoke-static { v0 }, Lfixture/Other;->enabled($prefs)Z"
        val instance = "$memoryClass->instance($session)$memoryClass"
        val memoryRead = mapOf(
            0 to listOf("const/4 v2, 0x1"),
            1 to listOf("const/4 v2, 0x0", "invoke-static { v2 }, $instance", "move-result-object v2", "iget-boolean v2, v2, $memory"),
            2 to listOf("const/4 v2, 0x0", "invoke-static { v2 }, $instance", "move-result-object v2", "iget-boolean v3, v2, Lfixture/Elsewhere;->flag:Z",
                "iget-boolean v2, v2, $memory"),
        )
        val checkMethods = (0 until checks).map { copy ->
            method(plugin, if (copy == 0) "check" else "check$copy", listOf(session), checkAnswers, 4, static = checkStatic, body = """
                const/4 v1, 0x0
                if-eqz v1, :memory
                const-string v0, "$checkMarker"
                invoke-static { v0 }, $trace
                if-nez v1, :off
                const/4 v0, 0x0
                $read
                move-result v1
                sget-object v0, $timer->deadline:$deadlineDescriptor
                sget-object v3, $timer->names:[$property
                return v1
                :off
                return v1
                :memory
                ${memoryRead.getValue(checkReadsMemory).joinToString("\n                ")}
                return v2
            """)
        }
        val choiceParameters = clickParameters.dropLast(1) + clickChoice
        val save = if (clickSaves) "invoke-static { v0, v1 }, $setter" else "invoke-static { v0, v1 }, Lfixture/Other;->setEnabled($prefs" + "Z)V"
        val clickMethods = (0 until clicks).map { copy ->
            method(plugin, if (copy == 0) "handle" else "handle$copy", choiceParameters, "V", 20, body = """
                ${if (clickLoops) ":top" else ""}
                const/4 v0, 0x0
                const-string v1, "android_purge_26_q3_$AUTOSCROLL_MODE_CLICK"
                invoke-static { v1 }, $trace
                ${choiceToSave.getValue(clickSavesChoice).joinToString("\n                ")}
                $save
                if-nez v0, :kept
                const/4 v2, 0x0
                invoke-static { v2 }, $instance
                move-result-object v2
                ${choiceToKeep.getValue(clickKeeps).joinToString("\n                ")}
                ${if (clickReads) "iget-boolean v4, v2, $memory" else ""}
                :kept
                ${if (clickLoops) "if-eqz v1, :top" else ""}
                const-wide/16 v8, 0x0
                invoke-static { v0, v8, v9 }, $timerSetter
                ${if (durationConstructed) (0..2).joinToString("\n") {
                    "new-instance v6, $duration\nconst/4 v7, $it\ninvoke-direct { v6, v0, v7 }, $duration-><init>($prefs" + "I)V"
                } else ""}
                return-void
            """)
        }
        val pluginClass = if (clickElsewhere) {
            listOf(classDef(plugin, checkMethods), classDef("Lfixture/OtherPlugin;", clickMethods.map { it.movedTo("Lfixture/OtherPlugin;") }))
        } else {
            listOf(classDef(plugin, checkMethods + clickMethods))
        }

        val preferenceClasses = (0 until preferences).map { copy ->
            val type = if (copy == 0) preference else "Lfixture/OtherPreference;"
            val methods = mutableListOf(
                method(type, "<clinit>", emptyList(), "V", 1, static = true, body = """
                    const-string v0, "clipsAutoscrollEnabled"
                    ${if (getterNamed) "const-string v0, \"$AUTOSCROLL_GETTER_NAME\"" else ""}
                    const-string v0, "$AUTOSCROLL_PREFERENCE"
                    return-void
                """),
            )
            (0 until getters).forEach { methods += method(type, if (it == 0) "enabled" else "enabled$it", listOf(prefs), "Z", 1, static = true, body = """
                const/4 v0, 0x0
                return v0
            """) }
            (0 until setters).forEach { methods += method(type, if (it == 0) "setEnabled" else "setEnabled$it", listOf(prefs, "Z"), "V", 0, static = true, body = """
                return-void
            """) }
            classDef(type, methods)
        }

        val viewerClasses = (0 until pauses).map {
            classDef(viewer, listOf(method(viewer, "onPause", emptyList(), "V", 3, body = """
                const-string v0, "$pauseString"
                const/4 v1, 0x0
                const/4 v2, 0x0
                invoke-static { v1, v2 }, $setter
                return-void
            """)))
        }
        val otherClasses = (0 until otherSavers).map {
            classDef("Lfixture/OtherSaver;", listOf(method("Lfixture/OtherSaver;", "save", emptyList(), "V", 3, body = """
                const/4 v1, 0x0
                const/4 v2, 0x1
                invoke-static { v1, v2 }, $setter
                return-void
            """)))
        }
        val toggleClasses = (0 until toggles).map {
            val saves = (0 until toggleSaves).joinToString("\n") { "invoke-static { v1, v2 }, $setter" }
            val fields = listOf("on" to "Z") + if (toggleKeepsActivity) listOf("activity" to MAIN_ACTIVITY) else emptyList()
            classDef(tabAction, listOf(method(tabAction, "run", toggleTakes, "V", 3, body = """
                iget-boolean v0, p0, $tabAction->on:Z
                xor-int/lit8 v2, v0, 0x1
                const/4 v1, 0x0
                ${if (jumpAfterSave) "if-eqz v2, :after" else ""}
                $saves
                ${if (jumpAfterSave) ":after" else ""}
                const/4 v0, 0x0
                return-void
            """)), fields = fields)
        }
        val memoryClasses = if (!memoryClassThere) emptyList() else listOf(
            classDef(memoryClass, listOf(method(memoryClass, "instance", listOf(session), memoryClass, 1, static = true, body = """
                const-string v0, "android_purge_26_q2_${if (memoryMarked) AUTOSCROLL_MEMORY else "ClipsSessionAutoscrollManager_init"}"
                invoke-static { v0 }, $trace
                const/4 v0, 0x0
                return-object v0
            """)), fields = listOf("on" to "Z") + if (memoryFlags == 2) listOf("on1" to "Z") else emptyList()),
        )
        // The Reels viewer's state reads the memory on one path and the preference on the other,
        // which jumps to right after the read, as 449's does.
        val readerClasses = listOf(
            classDef(useCase, listOf(method(useCase, "uiState", emptyList(), "Z", 3, body = """
                const/4 v0, 0x0
                if-nez v0, :preference
                invoke-static { v0 }, $instance
                move-result-object v0
                $readerOpcode v1, v0, $memory
                :read
                return v1
                :preference
                const/4 v1, 0x1
                goto :read
            """))),
            classDef(pip, listOf(method(pip, "onCreate", listOf("Landroid/os/Bundle;"), "V", 3, body = """
                const/4 v0, 0x0
                invoke-static { v0 }, $instance
                move-result-object v0
                iget-boolean v1, v0, $memory
                iput-boolean v1, p0, $pip->autoScroll:Z
                iget-boolean v2, v0, $memory
                iput-boolean v2, p0, $pip->autoScroll:Z
                return-void
            """)), fields = listOf("autoScroll" to "Z")),
            classDef(lifecycle, listOf(method(lifecycle, "onActivityCreated", emptyList(), "V", 2, body = """
                const/4 v0, 0x0
                invoke-static { v0 }, $instance
                move-result-object v0
                const/4 v1, 0x0
                iput-boolean v1, v0, $memory
                return-void
            """))),
        )
        val timerClasses = listOf(
            classDef("Lfixture/DurationCancel;", listOf(method("Lfixture/DurationCancel;", "invoke", emptyList(), "Ljava/lang/Object;", 1, body = """
                const-string v0, "android_purge_26_q3_$durationMarker"
                invoke-static { v0 }, $trace
                const/4 v0, 0x0
                return-object v0
            """)), interfaces = listOf("Lkotlin/jvm/functions/Function0;")),
            classDef("Lfixture/Save;", listOf(method("Lfixture/Save;", "longValue", listOf("Ljava/lang/Object;", deadlineDescriptor, property, "J"), "V", 1, static = true, body = """
                invoke-static { p3, p4 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
                move-result-object v0
                invoke-interface { p1, p0, v0, p2 }, $deadlineDescriptor->setValue(Ljava/lang/Object;Ljava/lang/Object;$property)V
                return-void
            """))),
            classDef(timer, listOf(
                method(timer, "<clinit>", emptyList(), "V", 1, static = true, body = """
                    const-string v0, "$timerKey"
                    const-string v0, "getClipsAutoscrollExpirationTimestampMs(Lcom/instagram/preferences/user/UserPreferences;)J"
                    return-void
                """),
                method(timer, "expires", listOf(prefs, "J"), "V", 3, static = true, body = """
                    const/4 v2, 0x0
                    invoke-static { p0, v2 }, Lfixture/Checks;->notNull(Ljava/lang/Object;I)V
                    sget-object v1, $timer->deadline:$deadlineDescriptor
                    sget-object v0, $timer->names:[$property
                    aget-object v0, v0, v2
                    invoke-static { p0, v1, v0, ${if (timerKeepsDeadline) "p1, p2" else "v4, v3"} }, Lfixture/Save;->longValue(Ljava/lang/Object;$deadlineDescriptor$property""" + "J)V\nreturn-void"),
            ), staticFields = listOf("deadline" to deadlineDescriptor, "names" to "[$property")),
            classDef(duration, listOf(
                method(duration, "<init>", listOf(prefs, "I"), "V", 0, body = """
                    ${if (durationOverwritesPrefs) "const/4 p1, 0x0" else ""}
                    ${if (durationOverwritesReceiver) "const/4 p0, 0x0" else ""}
                    iput-object ${if (durationCapturedPrefs) "p1" else "p0"}, p0, $duration->preferences:Ljava/lang/Object;
                    iput p2, p0, $duration->which:I
                    invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                    return-void
                """),
                method(duration, "invoke", emptyList(), "Ljava/lang/Object;", 8, body = """
                    iget v1, p0, $duration->which:I
                    if-eqz v1, :hour
                    const/4 v0, 0x1
                    if-eq v1, v0, :day
                    iget-object v2, p0, $duration->preferences:Ljava/lang/Object;
                    check-cast v2, $prefs
                    const-wide v0, 0x7fffffffffffffffL
                    invoke-static { v2, v0, v1 }, $timerSetter
                    goto :logged
                    :hour
                    iget-object v4, p0, $duration->preferences:Ljava/lang/Object;
                    check-cast v4, $prefs
                    ${if (jumpIntoDurationSave) "if-eqz v4, :hourSave" else ""}
                    invoke-static { }, Ljava/lang/System;->currentTimeMillis()J
                    move-result-wide v2
                    const-wide/32 v0, $durationMillis
                    add-long/2addr v2, v0
                    :hourSave
                    invoke-static { v4, v2, v3 }, $timerSetter
                    goto :logged
                    :day
                    iget-object v4, p0, $duration->preferences:Ljava/lang/Object;
                    check-cast v4, $prefs
                    invoke-static { }, Ljava/lang/System;->currentTimeMillis()J
                    move-result-wide v2
                    const-wide/32 v0, 86400000
                    add-long/2addr v2, v0
                    invoke-static { v4, v2, v3 }, $timerSetter
                    :logged
                    const-string v2, "android_purge_26_q3_$durationMarker"
                    invoke-static { v2 }, $trace
                    const/4 v0, 0x0
                    return-object v0
                """),
            ), fields = listOf("preferences" to "Ljava/lang/Object;", "which" to "I"), interfaces = listOf("Lkotlin/jvm/functions/Function0;"), finalFields = true),
        )
        return pluginClass + preferenceClasses + viewerClasses + otherClasses + toggleClasses + memoryClasses + readerClasses + timerClasses
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (name == "<clinit>" || name == "<init>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun Method.movedTo(owner: String): Method = ImmutableMethod(
        owner, name, parameters, returnType, accessFlags, annotations, hiddenApiRestrictions, implementation,
    )

    private fun classDef(type: String, methods: List<Method>, fields: List<Pair<String, String>> = emptyList(),
        staticFields: List<Pair<String, String>> = emptyList(), interfaces: List<String> = emptyList(), finalFields: Boolean = false): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", interfaces, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value or (if (finalFields) AccessFlags.FINAL.value else 0), null, null, null) } +
                staticFields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null, null) },
            methods,
        )

    private fun BytecodePatchContext.method(type: String, name: String): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == name }

    private fun BytecodePatchContext.method(like: Method): MutableMethod =
        mutableClassDefBy(like.definingClass).methods.single {
            it.name == like.name && it.parameterTypes.map(CharSequence::toString) == like.parameterTypes.map(CharSequence::toString)
        }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string

    private fun Instruction.calls(method: Method): Boolean {
        val called = reference() as? MethodReference ?: return false
        return called.definingClass == method.definingClass && called.name == method.name &&
            called.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}
