/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.place

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsConstString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep your place in Home"
internal const val HOME_PLACE = "$EXTENSION_PACKAGE/feed/HomePlace;"
internal const val TIME_AWAY = "$HOME_PLACE->timeAway(J)J"
internal const val RELOAD = "$HOME_PLACE->reload(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val SKIP_BACKGROUND_RELOAD = "$HOME_PLACE->skipBackgroundReload()Z"
internal const val SKIP_APP_RESET = "$HOME_PLACE->skipAppReset()Z"
internal const val APP_RESET_PLAN = "$HOME_PLACE->appResetPlan(Ljava/lang/Object;)Ljava/lang/Object;"

/** Instagram's app reset, which keeps its name in the build. */
internal const val BACKSTACK_MANAGER = "Lcom/instagram/mainactivity/appentry/appreset/ActivityBackstackManager;"

/** Where the app reset picks a reset as Instagram comes back to the foreground. */
internal const val FOREGROUNDED = "onAppForegrounded"

/** The event Home's background reload alarm logs when it reloads, and what it logs with it. */
internal const val BACKGROUND_RELOAD_FIRED = "bg_refresh_fired"
internal const val SINCE_BACKGROUNDED = "ms_since_backgrounded"

/** The trace name Home's warm-start check loads, which Instagram's build keeps. */
internal const val HOT_START_TRACE = "MainFeedFragment.onHotStart"

/** The skip reason the check logs when you weren't away long enough for a reload. */
internal const val MIN_TIME_AWAY = "MinTimeAway"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val INTEGER = "Ljava/lang/Integer;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Home's warm-start check: the instance `(Integer)Z` that loads [HOT_START_TRACE] and [MIN_TIME_AWAY]. */
internal object HomeHotStartFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/Integer;"),
    custom = { method, _ -> method.loadsConstString(HOT_START_TRACE) && method.loadsConstString(MIN_TIME_AWAY) },
)

/** The alarm that reloads Home in the background: an `onAlarm()V` that loads [BACKGROUND_RELOAD_FIRED] and [SINCE_BACKGROUNDED]. */
internal object HomeBackgroundReloadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ ->
        method.name == "onAlarm" && method.loadsConstString(BACKGROUND_RELOAD_FIRED) && method.loadsConstString(SINCE_BACKGROUNDED)
    },
)

/**
 * What runs when the app reset timer goes off: the static `(ActivityBackstackManager, plan, long)V`
 * on [BACKSTACK_MANAGER].
 */
internal object AppResetTimerFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val types = method.parameterTypes.map(Any::toString)
        method.definingClass == BACKSTACK_MANAGER && AccessFlags.STATIC.isSet(method.accessFlags) &&
            types.size == 3 && types[0] == BACKSTACK_MANAGER && types[1].startsWith("L") && types[2] == "J"
    },
)

/** Where the app reset picks a reset on return: [FOREGROUNDED]`()V` on [BACKSTACK_MANAGER]. */
internal object AppResetReturnFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ -> method.definingClass == BACKSTACK_MANAGER && method.name == FOREGROUNDED },
)

/**
 * Keeps Home on the post you were on when you come back to Instagram (#354).
 *
 * Coming back from the background with Home showing, Instagram's warm-start check works out how long
 * you were away and hands it, with the session, to a check that says whether that's under the
 * server's minimum (30 seconds by default). Under it, Home stays as it was; over it, the feed reloads
 * and scrolls to the top. The instant feed and the server's timers can pick a reload around that
 * check too, so it settles on one answer, a reload or Instagram's "nothing to do" value, which it
 * logs with the warm-start reason before acting on it. The patch hands the time to the extension
 * right before the minimum check and the answer to it right after it's logged, and while the switch
 * is on the extension gives back 0 and "nothing to do", so every return is a quick one.
 *
 * Going to the background also sets an alarm for that minimum, and if Instagram is still in the
 * background when it goes off, it reloads Home there and then, so the return finds a new feed at the
 * top whatever the check says. The patch asks the extension first thing in that alarm, and while the
 * switch is on the alarm returns without reloading. Pull to refresh goes through neither.
 *
 * What actually sent Home back to the top on a device is Instagram's app reset. Going to the
 * background starts a timer, about a minute on the server's say, and when it runs out a static method
 * on [BACKSTACK_MANAGER] closes whatever was opened on top and marks a reset that the next resume
 * carries out: back to Home, scrolled to the top, whichever tab you were on. With some server settings
 * [FOREGROUNDED] picks a reset on return as well. The patch asks the extension first thing in the
 * timer's method and returns when it says to skip, and hands the reset picked on return to the
 * extension, which gives back null (no reset) while the switch is on.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val keepHomePlacePatch = bytecodePatch(
    name = "Keep your place in Home",
    description = "Coming back to Instagram leaves Home on the post you were on, however long you were away, " +
        "instead of reloading it and jumping to the top. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("homePlace")
        val site = findHomePlaceSite()
        val alarm = findBackgroundReloadAlarm()
        val reset = findAppReset()
        keepHomePlace(site)
        holdBackgroundReload(alarm)
        holdAppReset(reset)
        enableStatus("homePlace")
    }
}

/**
 * Home's warm-start check, the index of its minimum time away call and the pair holding the time, and
 * where the answer is settled: the index right after it's logged, the register holding it and the
 * register holding Instagram's "nothing to do" value.
 */
