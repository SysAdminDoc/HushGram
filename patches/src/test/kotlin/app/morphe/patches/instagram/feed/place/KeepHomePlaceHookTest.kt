/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.place

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepHomePlaceHookTest {
    private val home = "Lfixture/HotStart;"
    private val timer = "Lfixture/Timer;"
    private val alarmType = "Lfixture/Alarm;"
    private val planType = "Lfixture/Plan;"
    private val userSession = "Lcom/instagram/common/session/UserSession;"

    /** The hook the patch writes is in the HomePlace the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_PLACE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$TIME_AWAY is not in the extension: $declared", TIME_AWAY.substringAfter("->") in declared)
        assertTrue("$RELOAD is not in the extension: $declared", RELOAD.substringAfter("->") in declared)
        assertTrue("$SKIP_BACKGROUND_RELOAD is not in the extension: $declared", SKIP_BACKGROUND_RELOAD.substringAfter("->") in declared)
        assertTrue("$SKIP_APP_RESET is not in the extension: $declared", SKIP_APP_RESET.substringAfter("->") in declared)
        assertTrue("$APP_RESET_PLAN is not in the extension: $declared", APP_RESET_PLAN.substringAfter("->") in declared)
    }

    /**
     * In each build of the version the app reset is found on ActivityBackstackManager. The timer's method gets
     * the same four instructions as the alarm, and the reset picked on return goes through the extension once,
     * right after it lands, back into the same register as the reset's type. Every other instruction keeps its
     * place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsAppReset() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val context = PatchContexts.of(classesOf(apk, setOf(BACKSTACK_MANAGER)).values)
                val site = context.findAppReset()
                val manager = context.mutableClassDefBy(BACKSTACK_MANAGER).methods
                val timer = manager.single { it.name == site.timer.name && it.parameterTypes.size == 3 }
                val foregrounded = manager.single { it.name == FOREGROUNDED && it.parameterTypes.isEmpty() }
                val first = timer.code().first()
                val originalTimer = NeutralNativePath(timer)
                val originalReturn = NeutralNativePath(foregrounded)
                context.holdAppReset(site)
                assertSkippedFirst("$what app reset timer", timer.code(), first, SKIP_APP_RESET)
                assertPlanHeld(what, foregrounded.code(), site)
                originalTimer.assertPreserved("$what app reset timer", timer, setOf(0, 1, 2, 3))
                originalReturn.assertPreserved("$what $FOREGROUNDED", foregrounded, setOf(site.picked + 1, site.picked + 2, site.picked + 3))
            }
        }
    }

    /** The stand-in app reset gets the same hooks, and one that can't be read is refused before anything changes. */
    @Test
    fun theAppResetIsHeldOrRefused() {
        val context = PatchContexts.of(classes())
        val site = context.findAppReset()
        assertEquals(planType, site.planType)
        assertEquals(2, site.plan)
        val manager = context.mutableClassDefBy(BACKSTACK_MANAGER).methods
        val timer = manager.single { it.name == "A05" }
        val first = timer.code().first()
        context.holdAppReset(site)
        assertSkippedFirst("stand-in timer", timer.code(), first, SKIP_APP_RESET)
        assertPlanHeld("stand-in", manager.single { it.name == FOREGROUNDED }.code(), site)

        val noTimer = "expected one static (ActivityBackstackManager, reset, long)V on $BACKSTACK_MANAGER, found"
        val cases = listOf(
            classes(reset = "no manager") to "$noTimer 0",
            classes(reset = "no timer") to "$noTimer 0",
            classes(reset = "two timers") to "$noTimer 2",
            classes(reset = "no locals") to "has no local register to read the answer into",
            classes(reset = "loop") to "jumps back to its first instruction",
            classes(reset = "no pick") to "to pick its reset with one (double)$planType call, found 0",
            classes(reset = "two picks") to "to pick its reset with one (double)$planType call, found 2",
            classes(reset = "unkept") to "doesn't keep the reset it picks",
            classes(reset = "jump past pick") to "jumps past the reset it picks",
        )
        for ((classes, expected) in cases) {
            val refused = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { refused.findAppReset() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    private fun assertPlanHeld(what: String, code: List<Instruction>, site: AppResetSite) {
        val at = site.picked + 1
        assertEquals("$what: the pick lands first", Opcode.MOVE_RESULT_OBJECT, code[site.picked].opcode)
        assertEquals("$what: the plan hook", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
        assertEquals("$what: the plan hook's method", APP_RESET_PLAN, code[at].referenceText())
        assertEquals("$what: handed the plan", listOf(site.plan), code[at].arguments())
        assertEquals("$what: plan read back", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals("$what: into the same register", site.plan, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: as the reset's type", Opcode.CHECK_CAST, code[at + 2].opcode)
        assertEquals("$what: cast to the reset's type", site.planType, code[at + 2].referenceText())
        assertEquals("$what: one plan hook", 1, code.count { it.referenceText() == APP_RESET_PLAN })
    }

    /**
     * In each build of the version the background reload alarm is found by the event it logs, and the hook goes
     * in once, first thing, returning when the extension says to skip and otherwise falling through to Instagram's
     * first instruction. Every other instruction keeps its place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsBackgroundReloadAlarm() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val holders = FixtureDex.classesHolding(apk, BACKGROUND_RELOAD_FIRED).map { it.type }.toSet()
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val context = PatchContexts.of(classesOf(apk, holders).values)
                val alarm = context.findBackgroundReloadAlarm()
                val method = context.mutableClassDefBy(alarm.definingClass).methods.single { it.name == "onAlarm" && it.parameterTypes.isEmpty() }
                val first = method.code().first()
                val original = NeutralNativePath(method)
                context.holdBackgroundReload(alarm)
                assertSkippedFirst(what, method.code(), first, SKIP_BACKGROUND_RELOAD)
                original.assertPreserved("$what background reload alarm", method, setOf(0, 1, 2, 3))
            }
        }
    }

    /** The stand-in alarm gets the same four instructions, and a build whose alarm can't be read is refused untouched. */
    @Test
    fun theBackgroundReloadAlarmIsHeldOrRefused() {
        val context = PatchContexts.of(classes())
        val alarm = context.findBackgroundReloadAlarm()
        val method = context.mutableClassDefBy(alarmType).methods.single { it.name == "onAlarm" }
        val first = method.code().first()
        context.holdBackgroundReload(alarm)
        assertSkippedFirst("stand-in", method.code(), first, SKIP_BACKGROUND_RELOAD)

        val cases = listOf(
            classes(alarm = "none") to "expected one onAlarm()V loading \"$BACKGROUND_RELOAD_FIRED\" and \"$SINCE_BACKGROUNDED\", found 0",
            classes(alarm = "one string") to "expected one onAlarm()V loading \"$BACKGROUND_RELOAD_FIRED\" and \"$SINCE_BACKGROUNDED\", found 0",
            classes(alarm = "two") to "expected one onAlarm()V loading \"$BACKGROUND_RELOAD_FIRED\" and \"$SINCE_BACKGROUNDED\", found 2",
            classes(alarm = "no locals") to "has no local register to read the answer into",
            classes(alarm = "loop") to "jumps back to its first instruction",
        )
        for ((classes, expected) in cases) {
            val refused = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { refused.findBackgroundReloadAlarm() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    private fun assertSkippedFirst(what: String, code: List<Instruction>, first: Instruction, hook: String) {
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$what: the hook's method", hook, code[0].referenceText())
        assertEquals("$what: answer read", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: into v0", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: go on unless skipped", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: tests the answer", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: goes on at Instagram's first instruction", code[2].codeUnits + code[3].codeUnits,
            (code[2] as OffsetInstruction).codeOffset)
        assertEquals("$what: skipped returns", Opcode.RETURN_VOID, code[3].opcode)
        assertTrue("$what: Instagram's first instruction follows", code[4] === first || code[4].opcode == first.opcode)
        assertEquals("$what: one hook", 1, code.count { it.referenceText() == hook })
    }

    /**
     * In each build of the version Home's warm-start check is found by its trace name. The time hook goes in once,
     * right in front of the minimum time away call, writing its answer back into the pair the call reads, and the
     * reload hook goes in once, right after the answer is logged, writing what it gives back into the register the
     * compare reads. Every other instruction keeps its place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsWarmStartCheck() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val holders = FixtureDex.classesHolding(apk, HOT_START_TRACE).map { it.type }.toSet()
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, holders).values
                val context = PatchContexts.of(classes)
                val site = context.findHomePlaceSite()
                val check = context.mutableClassDefBy(site.check.definingClass).methods.single {
                    it.name == site.check.name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Integer;")
                }
                val original = NeutralNativePath(check)
                context.keepHomePlace(site)

                val code = check.code()
                val at = site.index
                assertTrue("$what: the hook", code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE)
                assertEquals("$what: the hook's method", TIME_AWAY, code[at].referenceText())
                assertEquals("$what: handed the time", listOf(site.time, site.time + 1), code[at].arguments())
                assertEquals("$what: answer read back", Opcode.MOVE_RESULT_WIDE, code[at + 1].opcode)
                assertEquals("$what: into the same pair", site.time, (code[at + 1] as OneRegisterInstruction).registerA)
                val call = code[at + 2]
                assertEquals("$what: the call after it", listOf(userSession, "J"),
                    ((call as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                        .parameterTypes.map(Any::toString))
                assertEquals("$what: reads the pair", site.time, call.arguments()[2])
                assertEquals("$what: one hook", 1, code.count { it.referenceText() == TIME_AWAY })

                val settled = site.settled + 2
                assertEquals("$what: the log before the reload hook", listOf("Ljava/lang/Integer;", "Ljava/lang/Long;"),
                    ((code[settled - 1] as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                        .parameterTypes.map(Any::toString))
                assertEquals("$what: logged the answer", site.answer, code[settled - 1].arguments()[1])
                assertEquals("$what: the reload hook", RELOAD, code[settled].referenceText())
                assertEquals("$what: handed the answer and nothing to do", listOf(site.answer, site.none), code[settled].arguments())
                assertEquals("$what: reload answer read back", Opcode.MOVE_RESULT_OBJECT, code[settled + 1].opcode)
                assertEquals("$what: into the answer", site.answer, (code[settled + 1] as OneRegisterInstruction).registerA)
                assertEquals("$what: as an Integer", Opcode.CHECK_CAST, code[settled + 2].opcode)
                assertEquals("$what: the answer cast", site.answer, (code[settled + 2] as OneRegisterInstruction).registerA)
                assertEquals("$what: one reload hook", 1, code.count { it.referenceText() == RELOAD })
                val compare = (settled + 3 until code.size).first {
                    it in code.indices && code[it].opcode == Opcode.IF_NE &&
                        (code[it] as TwoRegisterInstruction).let { two -> two.registerA == site.answer || two.registerB == site.answer }
                }
                assertTrue("$what: the compare reads the answer and nothing to do",
                    (code[compare] as TwoRegisterInstruction).let { setOf(it.registerA, it.registerB) == setOf(site.answer, site.none) })
                original.assertPreserved("$what warm-start check", check, setOf(at, at + 1, settled, settled + 1, settled + 2))
            }
        }
    }

    /** A pair past v15 is handed over with a range call, which can name it. */
    @Test
    fun aHighPairIsHandedOverWithARangeCall() {
        val context = PatchContexts.of(classes(high = true))
        val site = context.findHomePlaceSite()
        assertEquals(16, site.time)
        context.keepHomePlace(site)
        val code = context.check().code()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[site.index].opcode)
        assertEquals(listOf(16, 17), code[site.index].arguments())
        assertEquals(16, (code[site.index + 1] as OneRegisterInstruction).registerA)
        assertEquals(RELOAD, code[site.settled + 2].referenceText())
        assertEquals(listOf(7, 6), code[site.settled + 2].arguments())
    }

    /** A build the patch can't read fails at patch time, saying what it found, and the check is left alone. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(minTimeAway = false) to "expected one (Integer)Z loading \"$HOT_START_TRACE\" and \"$MIN_TIME_AWAY\", found 0",
            classes(twoChecks = true) to "expected one (Integer)Z loading \"$HOT_START_TRACE\" and \"$MIN_TIME_AWAY\", found 2",
            classes(calls = 0) to "to make one (UserSession, long)Z call, found 0",
            classes(calls = 2) to "to make one (UserSession, long)Z call, found 2",
            classes(static = true) to "doesn't make its minimum time away call with invoke-virtual",
            classes(jumpToCall = true) to "jumps to its minimum time away call",
            classes(decision = "no log") to "to log its answer with one (Integer, Long)V call, found 0",
            classes(decision = "two logs") to "to log its answer with one (Integer, Long)V call, found 2",
            classes(decision = "log first") to "logs its answer before its minimum time away call",
            classes(decision = "no compare") to "never compares its answer after logging it",
            classes(decision = "answer changed") to "changes its answer between the log and the compare",
            classes(decision = "none written twice") to "in a register only one Integer sget-object writes",
            classes(decision = "none not an Integer") to "in a register only one Integer sget-object writes",
            classes(decision = "none after log") to "loads its \"nothing to do\" value after logging its answer",
            classes(decision = "jump past log") to "jumps past the log of its answer",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findHomePlaceSite() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.name }
                for (method in original.methods) {
                    assertEquals(
                        "${original.type}->${method.name} changed while the patch refused",
                        method.code().map { it.opcode }, now.getValue(method.name).code().map { it.opcode },
                    )
                }
            }
        }
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.check(): MutableMethod =
        mutableClassDefBy(home).methods.single { it.name == "A0F" }

    // ---- stand-ins shaped like Instagram 452's ---------------------------------------------------

    /**
     * Home's warm-start controller: an `(Integer)Z` that loads the trace name and the skip reason, works out
     * a time away into a register pair and hands it with the session to the minimum time away check, then logs
     * the answer it settled on and compares it with its "nothing to do" value. [decision] names a way the
     * answer's part can differ from 452's. Beside it, the background reload alarm: an `onAlarm()V` that loads the
     * event it logs; [alarm] names a way it can differ.
     */
    private fun classes(
        minTimeAway: Boolean = true,
        twoChecks: Boolean = false,
        calls: Int = 1,
        static: Boolean = false,
        jumpToCall: Boolean = false,
        high: Boolean = false,
        decision: String = "",
        alarm: String = "",
        reset: String = "",
    ): List<ClassDef> {
        val invoke = when {
            static -> "invoke-static { v5, v2, v3 }, $timer->check($userSession J)Z"
            high -> "invoke-virtual/range { v14 .. v17 }, $timer->check($userSession J)Z"
            else -> "invoke-virtual { v4, v5, v2, v3 }, $timer->check($userSession J)Z"
        }.replace("$userSession J", "${userSession}J")
        val setup = if (high) """
            const-wide/16 v16, 0x0
            sget-object v14, $timer->A00:$timer
            const/4 v15, 0x0
        """ else """
            const-wide/16 v2, 0x0
            sget-object v4, $timer->A00:$timer
            const/4 v5, 0x0
        """
        val strings = "const-string v0, \"$HOT_START_TRACE\"\n" + if (minTimeAway) "const-string v0, \"$MIN_TIME_AWAY\"" else ""
        val none = if (decision == "none not an Integer") "sget-object v6, $timer->NONE:$timer" else "sget-object v6, $timer->NONE:Ljava/lang/Integer;"
        val log = "invoke-direct { v9, v7, v8 }, $home->A07(Ljava/lang/Integer;Ljava/lang/Long;)V"
        val logs = when (decision) {
            "no log", "log first" -> ""
            "two logs" -> "$log\n$log"
            else -> log
        }
        val answer = """
            sget-object v7, $timer->RELOAD:Ljava/lang/Integer;
            const/4 v8, 0x0
            const/4 v9, 0x0
        """
        val body = """
            $strings
            $setup
            ${if (decision == "none written twice") "const/4 v6, 0x0" else ""}
            ${if (decision == "none after log") "" else none}
            ${if (decision == "log first") "$answer\n$log" else ""}
            ${if (jumpToCall) "if-eqz v0, :call" else ""}
            ${if (jumpToCall) ":call" else ""}
            ${(1..calls).joinToString("\n") { "$invoke\nmove-result v0" }}
            $answer
            ${if (decision == "jump past log") "if-eqz v0, :settled" else ""}
            $logs
            :settled
            ${if (decision == "answer changed") "move-object v7, v6" else ""}
            ${if (decision == "none after log") none else ""}
            const/4 v1, 0x1
            ${if (decision == "no compare") "if-eq v7, v6, :reload" else "if-ne v7, v6, :reload"}
            return v0
            :reload
            return v1
        """
        val methods = listOf(
            method(home, "A0F", listOf("Ljava/lang/Integer;"), "Z", 18, body = body),
            method(home, "other", emptyList(), "V", 1, body = "return-void"),
        )
        val result = mutableListOf(classDef(home, methods))
        if (twoChecks) result += classDef("Lfixture/Twin;", listOf(method("Lfixture/Twin;", "A0F", listOf("Ljava/lang/Integer;"), "Z", 18, body = body)))
        val alarmBody = """
            ${if (alarm == "loop") ":top" else ""}
            const-string v0, "$BACKGROUND_RELOAD_FIRED"
            ${if (alarm == "one string") "" else "const-string v0, \"$SINCE_BACKGROUNDED\""}
            ${if (alarm == "loop") "if-eqz v0, :top" else ""}
            return-void
        """
        fun alarmClass(type: String) = classDef(type, listOf(method(type, "onAlarm", emptyList(), "V", if (alarm == "no locals") 0 else 2, body = alarmBody)))
        if (alarm != "none") result += alarmClass(alarmType)
        if (alarm == "two") result += alarmClass("Lfixture/AlarmTwin;")
        if (reset != "no manager") result += resetClass(reset)
        return result
    }

    /**
     * The app reset, shaped like 452's ActivityBackstackManager: the static `(manager, plan, long)V` its timer
     * runs, and [FOREGROUNDED], which picks a plan with a `(double)` call. [reset] names a way it can differ.
     */
    private fun resetClass(reset: String): ClassDef {
        val timerBody = """
            ${if (reset == "loop") ":top" else ""}
            const/4 v0, 0x0
            ${if (reset == "loop") "if-eqz v0, :top" else ""}
            return-void
        """
        val pick = "invoke-virtual { v3, v0, v1 }, $BACKSTACK_MANAGER->pick(D)$planType"
        val picks = when (reset) {
            "no pick" -> ""
            "two picks" -> "$pick\nmove-result-object v2\n$pick\nmove-result-object v2"
            "unkept" -> pick
            else -> "$pick\nmove-result-object v2"
        }
        val returnBody = """
            const-wide/16 v0, 0x0
            ${if (reset == "jump past pick") "if-eqz v0, :after" else ""}
            $picks
            ${if (reset == "jump past pick") ":after" else ""}
            if-eqz v2, :none
            :none
            return-void
        """
        val timerParameters = listOf(BACKSTACK_MANAGER, planType, "J")
        val methods = buildList {
            if (reset != "no timer") add(method(BACKSTACK_MANAGER, "A05", timerParameters, "V", if (reset == "no locals") 0 else 2, timerBody, static = true))
            if (reset == "two timers") add(method(BACKSTACK_MANAGER, "A06", timerParameters, "V", 2, timerBody, static = true))
            add(method(BACKSTACK_MANAGER, FOREGROUNDED, emptyList(), "V", 3, returnBody))
        }
        return classDef(BACKSTACK_MANAGER, methods)
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        static: Boolean = false,
    ): Method {
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}
