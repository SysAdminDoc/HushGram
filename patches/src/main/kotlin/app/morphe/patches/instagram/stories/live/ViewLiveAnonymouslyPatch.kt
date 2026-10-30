/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.direct.ghost.ghostModeEntryPatch
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val LIVE_SEEN_PATCH = "View live anonymously"
internal const val LIVE_SEEN = "$EXTENSION_PACKAGE/stories/LiveSeen;"
internal const val HOLD_LIVE_HEARTBEAT = "$LIVE_SEEN->hold(Ljava/lang/String;)Z"
internal const val LIVE_SEEN_BUTTON = "$EXTENSION_PACKAGE/stories/LiveSeenButton;"
internal const val BIND_LIVE_SEEN_BUTTON = "$LIVE_SEEN_BUTTON->bind(Landroid/view/View;)V"

/**
 * The live header's three-dot menu and close button (`id/iglive_header_options` and
 * `id/iglive_header_close`), the same in all eight 453 builds (aapt2). The live viewer's header
 * holds both; the host's own header has only the close button.
 */
internal const val LIVE_HEADER_OPTIONS = 0x7f0b1f5f
internal const val LIVE_HEADER_CLOSE = 0x7f0b1f57
internal const val VIEW = "Landroid/view/View;"

/**
 * Instagram's live heartbeat manager, which keeps its name. Each tick tells the server who's in a
 * live: the host's own tick, and a viewer's or guest's, which is what puts them on the viewer list.
 */
internal const val LIVE_HEARTBEAT_MANAGER = "Lcom/instagram/video/live/mvvm/model/repository/core/IgLiveHeartbeatManager;"

/** What a viewer's or guest's heartbeat adds to its request, which the host's doesn't. */
internal const val LIVE_WITH_ELIGIBILITY = "live_with_eligibility"

/** (tick, role, broadcast id, continuation): how the viewer's and guest's heartbeat is asked for. */
internal const val INTEGER = "Ljava/lang/Integer;"
internal const val STRING = "Ljava/lang/String;"
internal const val OBJECT = "Ljava/lang/Object;"

/** The heartbeat a viewer or guest sends: the one (tick, role, broadcast id, continuation) method naming [LIVE_WITH_ELIGIBILITY]. */
internal object ViewerHeartbeatFingerprint : Fingerprint(
    returnType = OBJECT,
    filters = listOf(string(LIVE_WITH_ELIGIBILITY)),
    custom = { method, _ ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        !AccessFlags.STATIC.isSet(method.accessFlags) && parameters.size == 4 &&
            parameters[1] == INTEGER && parameters[2] == STRING
    },
)