internal class HomePlaceSite(val check: Method, val index: Int, val time: Int, val settled: Int, val answer: Int, val none: Int)

/**
 * Finds the one call in Home's warm-start check that takes the session and a time and answers a
 * boolean, and checks it before anything changes:
 * - One instance method matches [HomeHotStartFingerprint].
 * - It makes one `invoke-virtual` of a `(UserSession, long)Z` method, and the long is a register pair.
 * - Nothing jumps to the call, so the hook goes in front of it and every path still passes through it.
 * - It logs its answer once, with a `(Integer, Long)V` call on its own class, after the minimum check.
 * - The first `if-ne` after that compares the answer, unchanged since, with a register that only one
 *   `sget-object` of an Integer writes, earlier on: Instagram's "nothing to do" value.
 * - Nothing jumps to the instruction after the log call. An `if-ne` only names v0 to v15, so the
 *   hook can always hand both registers over without a range call.
 */
internal fun BytecodePatchContext.findHomePlaceSite(): HomePlaceSite {
    val checks = HomeHotStartFingerprint.matchAllOrNull().orEmpty().map { it.method }
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
        .distinctBy { it.definingClass + it.name }
    val check = checks.singleOrNull()
        ?: refuse("expected one (Integer)Z loading \"$HOT_START_TRACE\" and \"$MIN_TIME_AWAY\", found ${checks.size}")
    val where = "${check.definingClass}->${check.name}"
    val code = check.implementation?.instructions?.toList().orEmpty()
    val calls = code.indices.filter { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.returnType == "Z" &&
            called.parameterTypes.map(Any::toString) == listOf(USER_SESSION, "J")
    }
    val index = calls.singleOrNull() ?: refuse("expected $where to make one (UserSession, long)Z call, found ${calls.size}")
    val invoke = code[index]
    if (invoke.opcode != Opcode.INVOKE_VIRTUAL && invoke.opcode != Opcode.INVOKE_VIRTUAL_RANGE) {
        refuse("$where doesn't make its minimum time away call with invoke-virtual")
    }
    val arguments = invoke.argumentRegisters()
    if (arguments.size != 4 || arguments[3] != arguments[2] + 1) refuse("$where doesn't hand the time over as a register pair")
    val targets = check.jumpTargets()
    if (index in targets) refuse("something in $where jumps to its minimum time away call")

    val logs = code.indices.filter { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == check.definingClass && called.returnType == "V" &&
            called.parameterTypes.map(Any::toString) == listOf(INTEGER, "Ljava/lang/Long;")
    }
    val log = logs.singleOrNull() ?: refuse("expected $where to log its answer with one (Integer, Long)V call, found ${logs.size}")
    if (log < index) refuse("$where logs its answer before its minimum time away call")
    val answer = code[log].argumentRegisters().getOrNull(1) ?: refuse("$where doesn't hand its answer to the log call")
    val compare = (log + 1 until code.size).firstOrNull { at ->
        code[at].opcode == Opcode.IF_NE && (code[at] as TwoRegisterInstruction).let { it.registerA == answer || it.registerB == answer }
    } ?: refuse("$where never compares its answer after logging it")
    val none = (code[compare] as TwoRegisterInstruction).let { if (it.registerA == answer) it.registerB else it.registerA }
    if ((log + 1 until compare).any { code[it].writes(answer) }) refuse("$where changes its answer between the log and the compare")
    val noneWrites = code.indices.filter { code[it].writes(none) }
    val noneSet = noneWrites.singleOrNull()
        ?.takeIf { code[it].opcode == Opcode.SGET_OBJECT && ((code[it] as ReferenceInstruction).reference as FieldReference).type == INTEGER }
        ?: refuse("$where doesn't hold its \"nothing to do\" value in a register only one Integer sget-object writes")
    if (noneSet > log) refuse("$where loads its \"nothing to do\" value after logging its answer")
    if (log + 1 in targets) refuse("something in $where jumps past the log of its answer")
    return HomePlaceSite(check, index, arguments[2], log + 1, answer, none)
}

/**
 * Right after the answer is logged, hand it and the "nothing to do" value to [RELOAD] and put what
 * comes back in the answer's register. In front of the minimum time away call, hand the time to
 * [TIME_AWAY] and put its answer back in the same pair, so the call and everything after it reads
 * the time the extension gave. The later site goes in first so the earlier index still holds.
 */
internal fun BytecodePatchContext.keepHomePlace(site: HomePlaceSite) {
    val method = mutable(site.check)
    method.addInstructions(
        site.settled,
        """
            invoke-static { v${site.answer}, v${site.none} }, $RELOAD
            move-result-object v${site.answer}
            check-cast v${site.answer}, $INTEGER
        """,
    )
    val time = site.time
    val call = if (time + 1 <= 15) "invoke-static { v$time, v${time + 1} }" else "invoke-static/range { v$time .. v${time + 1} }"
    method.addInstructions(
        site.index,
        """
            $call, $TIME_AWAY
            move-result-wide v$time
        """,
    )
}

