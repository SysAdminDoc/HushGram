/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.direct.ghost.ghostModeEntryPatch
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val LIVE_SEEN_PATCH = "View live anonymously"
internal const val LIVE_SEEN = "$EXTENSION_PACKAGE/stories/LiveSeen;"
internal const val HOLD_LIVE_HEARTBEAT = "$LIVE_SEEN->hold()Z"

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
    strings = listOf(LIVE_WITH_ELIGIBILITY),
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
        "Your own lives still count their viewers. Ghost mode turns it on too. Starts off. Turn it on in HushGram " +
        "settings > Stories.",
    default = true,
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch, ghostModeEntryPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("liveSeen")
        holdViewerHeartbeat(findViewerHeartbeat())
        enableStatus("liveSeen")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$LIVE_SEEN_PATCH: $why")

/** Where the heartbeat tick asks for a viewer's or guest's heartbeat, the local the hook borrows, and the tick's plain return. */
internal class ViewerHeartbeatSite(val tick: MutableMethod, val call: Int, val register: Int, val done: Int)

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
    return ViewerHeartbeatSite(tick, call, register, done)
}

/** While the switch is on, the tick returns as if the heartbeat were done, before it's asked for. */
internal fun holdViewerHeartbeat(site: ViewerHeartbeatSite) {
    site.tick.addInstructionsWithLabels(
        site.call,
        """
            invoke-static { }, $HOLD_LIVE_HEARTBEAT
            move-result v${site.register}
            if-nez v${site.register}, :done
        """,
        ExternalLabel("done", site.tick.getInstruction(site.done)),
    )
}