@Suppress("unused")
val viewLiveAnonymouslyPatch = bytecodePatch(
    name = "View live anonymously",
    description = "Keeps you off the viewer list of the lives you watch, so the host isn't told you're there. " +
        "Your own lives still count their viewers. An optional eye button at the top of a live puts you on that " +
        "one's list. Ghost mode turns it on too. Starts off. Turn it on in HushGram settings > Stories.",
    default = true,
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch, ghostModeEntryPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("liveSeen")
        val heartbeat = findViewerHeartbeat()
        val header = findLiveHeader()
        holdViewerHeartbeat(heartbeat)
        bindLiveSeenButton(header)
        enableStatus("liveSeen")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$LIVE_SEEN_PATCH: $why")

/**
 * Where the heartbeat tick asks for a viewer's or guest's heartbeat, the register holding the live's
 * broadcast id there, the local the hook borrows, and the tick's plain return.
 */
internal class ViewerHeartbeatSite(val tick: MutableMethod, val call: Int, val broadcast: Int, val register: Int, val done: Int)

/** The live viewer header's constructor and its one plain return, where the button is bound. */
internal class LiveHeaderSite(val constructor: MutableMethod, val end: Int)

private fun MutableMethod.code() = implementation!!.instructions.toList()

/**
 * The heartbeat manager's tick and its one call asking for a viewer's or guest's heartbeat. Proved
 * before anything changes: the tick is the manager's one static method making that call, the call
 * isn't something a jump lands on, a local is free in front of it, and the tick has one place that
 * returns its plain result, a singleton loaded right before it's returned, for the hook to jump to.
 */
internal fun BytecodePatchContext.findViewerHeartbeat(): ViewerHeartbeatSite {
    val heartbeat = uniqueMethod(LIVE_SEEN_PATCH, "viewer heartbeat", ViewerHeartbeatFingerprint)
    val manager = mutableClassDefByOrNull(LIVE_HEARTBEAT_MANAGER) ?: refuse("$LIVE_HEARTBEAT_MANAGER is missing")
    fun MethodReference.isHeartbeat() = definingClass == heartbeat.definingClass && name == heartbeat.name &&
        parameterTypes.map(CharSequence::toString) == heartbeat.parameterTypes.map(CharSequence::toString) &&
        returnType == heartbeat.returnType
    val ticks = manager.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == OBJECT && method.implementation != null &&
            method.code().any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.isHeartbeat() == true }
    }
    val tick = ticks.singleOrNull() ?: refuse("expected one heartbeat tick asking for a viewer's heartbeat, found ${ticks.size}")
    val code = tick.code()
    val calls = code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.isHeartbeat() == true }
    val call = calls.singleOrNull() ?: refuse("expected one viewer heartbeat call in the tick, found ${calls.size}")
    if (call in tick.jumpTargets()) refuse("something jumps to the tick's viewer heartbeat call")
    val returns = code.indices.filter { at ->
        val load = code[at]
        val field = (load as? ReferenceInstruction)?.reference as? FieldReference
        load.opcode == Opcode.SGET_OBJECT && field != null && field.type == field.definingClass &&
            code.getOrNull(at + 1)?.opcode == Opcode.RETURN_OBJECT &&
            (code[at + 1] as OneRegisterInstruction).registerA == (load as OneRegisterInstruction).registerA
    }
    val done = returns.singleOrNull() ?: refuse("expected one plain return in the heartbeat tick, found ${returns.size}")
    val register = tick.freeLocalsAt(LIVE_SEEN_PATCH, call, 1, listOf(done), highest = 255).single()
    return ViewerHeartbeatSite(tick, call, broadcastRegister(code[call]), register, done)
}

/** The register a heartbeat call passes the broadcast id in: its fourth, after the heartbeat's owner, the tick and the role. */
private fun broadcastRegister(call: Instruction): Int = when (call) {
    is FiveRegisterInstruction -> call.registerF
    is RegisterRangeInstruction -> call.startRegister + 3
    else -> refuse("the viewer heartbeat call takes no registers")
}

/**
 * The live viewer header's constructor: the one constructor taking a root view first that looks up
 * both the header's three-dot menu and its close button, with one plain return, and the root view
 * still in its parameter register there.
 */
internal fun BytecodePatchContext.findLiveHeader(): LiveHeaderSite {
    val holders = classesLoading(LIVE_HEADER_OPTIONS.toLong()).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == "<init>" && method.parameterTypes.firstOrNull()?.toString() == VIEW && method.implementation != null &&
                method.implementation!!.instructions.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
                    .containsAll(listOf(LIVE_HEADER_OPTIONS, LIVE_HEADER_CLOSE))
        }.map { classDef to it }
    }
    val (owner, found) = holders.singleOrNull()
        ?: refuse("expected one live header constructor looking up its menu and close button, found ${holders.size}")
    val constructor = mutableClassDefBy(owner.type).methods.single {
        it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameterTypes.map(CharSequence::toString)
    }
    val code = constructor.code()
    val ends = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    val end = ends.singleOrNull() ?: refuse("expected one return in the live header constructor, found ${ends.size}")
    constructor.requireParameterIntact(LIVE_SEEN_PATCH, 0, listOf(end))
    return LiveHeaderSite(constructor, end)
}

/** While the switch is on, the tick returns as if the heartbeat were done, before it's asked for, unless the live is marked. */
internal fun holdViewerHeartbeat(site: ViewerHeartbeatSite) {
    site.tick.addInstructionsWithLabels(
        site.call,
        """
            invoke-static/range { v${site.broadcast} .. v${site.broadcast} }, $HOLD_LIVE_HEARTBEAT
            move-result v${site.register}
            if-nez v${site.register}, :done
        """,
        ExternalLabel("done", site.tick.getInstruction(site.done)),
    )
}

/** The header's constructor hands its root view to the button last, once every view it looks up is found. */
internal fun bindLiveSeenButton(site: LiveHeaderSite) {
    val root = site.constructor.parameterRegister(0)
    site.constructor.addInstructions(site.end, "invoke-static/range { $root .. $root }, $BIND_LIVE_SEEN_BUTTON")
}
