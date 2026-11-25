/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.field
import app.morphe.patches.instagram.misc.comment.writes
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.poolAsks
import app.morphe.patches.instagram.misc.extension.pooledString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The extension's test of whether a Reels viewer is a chat's, read off its config. */
internal const val VIEWER_CHAT = "$EXTENSION_PACKAGE/direct/ViewerChat;"

/**
 * What the chat data's toString names the chat's id by, right before appending it. Instagram's build
 * keeps the generated toString of its data classes, field names included.
 */
internal const val THREAD_ID_LABEL = ", threadId="

private const val TEXT = "Ljava/lang/String;"
private const val APPEND_TEXT = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"

private fun refuseViewerChat(detail: String): Nothing = throw PatchException("Reels viewers from chats: $detail")

/**
 * Writes the extension's bridges reading whether a Reels viewer is a chat's, for every patch keyed on a
 * chat's viewer: Swipe through a chat's reels, Clean up Reels' reply bar, Stop Reels scrolling and Open
 * reel links on that reel. Each depends on it, so the bridges are written once whichever of them run.
 * A build it can't read keeps the stubs, which read no viewer as a chat's, with a warning, so the
 * patches depending on it still go in.
 */
internal val viewerChatPatch = bytecodePatch {
    dependsOn(instagramExtensionPatch)

    execute {
        val found = try {
            findViewerChat()
        } catch (refusal: PatchException) {
            patchLog.warning("${refusal.message}. Reels opened from a chat are read as any other viewer's.")
            return@execute
        }
        readViewerChats(found)
    }
}

/**
 * A Reels viewer config's fields that tell a chat's viewer: where it was opened ([source]) and the chat
 * it holds ([direct]), whose id is [thread]. Each is a `class->name:type` reference.
 */
internal class ViewerChatFields(val source: String, val direct: String, val thread: String)

/**
 * Finds [ViewerChatFields] before anything changes. The [VIEWER_CONFIG] holds one [VIEWER_SOURCE] and
 * one [DIRECT_DATA], and the chat's id is the text field the chat data's toString appends right after
 * [THREAD_ID_LABEL] (457's ClipsViewerDirectData->A04). Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findViewerChat(): ViewerChatFields {
    val config = classDefByOrNull(VIEWER_CONFIG) ?: refuseViewerChat("$VIEWER_CONFIG isn't in this build")
    fun configField(type: String): String {
        val fields = config.fields.filter { it.type == type && !AccessFlags.STATIC.isSet(it.accessFlags) }
        val field = fields.singleOrNull() ?: refuseViewerChat("$VIEWER_CONFIG holds ${fields.size} $type fields, not one")
        return "$VIEWER_CONFIG->${field.name}:$type"
    }
    val source = configField(VIEWER_SOURCE)
    val direct = configField(DIRECT_DATA)

    val data = classDefByOrNull(DIRECT_DATA) ?: refuseViewerChat("$DIRECT_DATA isn't in this build")
    val texts = data.methods.filter {
        it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == TEXT && it.implementation != null &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val text = texts.singleOrNull() ?: refuseViewerChat("$DIRECT_DATA has ${texts.size} toString methods, not one")
    val code = text.code()
    val where = "$DIRECT_DATA->toString"
    // Redex pools the label on some builds of one version (386314286 asks LX/0U67;->A00(I) for it), so
    // a pool call's result counts as the label too, read where the call's move-result lands it.
    val pooled = poolAsks(code).filter { (at, number) ->
        pooledString((code[at] as ReferenceInstruction).reference as MethodReference, number) == THREAD_ID_LABEL
    }.mapNotNull { (at, _) -> (at + 1).takeIf { code.getOrNull(it)?.opcode == Opcode.MOVE_RESULT_OBJECT } }
    val labels = code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == THREAD_ID_LABEL } + pooled
    val label = labels.singleOrNull() ?: refuseViewerChat("$where names \"$THREAD_ID_LABEL\" ${labels.size} times, not once")
    val appends = (label + 1 until code.size).filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL && (code[at] as? ReferenceInstruction)?.reference.let { it is MethodReference && it.name == "append" }
    }
    val labelRegister = (code[label] as OneRegisterInstruction).registerA
    appends.getOrNull(0)?.takeIf { at ->
        "${(code[at] as ReferenceInstruction).reference}" == APPEND_TEXT && (code[at] as FiveRegisterInstruction).registerD == labelRegister &&
            (label + 1 until at).none { code[it].writes(labelRegister) }
    } ?: refuseViewerChat("$where doesn't append \"$THREAD_ID_LABEL\" right after loading it")
    val idAppend = appends.getOrNull(1)?.takeIf { "${(code[it] as ReferenceInstruction).reference}" == APPEND_TEXT }
        ?: refuseViewerChat("$where doesn't append a text right after \"$THREAD_ID_LABEL\"")
    val thread = readBefore(code, idAppend, (code[idAppend] as FiveRegisterInstruction).registerD)
        ?: refuseViewerChat("$where appends a text after \"$THREAD_ID_LABEL\" that isn't one of its own fields")
    val jumps = text.jumpTargets()
    if ((thread.first + 1..idAppend).any { it in jumps }) {
        refuseViewerChat("$where branches between reading the chat's id and appending it")
    }
    if (thread.second.let { it.definingClass != DIRECT_DATA || it.type != TEXT }) {
        refuseViewerChat("$where appends ${thread.second} after \"$THREAD_ID_LABEL\", not a text field of its own")
    }
    return ViewerChatFields(source, direct, "${thread.second}")
}

/**
 * The field read whose value reaches [register] at instruction [at] of [code], following copies back,
 * with the index of the read. Null when something else last wrote it.
 */
