/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.comment.arguments
import app.morphe.patches.instagram.misc.comment.call
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.field
import app.morphe.patches.instagram.misc.comment.reference
import app.morphe.patches.instagram.misc.comment.writes
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val BLEND_AFTER_CHAT = "$CHAT_REELS->blendAfterChat(Ljava/lang/Object;Ljava/lang/String;)Z"

/**
 * What the source of a Blend opened from a chat names its feed by, in its constructor, followed by the
 * chat's id (456's 0OxG). Only that constructor loads it.
 */
internal const val BLEND_FEED = "blend_feed_direct_thread_"

private const val BLEND_TEXT = "Ljava/lang/String;"
private const val BLEND_NUMBER = "Ljava/lang/Integer;"

private fun refuseBlend(detail: String): Nothing = throw PatchException("Swipe through a chat's reels: $detail")

/**
 * Where the Reels viewer picks the source of a reel opened from a chat that has a Blend (#804). The
 * picker (456's 06CU.A00) builds the chat's own source, which pages the chat's shared reels and then
 * its second side, only when the config holds no Blend. With a Blend it builds the Blend's source
 * instead, which pages the Blend's reels, so the chat's other shared reels never come. Once Instagram
 * has loaded the chat's Blend, a reel tapped in the chat opens with the Blend's viewer source instead
 * of the chat's (457's 0lNl), still holding the chat, and the picker builds the same Blend source for it.
 *
 * [after] is the instruction after the Blend source's constructor, [blend] the register holding the
 * new source and [config] the picker's config parameter. The config's fields: where it was opened
 * ([configSource]), its dual paging switch ([dual]) and its chat ([direct]), whose [thread] id and
 * [position] the chat's source takes, [firstPosition] when the chat has none. The chat's source class
 * [chat] is built through [chatInit] and holds the chat's id ([chatThread]), its second side
 * ([chatSecond]) and the position ([chatPosition]). [blendThread] is the Blend source's chat id, set
 * only when Instagram already has it page the chat's reels itself.
 */
internal class BlendSites(
    val picker: MutableMethod,
    val after: Int,
    val blend: Int,
    val config: Int,
    val configSource: String,
    val dual: String,
    val direct: String,
    val thread: String,
    val position: String,
    val firstPosition: String,
    val chat: String,
    val chatInit: String,
    val chatThread: String,
    val chatSecond: String,
    val chatPosition: String,
    val blendThread: String,
    val scratch: List<Int>,
)

