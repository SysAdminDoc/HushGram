/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.sleep

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.media.background.FRAGMENT_PAUSED
import app.morphe.patches.instagram.media.background.SINGLE_TAP_PAUSED
import app.morphe.patches.instagram.media.background.UI_THREAD
import app.morphe.patches.instagram.media.background.addAfterLeadingHooks
import app.morphe.patches.instagram.media.background.mutable
import app.morphe.patches.instagram.media.taptoplay.findPlayInternal
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val REEL_SCREEN_SLEEP_PATCH = "Let the screen sleep when a reel stops"
internal const val REEL_SCREEN_SLEEP = "$EXTENSION_PACKAGE/reels/ReelScreenSleep;"
internal const val KEEP_AWAKE = "$REEL_SCREEN_SLEEP->keepAwake(Landroid/view/Window;I)V"
internal const val SLEEP_STARTED = "$REEL_SCREEN_SLEEP->started(Ljava/lang/Object;)V"
internal const val GET_WINDOW = "Landroid/app/Activity;->getWindow()Landroid/view/Window;"
internal const val CLEAR_FLAGS = "Landroid/view/Window;->clearFlags(I)V"

/** WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON. */
internal const val KEEP_SCREEN_ON = 0x80

private const val STRING = "Ljava/lang/String;"

/**
 * The Reels viewer's keep-awake helper, [method]: [index] is where the hook goes, right after the
 * window's move-result, [window] the register holding the window, and [keep] the one holding the
 * helper's boolean.
 */
internal class KeepAwakeSite(val method: Method, val index: Int, val window: Int, val keep: Int)

/** Everything the patch changes or calls: the helper, IgVideoPlayerImpl's playInternal, and its isPlaying. */
internal class ReelScreenSleepSites(val keepAwake: KeepAwakeSite, val playInternal: Method, val player: String, val isPlaying: String)

/**
 * Lets the phone's screen timeout run while a reel is paused or stuck (#777).
 *
 * The Reels viewer keeps the screen on through one helper of its own, an instance (boolean) method
 * that gets its activity's window and adds FLAG_KEEP_SCREEN_ON to it when the boolean is true, or
 * clears it when false. The viewer asks for it as it resumes and lets go as it pauses, so a reel
 * you paused keeps the phone awake for as long as the viewer is open. The patch tells the extension
 * about each call, with the window and the boolean, right after the helper gets the window, and
 * tells it each time IgVideoPlayerImpl's playInternal starts a video. The extension then checks
 * every couple of seconds whether any of those players plays, clears the flag when none does, and
 * puts it back the moment one plays. A stub this patch fills asks a player whether it plays. In
 * the default selection with its switch off.
 */
@Suppress("unused")
val reelScreenSleepPatch = bytecodePatch(
    name = "Let the screen sleep when a reel stops",
    description = "Instagram keeps the screen on while Reels is open, even with a reel paused. With this on, " +
        "a paused reel lets your phone's screen timeout run, and a playing one keeps the screen on. Starts off. " +
        "Turn it on in HushGram settings > Reels.",
) {
    // Reels and Playback are at the limit, and this fixes the Reels viewer keeping the screen on while nothing plays.
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelScreenSleep")
        letTheScreenSleep(findReelScreenSleep())
        enableStatus("reelScreenSleep")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$REEL_SCREEN_SLEEP_PATCH: $why")

/**
 * Finds [ReelScreenSleepSites] before anything changes.
 *
 * The helper is the one instance (boolean) void method outside the extension that gets an
 * activity's window, loads [KEEP_SCREEN_ON] and clears that flag on the window. Its getWindow has to
 * be followed by a move-result nothing jumps past, with the window and the boolean in registers a
 * four-bit call can take, and the boolean still the helper's own there. The player is
 * playInternal's class, and whether it plays the one public no-argument boolean of its own that
 * both its pause (String, loading [FRAGMENT_PAUSED] and [SINGLE_TAP_PAUSED]) and its stop (String,
 * boolean, loading [UI_THREAD] and [FRAGMENT_PAUSED]) ask. Anything else fails the patch.
 */
internal fun BytecodePatchContext.findReelScreenSleep(): ReelScreenSleepSites {
    val extension = classDefByOrNull(REEL_SCREEN_SLEEP) ?: refuse("the extension has no $REEL_SCREEN_SLEEP")
    val declared = extension.methods
        .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
        .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    for (hook in listOf(KEEP_AWAKE, SLEEP_STARTED, "$REEL_SCREEN_SLEEP->playing(Ljava/lang/Object;)Z")) {
        if (hook !in declared) refuse("the extension has no public static $hook")
    }

    val helpers = classesCalling("Landroid/view/Window;", "clearFlags", listOf("I"), "V").flatMap { classDef ->
        classDef.methods.filter { it.isKeepAwakeHelper() }
    }
    val helper = helpers.singleOrNull()
        ?: refuse("expected one keep-awake helper, found ${helpers.size}${helpers.joinToString(prefix = ": ") { it.key() }.takeIf { helpers.isNotEmpty() }.orEmpty()}")
    val where = helper.key()
    val code = helper.code()
    val calls = code.indices.filter { code[it].methodKey() == GET_WINDOW }
    val call = calls.singleOrNull() ?: refuse("expected $where to get the window once, found ${calls.size}")
    val result = code.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where doesn't keep the window getWindow answers")
    val index = call + 2
    if (index >= code.size) refuse("$where ends right after it gets the window")
    if (index in helper.jumpTargets()) refuse("something in $where jumps to right after it gets the window")
    val window = (result as OneRegisterInstruction).registerA
    val keep = helper.parameterRegisterNumber(0)
    if (window > 15 || keep > 15) refuse("$where keeps the window in v$window and its boolean in v$keep, past v15")
    helper.requireParameterIntact(REEL_SCREEN_SLEEP_PATCH, 0, listOf(index))

    val playInternal = findPlayInternal(REEL_SCREEN_SLEEP_PATCH)
    val player = playInternal.definingClass
    return ReelScreenSleepSites(KeepAwakeSite(helper, index, window, keep), playInternal, player, findIsPlaying(player))
}

private fun Method.isKeepAwakeHelper(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || parameterTypes.map(Any::toString) != listOf("Z")) return false
    val code = code()
    return code.any { it.methodKey() == GET_WINDOW } && code.any { it.methodKey() == CLEAR_FLAGS } &&
        code.any { it.opcode in NARROW_CONSTANTS && (it as NarrowLiteralInstruction).narrowLiteral == KEEP_SCREEN_ON }
}