private fun readBefore(code: List<Instruction>, at: Int, register: Int): Pair<Int, FieldReference>? {
    var held = register
    for (index in at - 1 downTo 0) {
        val instruction = code[index]
        if (!instruction.writes(held)) continue
        when (instruction.opcode) {
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> held = (instruction as TwoRegisterInstruction).registerB
            Opcode.IGET_OBJECT -> return instruction.field()?.let { index to it }
            else -> return null
        }
    }
    return null
}

/**
 * Writes [VIEWER_CHAT]'s bridges reading a viewer's config: where the viewer was opened,
 * [ViewerChatFields.source], and the id of the chat it holds, [ViewerChatFields.thread] of its
 * [ViewerChatFields.direct], or null when it holds none. The hooks keyed on a chat's viewer hand the
 * extension the config rather than where it was opened, since a reel tapped in a chat opens as its
 * Blend's once Instagram has loaded the Blend (#804), and the extension asks these.
 */
internal fun BytecodePatchContext.readViewerChats(found: ViewerChatFields) {
    val stubs = mutableClassDefBy(VIEWER_CHAT).methods
    fun bridge(name: String, returns: String) = stubs.singleOrNull {
        it.name == name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") && it.returnType == returns &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuseViewerChat("the extension has no public static $name bridge reading a viewer's config")
    bridge("viewerSource", "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, $VIEWER_CONFIG
            iget-object p0, p0, ${found.source}
            return-object p0
        """,
    )
    bridge("chatThread", TEXT).addInstructionsWithLabels(
        0,
        """
            check-cast p0, $VIEWER_CONFIG
            iget-object p0, p0, ${found.direct}
            if-nez p0, :read
            const/4 p0, 0x0
            return-object p0
            :read
            iget-object p0, p0, ${found.thread}
            return-object p0
        """,
    )
}

/**
 * Where a Reels viewer part keeps its viewer's config, one object away: its [holder] field, and that
 * object's [config] field, each a `class->name:type` reference. On 457 the chat reply bar's controller
 * (LX/0RL1; on 386314341) reads it as A11, the viewer's state LX/0BwL;, then its A0a.
 */
internal class ViewerConfigPath(val holder: String, val config: String)

/**
 * The [ViewerConfigPath] of [owner], a class whose own instance fields include exactly one whose class
 * holds exactly one final instance [VIEWER_CONFIG] field, the config it was made with. A config field
 * that isn't final is set later and may be none, as on 457's audio player (LX/0ASt;->A0E, which the
 * reply bar's controller also holds), so it isn't a way. The extension reads it, so both fields and
 * the holder's class are public. Fails naming [what] when any of it isn't so.
 */
internal fun BytecodePatchContext.viewerConfigPath(what: String, owner: String): ViewerConfigPath {
    val kept = classDefByOrNull(owner) ?: throw PatchException("$what: $owner isn't in this build")
    fun instance(flags: Int) = !AccessFlags.STATIC.isSet(flags)
    val paths = kept.fields.filter { instance(it.accessFlags) }.flatMap { holder ->
        classDefByOrNull(holder.type)?.fields?.toList().orEmpty().filter {
            it.type == VIEWER_CONFIG && instance(it.accessFlags) && AccessFlags.FINAL.isSet(it.accessFlags)
        }.map { holder to it }
    }
    val (holder, config) = paths.singleOrNull()
        ?: throw PatchException("$what: expected one field of $owner holding the viewer's config, found ${paths.size}")
    val held = classDefByOrNull(holder.type)
    if (!AccessFlags.PUBLIC.isSet(config.accessFlags) || !AccessFlags.PUBLIC.isSet(holder.accessFlags) ||
        held == null || !AccessFlags.PUBLIC.isSet(held.accessFlags)
    ) {
        throw PatchException("$what: ${holder.name}, ${holder.type} or its ${config.name}, the way to the viewer's config, isn't public")
    }
    return ViewerConfigPath(
        "${holder.definingClass}->${holder.name}:${holder.type}",
        "${config.definingClass}->${config.name}:${config.type}",
    )
}
