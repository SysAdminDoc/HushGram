/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.keep

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val KEEP_IN_CHAT_PATCH = "Keep in chat"
internal const val KEEP_IN_CHAT = "$EXTENSION_PACKAGE/direct/KeepInChat;"
internal const val VIEW_MODE_HOOK = "$KEEP_IN_CHAT->viewMode(Ljava/lang/String;)Ljava/lang/String;"

/** The JSON key of a photo or video message's view mode, and keys only that message's media has with it. */
internal const val VIEW_MODE = "view_mode"
/** Its keys. seen_user_ids sets it apart from the voice message parser, which reads the first four too. */
internal val VISUAL_MEDIA_KEYS = listOf(VIEW_MODE, "seen_count", "url_expire_at_secs", "expiring_media_action_summary",
    "seen_user_ids")

/** The parser of a photo or video message's media: the one (reader) parse returning an object and holding its keys. */
internal object VisualMediaParseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = VISUAL_MEDIA_KEYS,
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

@Suppress("unused")
val keepInChatPatch = bytecodePatch(
    name = "Keep in chat",
    description = "Keeps view once and replayable photos and videos in your chats, as if they'd been sent with Keep " +
        "in chat, so they don't disappear after you open them. Turning it on or off reaches the ones already loaded " +
        "once the chat loads again. Off until you turn it on. Switch: HushGram settings > Messages.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("keepInChat")
        val (parse, store) = findViewModeStore()
        keepViewModeInChat(parse, store)
        enableStatus("keepInChat")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$KEEP_IN_CHAT_PATCH: $why")

private fun Instruction.string(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) ((this as ReferenceInstruction).reference as StringReference).string else null

/**
 * Where the parser of a photo or video message's media stores the view mode it read: the first
 * object store after the parser loads "view_mode", into a text field of the media it builds, and
 * the parser's one store into that field. Nothing may jump straight to the store, which would skip
 * the hook put in front of it.
 */
internal fun BytecodePatchContext.findViewModeStore(): Pair<MutableMethod, Int> {
    val parse = uniqueMethod(KEEP_IN_CHAT_PATCH, "photo and video message parser", VisualMediaParseFingerprint)
    val code = parse.implementation!!.instructions.toList()
    val key = code.indices.filter { code[it].string() == VIEW_MODE }.singleOrNull()
        ?: refuse("the parser doesn't load \"$VIEW_MODE\" once")
    val store = (key + 1 until code.size).firstOrNull { code[it].opcode == Opcode.IPUT_OBJECT }
        ?: refuse("the parser stores no view mode")
    val field = (code[store] as ReferenceInstruction).reference as FieldReference
    if (field.type != "Ljava/lang/String;") refuse("the parser's view mode isn't stored as text")
    val built = code.firstOrNull { it.opcode == Opcode.NEW_INSTANCE }?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
    if (field.definingClass != built) refuse("the view mode goes into ${field.definingClass}, not the media the parser builds")
    val stores = code.count { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference == field }
    if (stores != 1) refuse("the parser stores the view mode $stores times")
    if (store in parse.jumpTargets()) refuse("something jumps straight to the view mode's store")
    if ((code[store] as TwoRegisterInstruction).registerA > 15) refuse("the view mode is past v15")
    return parse to store
}

/** The view mode passes through the extension on its way into the media. */
internal fun keepViewModeInChat(parse: MutableMethod, store: Int) {
    val mode = (parse.implementation!!.instructions[store] as TwoRegisterInstruction).registerA
    parse.addInstructions(
        store,
        """
            invoke-static { v$mode }, $VIEW_MODE_HOOK
            move-result-object v$mode
        """,
    )
}
