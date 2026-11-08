/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.hold

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.tap.FEED_TAP_HANDLER_STRING
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hold for 2x on feed videos"
internal const val FEED_HOLD = "$EXTENSION_PACKAGE/feed/FeedHoldSpeed;"
internal const val FAST = "$FEED_HOLD->fast(I)Z"
internal const val FIXED_STRIP = "$FEED_HOLD->fixedStrip(I)Z"
internal const val STRIP = "$FEED_HOLD->strip(D)D"

/**
 * The feature as a whole, read by a static (UserSession) helper (02o9.A00 on 454's 386014758). The
 * press entry calls that helper once, and a dozen other places in the feed call it too; the patch
 * answers the press entry's call alone, so the rest of the feed stays Instagram's.
 */
internal const val HOLD_FLAG = 0x810ec4000251f6L

/**
 * One for each kind of post the press entry tells apart. It picks one in a branch and reads
 * whichever it picked in one place.
 */
internal val KIND_FLAGS = listOf(0x810ec4000451f8L, 0x810ec4000551f9L, 0x810ec4000351f7L)

/** The speed-up itself, and a carousel's, which skips the other. */
internal const val FAST_FLAG = 0x810ec4000151f5L
internal const val CAROUSEL_FLAG = 0x810ec4000051f4L

/** Whether the strip along each side where a hold counts is a fixed width, about 50dp. */
internal const val FIXED_STRIP_FLAG = 0x20810ec4000651faL

/** The strip as a share of the video's width, when it isn't fixed. Read as a double on every 454 build. */
internal const val STRIP_FLAG = 0x840ec40007038cL

/**
 * Turns on Instagram's own hold for 2x on feed videos, which it keeps behind server flags in the
 * feed's press entry, and makes the whole video count for it. In the default selection with its
 * switch off.
 */
@Suppress("unused")
val holdFeedVideoFastPatch = bytecodePatch(
    name = "Hold for 2x on feed videos",
    description = "Press and hold a video in a feed to play it at 2x with Instagram's own speed-up, and let go " +
        "for normal speed. Starts off. Turn it on in HushGram settings > Feed.",
) {
    // Manager's Feed group is full at the fifteen PatchCategoriesTest allows. The switch is under Feed in settings.
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("feedHoldFast")
        holdFast(findFeedHold())
        enableStatus("feedHoldFast")
    }
}

/**
 * The feed's press entry and what the patch answers there.
 *
 * @property holdCall the index of the press entry's move-result for its call to the hold helper,
 *           and [holdRegister] that move-result's register
 * @property loads every flag read in the press entry, each with the hook that answers it
 */
internal class FeedHold(
    val press: Method,
    val holdCall: Int,
    val holdRegister: Int,
    val loads: List<Pair<FlagLoad, String>>,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private const val VIEW = "Landroid/view/View;"
private const val MEDIA = "Lcom/instagram/feed/media/Media;"

/**
 * The feed tap handler's class holds the press entry, its one instance void (View, Media, point,
 * holder, state) method (or (View, Media, point, state), as on 451): the same lookup the feed tap
 * pause patch makes. In it, the call to the static helper that alone reads [HOLD_FLAG], and one
 * read of each other flag. Fails before anything changes when any of it isn't so.
 */
internal fun BytecodePatchContext.findFeedHold(): FeedHold {
    val handlers = classesLoadingString(FEED_TAP_HANDLER_STRING).flatMap { it.methods }.filter { method ->
        val p = method.parameterTypes.map(Any::toString)
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.implementation != null &&
            p.size in 8..9 && p[0] == VIEW && p[1] == MEDIA && p[p.size - 2] == "I" && p[p.size - 1] == "Z" &&
            loadsString(method, FEED_TAP_HANDLER_STRING)
    }
    val handler = handlers.singleOrNull()
        ?: refuse("expected one feed tap handler loading \"$FEED_TAP_HANDLER_STRING\", found ${handlers.size}")
    val point = handler.parameterTypes[2].toString()
    val holder = handler.parameterTypes[3].toString()
    val state = handler.parameterTypes[4].toString()
    val shapes = listOf(listOf(VIEW, MEDIA, point, state), listOf(VIEW, MEDIA, point, holder, state))
    val presses = classDefBy(handler.definingClass).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.implementation != null &&
            it.parameterTypes.map(Any::toString) in shapes
    }
    val press = presses.singleOrNull()
        ?: refuse("expected one press entry ($VIEW, $MEDIA, $point[, $holder], $state) in ${handler.definingClass}, found ${presses.size}")
    val where = "${press.definingClass}->${press.name}"
    fun FlagLoad.inPress() = type == press.definingClass && name == press.name &&
        parameters == press.parameterTypes.map(Any::toString)

    val holds = findFlagLoads(PATCH, HOLD_FLAG, "Z")
    val reader = holds.singleOrNull() ?: refuse("expected one read of ${HOLD_FLAG.toString(16)}, found ${holds.size}")
    val readerMethod = classDefBy(reader.type).methods.single {
        it.name == reader.name && it.returnType == reader.returnType && it.parameterTypes.map(Any::toString) == reader.parameters
    }
    if (!AccessFlags.STATIC.isSet(readerMethod.accessFlags) || reader.returnType != "Z" || reader.parameters.size != 1) {
        refuse("${reader.type}->${reader.name} reads ${HOLD_FLAG.toString(16)} but isn't a static helper answering it")
    }
    val code = press.implementation!!.instructions.toList()
    val calls = code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) && call != null &&
            call.definingClass == reader.type && call.name == reader.name && call.returnType == "Z" &&
            call.parameterTypes.map(Any::toString) == reader.parameters
    }
    val call = calls.singleOrNull() ?: refuse("expected $where to call ${reader.type}->${reader.name} once, found ${calls.size}")
    val result = code.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) refuse("$where calls ${reader.type}->${reader.name} and drops the answer")

    val loads = mutableListOf<Pair<FlagLoad, String>>()
    fun answer(flag: Long, read: String, hook: String) {
        val found = findFlagLoads(PATCH, flag, read)
        val load = found.singleOrNull() ?: refuse("expected one read of ${flag.toString(16)}, found ${found.size}")
        if (!load.inPress()) refuse("${load.type}->${load.name} reads ${flag.toString(16)}, not $where")
        loads += load to hook
    }
    (KIND_FLAGS + FAST_FLAG + CAROUSEL_FLAG).forEach { answer(it, "Z", FAST) }
    answer(FIXED_STRIP_FLAG, "Z", FIXED_STRIP)
    answer(STRIP_FLAG, "D", STRIP)
    return FeedHold(press, call + 1, (result as OneRegisterInstruction).registerA, loads)
}

/**
 * Has the extension answer each flag read in the press entry, then its call to the hold helper. The
 * call's hook goes in last, after the flags' writes, at an index none of them can move: every flag
 * is read after the call on each build seen, and [answerFlagLoads] writes below its reads.
 */
internal fun BytecodePatchContext.holdFast(found: FeedHold) {
    if (found.loads.any { (load, _) -> load.loadAt <= found.holdCall }) {
        refuse("${found.press.definingClass}->${found.press.name} reads a hold flag before it calls the hold helper")
    }
    answerFlagLoads(found.loads)
    val press = mutableClassDefBy(found.press.definingClass).methods.single {
        it.name == found.press.name && it.parameterTypes.map(Any::toString) == found.press.parameterTypes.map(Any::toString)
    }
    press.addInstructions(
        found.holdCall + 1,
        """
            invoke-static/range { v${found.holdRegister} .. v${found.holdRegister} }, $FAST
            move-result v${found.holdRegister}
        """,
    )
}