/**
 * Finds [BlendSites] for the chat's source [chat], what [findChatReelsEnd] found. The Blend source is
 * the one class whose constructor loads [BLEND_FEED], and the picker the one method building both it
 * and [chat] from a [VIEWER_CONFIG] parameter. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findBlendAfterChat(chat: String): BlendSites {
    val blends = classesLoadingString(BLEND_FEED).filter { holder ->
        holder.methods.any { it.name == "<init>" && loadsString(it, BLEND_FEED) }
    }
    val blend = blends.singleOrNull()?.type ?: refuseBlend("expected one class whose constructor loads $BLEND_FEED, found ${blends.size}")

    fun builds(method: Method, type: String) = method.code().count { it.opcode == Opcode.NEW_INSTANCE && it.reference()?.toString() == type }
    val pickers = methodsCalling(blend, "<init>").distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }.filter { method ->
        builds(method, blend) == 1 && builds(method, chat) == 1 && method.parameterTypes.count { "$it" == VIEWER_CONFIG } == 1
    }
    val found = pickers.singleOrNull() ?: refuseBlend("expected one method building $blend and $chat from a config, found ${pickers.size}")
    val picker = mutableClassDefBy(found.definingClass).methods.single { method ->
        method.name == found.name && method.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString)
    }
    val where = "${picker.definingClass}->${picker.name}"
    val code = picker.code()

    // The Blend source: its new instance, its constructor, and the register keeping it.
    val made = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && it.reference()?.toString() == blend }
    val blendRegister = (code[made] as OneRegisterInstruction).registerA
    val init = (made + 1 until code.size).firstOrNull { at ->
        code[at].opcode in setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE) && code[at].call()?.let { it.definingClass == blend && it.name == "<init>" } == true
    } ?: refuseBlend("$where never calls the constructor of the $blend it makes")
    if ((made + 1..init).any { code[it].writes(blendRegister) }) refuseBlend("$where writes v$blendRegister before $blend is built")
    val after = init + 1
    if (after >= code.size || after in picker.jumpTargets()) refuseBlend("something in $where jumps to the instruction after $blend is built")

    // The chat's source: its new instance, the constructor it calls, and the three fields it sets.
    val chatMade = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && it.reference()?.toString() == chat }
    val chatRegister = (code[chatMade] as OneRegisterInstruction).registerA
    val chatInit = code.getOrNull(chatMade + 1)?.takeIf { it.opcode == Opcode.INVOKE_DIRECT && it.arguments() == listOf(chatRegister) }
        ?.call()?.toString() ?: refuseBlend("$where doesn't build its $chat right after making it")
    val sets = (chatMade + 2 until minOf(code.size, chatMade + 8)).map { code[it] }
        .takeWhile { it.opcode == Opcode.IPUT_OBJECT }
        .filter { (it as TwoRegisterInstruction).registerB == chatRegister && it.field()?.definingClass == chat }
        .mapNotNull { it.field() }
    fun chatField(what: String, test: (String) -> Boolean): FieldReference =
        sets.filter { test(it.type) }.singleOrNull() ?: refuseBlend("$where sets ${sets.count { test(it.type) }} $what fields of $chat, not one")
    val chatThread = chatField("text") { it == BLEND_TEXT }
    val chatPosition = chatField("number") { it == BLEND_NUMBER }
    val chatSecond = chatField("source") { it != BLEND_TEXT && it != BLEND_NUMBER && it.startsWith("L") }
    if (sets.size != 3) refuseBlend("$where sets ${sets.size} fields of $chat, not 3")

    // The config's dual paging switch, the last of its flags read before the chat's source is made.
    val dual = (chatMade - 1 downTo 0).map { code[it] }.firstOrNull { it.opcode == Opcode.IGET_BOOLEAN && it.field()?.definingClass == VIEWER_CONFIG }
        ?.field() ?: refuseBlend("$where reads no flag of its config before making $chat")

    // The chat's id and position, the one text and the one number of the chat the picker reads.
    fun directRead(type: String): FieldReference {
        val reads = code.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.field() }
            .filter { it.definingClass == DIRECT_DATA && it.type == type }.distinctBy { it.name }
        return reads.singleOrNull() ?: refuseBlend("$where reads ${reads.size} $type fields of $DIRECT_DATA, not one")
    }
    val thread = directRead(BLEND_TEXT)
    val position = directRead(BLEND_NUMBER)
    val positionAt = code.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && it.field() == position }
    val firstPosition = (positionAt + 1 until chatMade).map { code[it] }
        .firstOrNull { it.opcode == Opcode.SGET_OBJECT && it.field()?.type == BLEND_NUMBER }?.field()
        ?: refuseBlend("$where has no number standing in for a chat without a position")

    val directs = classDefBy(VIEWER_CONFIG).fields.filter { it.type == DIRECT_DATA && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val direct = directs.singleOrNull() ?: refuseBlend("$VIEWER_CONFIG holds ${directs.size} $DIRECT_DATA fields, not one")
    val sources = classDefBy(VIEWER_CONFIG).fields.filter { it.type == VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: refuseBlend("$VIEWER_CONFIG holds ${sources.size} $VIEWER_SOURCE fields, not one")

    // The Blend source's own chat id: the field its page of the chat's reels tests, then hands the request.
    val blendThreads = classDefBy(blend).methods.mapNotNull { method ->
        val body = method.code()
        body.withIndex().firstNotNullOfOrNull { (at, instruction) ->
            val read = instruction.field()
            val register = (instruction as? OneRegisterInstruction)?.registerA
            if (instruction.opcode != Opcode.IGET_OBJECT || read?.definingClass != blend || read.type != BLEND_TEXT || register == null) return@firstNotNullOfOrNull null
            // The test comes within a few instructions (a copy may sit between), and the request takes
            // the field's value, still in its register, after it.
            val test = (at + 1..minOf(at + 3, body.size - 1)).firstOrNull { body[it].opcode == Opcode.IF_EQZ && (body[it] as OneRegisterInstruction).registerA == register }
                ?.takeIf { end -> (at + 1 until end).none { body[it].writes(register) } } ?: return@firstNotNullOfOrNull null
            val call = (test + 1 until body.size).firstOrNull { index ->
                body[index].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && register in body[index].arguments()
            } ?: return@firstNotNullOfOrNull null
            val handed = (test + 1 until call).none { body[it].writes(register) } &&
                body[call].call()?.parameterTypes?.firstOrNull()?.toString() == "Lcom/instagram/common/session/UserSession;"
            read.takeIf { handed }
        }
    }.distinctBy { it.name }
    val blendThread = blendThreads.singleOrNull() ?: refuseBlend("$blend tests and hands on ${blendThreads.size} chat ids of its own, not one")

    val configIndex = found.parameterTypes.indexOfFirst { "$it" == VIEWER_CONFIG }
    picker.requireParameterIntact("Swipe through a chat's reels", configIndex, listOf(after))
    val scratch = picker.freeLocalsAt("Swipe through a chat's reels", after, 4, except = listOf(blendRegister))

    return BlendSites(
        picker, after, blendRegister, picker.parameterRegisterNumber(configIndex),
        "$VIEWER_CONFIG->${source.name}:$VIEWER_SOURCE", "$dual", "$VIEWER_CONFIG->${direct.name}:$DIRECT_DATA",
        "$thread", "$position", "$firstPosition", chat, chatInit, "$chatThread", "$chatSecond", "$chatPosition", "$blendThread",
        scratch,
    )
}

/**
 * Right after the picker builds a Blend's source, asks [BLEND_AFTER_CHAT] with where the viewer was
 * opened and the chat's id, when the config pages two sides, holds a chat with an id, and the Blend
 * source doesn't page that chat's reels itself. On a yes it builds the chat's own source the way the
 * picker would without a Blend, with the Blend's source as its second side, so the chat's shared reels
 * come first and the Blend's after them. Anything else keeps Instagram's Blend source.
 */
