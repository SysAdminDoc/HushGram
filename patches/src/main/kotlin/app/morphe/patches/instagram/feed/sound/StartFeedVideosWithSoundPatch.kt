/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.sound

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.sound.findAudioState
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Start feed videos with sound"
internal const val FEED_SOUND = "$EXTENSION_PACKAGE/feed/FeedSound;"
internal const val FEED_START_WITH_SOUND = "$FEED_SOUND->startWithSound(Ljava/lang/Object;)I"

/** A string only the feed video controller loads, in its volume key handler. */
internal const val FEED_CONTROLLER_STRING = "feed_video_crash_when_adjusting_volume"

private const val MEDIA = "Lcom/instagram/feed/media/Media;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Starts feed videos with their sound on, through Instagram's own audio state.
 *
 * Instagram 450's feed video controller starts each video through one method that takes the post's
 * Media, the player holder, three ints and three booleans. It makes the video's state object there
 * and gives it a boolean, whether the video starts with sound, that Instagram works out from the
 * account's audio state (the one the speaker icon flips, off until you tap it). The video's sound
 * icon and the volume the player is prepared with both follow that boolean. The patch asks the
 * extension once the boolean is known, only when it's false. A yes sets it to true and sets the
 * account's audio state to on with its own setter, the call the Reels audio toggle makes when it
 * unmutes, so the videos after it start with sound too, the same as after a tap on the speaker
 * (#504). Setting only the boolean left every video after the first muted.
 *
 * The extension answers yes for the first video each feed controller starts, while the switch is on
 * and the phone's ringer and media volume allow sound. A video you mute turns the account's state
 * off, and the next ones stay muted. A no, the switch off, Pause and a settings screen that isn't
 * ready all leave Instagram's code exactly as it was. Stories don't pass through this method; Reels
 * read the same audio state, as they do after a tap on a feed video's speaker.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val startFeedVideosWithSoundPatch = bytecodePatch(
    name = "Start feed videos with sound",
    description = "Starts videos on Home with their sound on, the same as tapping a video's speaker. Your phone's " +
        "ringer and volume still count. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("feedSound")
        applyFeedSound(findFeedSoundSite())
        enableStatus("feedSound")
    }
}

/**
 * The video start method, the index of the call that gives the video's state object its sound flag,
 * the register holding that flag, the controller's user session field, the account audio state's
 * static factory and its boolean setter, and the three locals the hook may borrow there: the
 * extension's answer, the audio state, and the setter's second boolean where it takes one.
 */
internal class FeedSoundSite(
    val start: Method,
    val index: Int,
    val flag: Int,
    val session: FieldReference,
    val factory: MethodReference,
    val setter: MethodReference,
    val scratch: List<Int>,
)

/**
 * The feed controller is the one class loading [FEED_CONTROLLER_STRING]. Its video start is the one
 * instance method returning void with the parameters (Media, x, y, z, int, int, int, boolean,
 * boolean, boolean) that makes one new state object, by a constructor taking (Media, x, int, int,
 * int, boolean, boolean), and then calls a method of that object taking one boolean, on the
 * register the object was made in. The register that call hands over is the flag, and every write
 * to it before the call must be a 0 or a 1.
 *
 * The controller keeps the account's user session in one instance field of its own, and the account
 * audio state is the one the Reels audio toggle flips ([findAudioState]); its factory and setter
 * must be reachable from the controller's package.
 */
internal fun BytecodePatchContext.findFeedSoundSite(): FeedSoundSite {
    val holders = classesLoadingString(FEED_CONTROLLER_STRING)
    val controller = holders.singleOrNull()
        ?: refuse("expected one class loading \"$FEED_CONTROLLER_STRING\", found ${holders.size}")
    val starts = controller.methods.filter { method ->
        val p = method.parameterTypes.map(Any::toString)
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.implementation != null &&
            p.size == 10 && p[0] == MEDIA && p.slice(4..6).all { it == "I" } && p.slice(7..9).all { it == "Z" }
    }
    val found = starts.mapNotNull { method -> flagCall(method)?.let { method to it } }
    val (start, call) = found.singleOrNull()
        ?: refuse("expected ${controller.type} to hold one video start that sets a sound flag on the state it makes, found ${found.size}")
    val where = "${controller.type}->${start.name}"
    val index = call.index
    if (index in start.jumpTargets()) refuse("something in $where jumps to the sound flag call")
    start.requireThisIntact(PATCH, listOf(index))
    if (call.flag >= start.localRegisterCount()) refuse("$where keeps the sound flag in a parameter register")

    val sessions = controller.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == USER_SESSION }
    val session = sessions.singleOrNull()
        ?: refuse("expected ${controller.type} to keep one $USER_SESSION field, found ${sessions.size}")
    val (factory, _, setter) = findAudioState()
    val stateType = factory.returnType
    val state = classDefByOrNull(stateType) ?: refuse("the audio state $stateType isn't in the app")
    val home = controller.type.substringBeforeLast('/')
    fun reachable(flags: Int) = stateType.substringBeforeLast('/') == home ||
        (AccessFlags.PUBLIC.isSet(flags) && AccessFlags.PUBLIC.isSet(state.accessFlags))
    val factoryMethod = state.methods.singleOrNull {
        it.name == factory.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) && it.returnType == stateType
    }
    val setterMethod = state.methods.singleOrNull {
        it.name == setter.name && !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == setter.parameterTypes.map(Any::toString)
    }
    if (factoryMethod == null || setterMethod == null || AccessFlags.PRIVATE.isSet(factoryMethod.accessFlags) ||
        AccessFlags.PRIVATE.isSet(setterMethod.accessFlags) || !reachable(factoryMethod.accessFlags) ||
        !reachable(setterMethod.accessFlags)
    ) {
        refuse("$where can't reach the audio state $stateType from the feed controller")
    }

    val scratch = start.freeLocalsAt(PATCH, index, 3, except = listOf(call.flag))
    return FeedSoundSite(start, index, call.flag, ImmutableFieldReference.of(session), factory, setter, scratch)
}