/**
 * Finds the alarm that reloads Home in the background and checks it before anything changes: one
 * instance method matches [HomeBackgroundReloadFingerprint], it has a local to read the answer
 * into, and nothing jumps back to its first instruction.
 */
internal fun BytecodePatchContext.findBackgroundReloadAlarm(): Method {
    val alarms = HomeBackgroundReloadFingerprint.matchAllOrNull().orEmpty().map { it.method }
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
        .distinctBy { it.definingClass + it.name }
    val alarm = alarms.singleOrNull()
        ?: refuse("expected one onAlarm()V loading \"$BACKGROUND_RELOAD_FIRED\" and \"$SINCE_BACKGROUNDED\", found ${alarms.size}")
    val where = "${alarm.definingClass}->${alarm.name}"
    if (alarm.localRegisterCount() < 1) refuse("$where has no local register to read the answer into")
    if (0 in alarm.jumpTargets()) refuse("something in $where jumps back to its first instruction")
    return alarm
}

/**
 * First thing in the alarm, ask [SKIP_BACKGROUND_RELOAD] and return when it says to skip. No local
 * is live yet at the top, so v0 is free.
 */
internal fun BytecodePatchContext.holdBackgroundReload(alarm: Method) = returnFirstWhen(alarm, SKIP_BACKGROUND_RELOAD)

/**
 * The app reset's timer method and, in [FOREGROUNDED], the index of the `move-result-object` that
 * takes the reset picked on return, the register it lands in and the reset's type.
 */
internal class AppResetSite(val timer: Method, val foregrounded: Method, val picked: Int, val plan: Int, val planType: String)

/**
 * Finds the app reset and checks it before anything changes:
 * - One static method matches [AppResetTimerFingerprint], it has a local to read the answer into,
 *   and nothing jumps back to its first instruction. Its second parameter is the reset's type.
 * - One [FOREGROUNDED] matches [AppResetReturnFingerprint], and it makes one call that takes a
 *   double and answers the reset's type, straight into a `move-result-object`.
 * - Nothing jumps to the instruction after that, so every path that took the reset passes the hook.
 */
internal fun BytecodePatchContext.findAppReset(): AppResetSite {
    val timers = AppResetTimerFingerprint.matchAllOrNull().orEmpty().map { it.method }
        .filter { it.implementation != null }
        .distinctBy { it.name + it.parameterTypes.joinToString() }
    val timer = timers.singleOrNull()
        ?: refuse("expected one static (ActivityBackstackManager, reset, long)V on $BACKSTACK_MANAGER, found ${timers.size}")
    val timerWhere = "${timer.definingClass}->${timer.name}"
    if (timer.localRegisterCount() < 1) refuse("$timerWhere has no local register to read the answer into")
    if (0 in timer.jumpTargets()) refuse("something in $timerWhere jumps back to its first instruction")
    val planType = timer.parameterTypes[1].toString()

    val returns = AppResetReturnFingerprint.matchAllOrNull().orEmpty().map { it.method }
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
        .distinctBy { it.name }
    val foregrounded = returns.singleOrNull() ?: refuse("expected one $FOREGROUNDED()V on $BACKSTACK_MANAGER, found ${returns.size}")
    val where = "${foregrounded.definingClass}->${foregrounded.name}"
    val code = foregrounded.implementation?.instructions?.toList().orEmpty()
    val picks = code.indices.filter { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.returnType == planType && called.parameterTypes.map(Any::toString) == listOf("D")
    }
    val pick = picks.singleOrNull() ?: refuse("expected $where to pick its reset with one (double)$planType call, found ${picks.size}")
    val result = code.getOrNull(pick + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where doesn't keep the reset it picks")
    if (pick + 2 in foregrounded.jumpTargets()) refuse("something in $where jumps past the reset it picks")
    return AppResetSite(timer, foregrounded, pick + 1, (result as OneRegisterInstruction).registerA, planType)
}

/**
 * First thing in the timer's method, ask [SKIP_APP_RESET] and return when it says to skip. Right
 * after the reset picked on return lands, hand it to [APP_RESET_PLAN] and put what comes back, cast
 * to the reset's type, in the same register. A range call hands it over from any register.
 */
internal fun BytecodePatchContext.holdAppReset(site: AppResetSite) {
    returnFirstWhen(site.timer, SKIP_APP_RESET)
    mutable(site.foregrounded).addInstructions(
        site.picked + 1,
        """
            invoke-static/range { v${site.plan} .. v${site.plan} }, $APP_RESET_PLAN
            move-result-object v${site.plan}
            check-cast v${site.plan}, ${site.planType}
        """,
    )
}

/** First thing in [target], ask [hook] and return when it answers true. No local is live yet at the top, so v0 is free. */
private fun BytecodePatchContext.returnFirstWhen(target: Method, hook: String) {
    val method = mutable(target)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

/** Whether this instruction writes [register], as itself or as the second half of a wide pair. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