/** The no-argument boolean of [player] both its pause and its stop ask, which has to be public. */
private fun BytecodePatchContext.findIsPlaying(player: String): String {
    val playerClass = classDefByOrNull(player) ?: refuse("this build has no $player")
    val instance = playerClass.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    val pauses = instance.filter {
        it.takes(STRING) && it.returnType == "V" && loadsString(it, FRAGMENT_PAUSED) && loadsString(it, SINGLE_TAP_PAUSED)
    }
    val pause = pauses.singleOrNull() ?: refuse("expected one pause (String) in $player, found ${pauses.size}")
    val stops = instance.filter {
        it.takes(STRING, "Z") && it.returnType == "V" && loadsString(it, UI_THREAD) && loadsString(it, FRAGMENT_PAUSED)
    }
    val stop = stops.singleOrNull() ?: refuse("expected one stop (String, boolean) in $player, found ${stops.size}")
    val asked = listOf(pause, stop).map { method ->
        method.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }.mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .filter { it.definingClass == player && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            .map { "${it.definingClass}->${it.name}()Z" }.toSet()
    }
    val playing = asked[0].intersect(asked[1])
    val isPlaying = playing.singleOrNull()
        ?: refuse("expected one ()boolean of $player both its pause and its stop ask, found ${playing.size}: $playing")
    val method = playerClass.methods.firstOrNull { it.key() == isPlaying } ?: refuse("$isPlaying isn't declared in $player")
    if (!AccessFlags.PUBLIC.isSet(playerClass.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) {
        refuse("the extension can't reach $isPlaying, not public")
    }
    return isPlaying
}

/**
 * Only called once [findReelScreenSleep] found everything: the helper tells [KEEP_AWAKE] with its
 * window and its boolean right after it gets the window, playInternal tells [SLEEP_STARTED] with
 * the player after any hooks other patches put first, and the extension's playing stub asks the
 * player.
 */
internal fun BytecodePatchContext.letTheScreenSleep(sites: ReelScreenSleepSites) {
    val site = sites.keepAwake
    mutable(site.method).addInstructions(site.index, "invoke-static { v${site.window}, v${site.keep} }, $KEEP_AWAKE")
    mutable(sites.playInternal).addAfterLeadingHooks("invoke-static/range { p0 .. p0 }, $SLEEP_STARTED")
    fillStub(
        REEL_SCREEN_SLEEP, "playing", "Z", 2,
        """
            instance-of v0, p0, ${sites.player}
            if-eqz v0, :answer
            check-cast p0, ${sites.player}
            invoke-virtual { p0 }, ${sites.isPlaying}
            move-result v0
            :answer
            return v0
        """,
    )
}

private val NARROW_CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

private fun Method.takes(vararg types: String) = parameterTypes.map(Any::toString) == types.toList()

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.methodKey(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)
    ?.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