private class FlagCall(val index: Int, val flag: Int)

/** The sound flag call in [method], or null when the method doesn't have the one shape described above. */
private fun BytecodePatchContext.flagCall(method: Method): FlagCall? {
    val code = method.code()
    val newest = mutableMapOf<String, Pair<Int, Int>>()
    val made = mutableListOf<Triple<Int, Int, String>>()
    for ((at, instruction) in code.withIndex()) {
        if (instruction.opcode == Opcode.NEW_INSTANCE) {
            val type = (instruction as ReferenceInstruction).reference.toString()
            newest[type] = at to (instruction as OneRegisterInstruction).registerA
            continue
        }
        val called = instruction.methodReference() ?: continue
        if ((instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) || called.name != "<init>") continue
        val p = called.parameterTypes.map(Any::toString)
        if (p.size != 7 || p[0] != MEDIA || p.slice(2..4).any { it != "I" } || p[5] != "Z" || p[6] != "Z") continue
        val (newAt, register) = newest[called.definingClass] ?: continue
        made += Triple(newAt, register, called.definingClass)
    }
    val (newAt, register, type) = made.singleOrNull() ?: return null
    if (classDefByOrNull(type) == null) return null
    val calls = code.indices.filter { at ->
        val ref = code[at].methodReference()
        val call = code[at] as? FiveRegisterInstruction
        at > newAt && code[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && ref != null && ref.definingClass == type &&
            ref.returnType == "V" && ref.parameterTypes.map(Any::toString) == listOf("Z") && call.registerCount == 2 &&
            call.registerC == register
    }
    val at = calls.singleOrNull() ?: return null
    val flag = (code[at] as FiveRegisterInstruction).registerD
    // Nothing rewrites the made object's register between making it and the call.
    for (k in newAt + 1 until at) {
        val written = (code[k] as? OneRegisterInstruction)?.registerA ?: continue
        if (code[k].opcode.setsRegister() && written == register) return null
    }
    // Every write that reaches the call is a plain 0 or 1, so the flag is a real boolean there.
    val writes = writesReaching(method, at, flag)
    if (writes.isEmpty() || writes.any { k ->
            code[k].opcode != Opcode.CONST_4 || (code[k] as NarrowLiteralInstruction).narrowLiteral !in 0..1
        }
    ) {
        return null
    }
    return FlagCall(at, flag)
}

/**
 * The indexes of the instructions that write [register] and whose value can still be in it at
 * instruction [at]: walking back from [at], each path stops at the first write it meets. A write
 * that throws leaves the old value, so the walk goes on past it along an exception edge.
 */
internal fun writesReaching(method: Method, at: Int, register: Int): Set<Int> {
    val flow = ControlFlow.of(method)
    val count = flow.instructions.size
    val normal = Array(count) { mutableListOf<Int>() }
    val thrown = Array(count) { mutableListOf<Int>() }
    for (from in 0 until count) {
        flow.normal[from].filter { it < count }.forEach { normal[it] += from }
        flow.exceptional[from].filter { it < count }.forEach { thrown[it] += from }
    }
    fun writes(k: Int): Boolean {
        val written = (flow.instructions[k] as? OneRegisterInstruction)?.registerA ?: return false
        val opcode = flow.instructions[k].opcode
        return (opcode.setsRegister() && written == register) ||
            (opcode.setsWideRegister() && (written == register || written + 1 == register))
    }
    val found = sortedSetOf<Int>()
    val seen = java.util.BitSet(count)
    val pending = ArrayDeque<Int>().apply { addLast(at) }
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        for (before in normal[next]) {
            if (writes(before)) {
                found += before
            } else if (!seen[before]) {
                seen.set(before)
                pending.addLast(before)
            }
        }
        for (before in thrown[next]) {
            if (!seen[before]) {
                seen.set(before)
                pending.addLast(before)
            }
        }
    }
    return found
}

/**
 * In front of the call that gives the video's state object its sound flag: when the flag is false, ask
 * [FEED_START_WITH_SOUND] with the controller, and on a yes make the flag true, take the account's
 * audio state from the controller's session and set it to on, with false for the setter's second
 * boolean where it takes one, as the story hook does. A flag that is already true, or no session,
 * goes straight on.
 */
internal fun BytecodePatchContext.applyFeedSound(site: FeedSoundSite) {
    val (answer, state, extra) = site.scratch
    val setter = if (site.setter.parameterTypes.size == 2) {
        "const/4 v$extra, 0x0\n                invoke-virtual { v$state, v${site.flag}, v$extra }, ${site.setter}"
    } else {
        "invoke-virtual { v$state, v${site.flag} }, ${site.setter}"
    }
    mutable(site.start).apply {
        addInstructionsWithLabels(
            site.index,
            """
                if-nez v${site.flag}, :stock
                invoke-static/range { p0 .. p0 }, $FEED_START_WITH_SOUND
                move-result v$answer
                if-eqz v$answer, :stock
                const/4 v${site.flag}, 1
                move-object/from16 v$state, p0
                iget-object v$state, v$state, ${site.session}
                if-eqz v$state, :stock
                invoke-static { v$state }, ${site.factory}
                move-result-object v$state
                $setter
            """,
            ExternalLabel("stock", getInstruction(site.index)),
        )
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