internal fun BytecodePatchContext.keepChatReelsBeforeBlend(found: BlendSites) {
    val (a, b, c, d) = found.scratch
    found.picker.addInstructionsWithLabels(
        found.after,
        """
            move-object/from16 v$a, v${found.config}
            iget-object v$b, v$a, ${found.configSource}
            iget-boolean v$c, v$a, ${found.dual}
            if-eqz v$c, :instagram
            iget-object v$a, v$a, ${found.direct}
            if-eqz v$a, :instagram
            iget-object v$c, v$a, ${found.thread}
            if-eqz v$c, :instagram
            move-object/from16 v$d, v${found.blend}
            iget-object v$d, v$d, ${found.blendThread}
            if-nez v$d, :instagram
            invoke-static {v$b, v$c}, $BLEND_AFTER_CHAT
            move-result v$b
            if-eqz v$b, :instagram
            iget-object v$a, v$a, ${found.position}
            if-nez v$a, :position
            sget-object v$a, ${found.firstPosition}
            :position
            new-instance v$b, ${found.chat}
            invoke-direct {v$b}, ${found.chatInit}
            iput-object v$c, v$b, ${found.chatThread}
            move-object/from16 v$d, v${found.blend}
            iput-object v$d, v$b, ${found.chatSecond}
            iput-object v$a, v$b, ${found.chatPosition}
            move-object/from16 v${found.blend}, v$b
            :instagram
            nop
        """,
    )
}
