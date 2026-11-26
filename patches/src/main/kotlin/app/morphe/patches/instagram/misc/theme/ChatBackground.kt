/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload

private const val PATCH = "Accent color"

/**
 * Instagram's id/thread_background_view_container, the view behind an opened chat's messages that the
 * chat's theme paints: 0x7f0b41eb in 457's 386314341, 386314297 and 386314286 (aapt2), 0x7f0b41ec in its
 * armeabi-v7a 386314340, whose 0x7f0b41eb is thread_background_view (the other builds' 0x7f0b41ec is
 * the overlay, so the finder still wants the one painting method). 456.0 had it at 0x7f0b4205 in 386214534 and
 * 386214574 and at 0x7f0b4206 in 386214562 and 386214575, 456.1 at 0x7f0b4205 in every build. Builds of one
 * version can differ here, so read them again each update.
 */
internal val THREAD_BACKGROUND_IDS = listOf(0x7f0b41ebL, 0x7f0b41ecL)

/** The extension's hook given an opened chat's background layers (#528). */
internal const val CHAT_BACKGROUND = "$ACCENT_THEME->chatBackground(Landroid/graphics/drawable/Drawable;)V"

private const val LAYER_DRAWABLE = "Landroid/graphics/drawable/LayerDrawable;"

/** The end of the static call that hands a drawable to a view, the chat background's among them. */
private const val HANDS_TO_VIEW = "(Landroid/graphics/drawable/Drawable;Landroid/view/View;I)V"

/**
 * The method painting an opened chat's background: the index of the static call handing the layers it
 * built to the view, and the layers' register.
 */
internal class ChatBackgroundSite(val method: Method, val call: Int, val layers: Int)

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * The one method loading an id of [THREAD_BACKGROUND_IDS] that builds a LayerDrawable and hands it,
 * unchanged, to a static call taking a drawable, a view and an int (456's 04v3.A0D on 386214574, which
 * stacks the chat theme's color under its own layers). Answers none with a warning when there isn't
 * exactly one, and refuses without the extension's hook.
 */
internal fun BytecodePatchContext.findChatBackground(): ChatBackgroundSite? {
    val sites = THREAD_BACKGROUND_IDS.flatMap { id ->
        classesLoading(id).flatMap { classDef -> classDef.methods.mapNotNull { site(it, id) } }
    }.distinctBy { "${it.method.definingClass}->${it.method.name}(${it.method.parameterTypes.joinToString("")})" }
    val site = sites.singleOrNull() ?: run {
        patchLog.warning("$PATCH: found ${sites.size} methods painting a chat's background, so chats keep Instagram's black")
        return null
    }
    if (classDefByOrNull(ACCENT_THEME)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == CHAT_BACKGROUND &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $CHAT_BACKGROUND")
    }
    return site
}

/**
 * [method]'s chat background site when it loads [id], then builds a LayerDrawable, and a later static
 * call ending in [HANDS_TO_VIEW] takes that register first with nothing writing it in between.
 */
private fun site(method: Method, id: Long): ChatBackgroundSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val load = code.indexOfFirst { it is NarrowLiteralInstruction && (it.narrowLiteral.toLong() and 0xffffffffL) == id }
    if (load < 0) return null
    val built = (load + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && (code[it] as ReferenceInstruction).reference.toString() == LAYER_DRAWABLE
    } ?: return null
    val layers = (code[built] as OneRegisterInstruction).registerA
    for (at in built + 1 until code.size) {
        val instruction = code[at]
        if (instruction.writes(layers)) return null
        if (instruction.opcode == Opcode.INVOKE_STATIC && instruction is FiveRegisterInstruction &&
            (instruction as ReferenceInstruction).reference.toString().endsWith(HANDS_TO_VIEW) && instruction.registerC == layers
        ) {
            return ChatBackgroundSite(method, at, layers)
        }
    }
    return null
}

/**
 * Hands the layers [findChatBackground] found to AccentTheme.chatBackground right before they're handed
 * to the view, so an opened chat's page black takes the page shade Classic colors or Backgrounds in the
 * accent color give the rest of Instagram (#528). Every path onto that call goes through the hook.
 * Leaves chats as they are when there's no such method.
 */
internal fun BytecodePatchContext.paintChatBackground() {
    val site = findChatBackground() ?: return
    val method = mutableClassDefBy(site.method.definingClass).methods.single {
        it.name == site.method.name && it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString) &&
            it.returnType == site.method.returnType
    }
    method.addInstructionsAtControlFlowLabel(site.call, "invoke-static/range { v${site.layers} .. v${site.layers} }, $CHAT_BACKGROUND")
}

/**
 * The trace key Instagram's view helper names when an opened chat's header row takes the header color of
 * the chat's theme (06Eo.A09 on 456's 386214574 hands it with the header and the color): 0x14d400c4 on
 * every 456 build, and no other method loads it.
 */
internal const val CHAT_HEADER_TRACE = 0x14d400c4L

/** The extension's hook given an opened chat's header once its color is set (#528). */
internal const val CHAT_HEADER = "$ACCENT_THEME->chatHeader(Landroid/view/View;)V"

/** The end of the static call setting a view's background color under a trace key. */
private const val COLORS_VIEW = "(Landroid/view/View;II)V"

/** The method setting an opened chat's header color: the index of the call setting it, and the header's register. */
internal class ChatHeaderSite(val method: Method, val call: Int, val header: Int)

/**
 * The one method loading [CHAT_HEADER_TRACE] whose next static call ending in [COLORS_VIEW] takes it last,
 * with nothing jumping onto the instruction after that call. Answers none with a warning otherwise, and
 * refuses without the extension's hook.
 */
internal fun BytecodePatchContext.findChatHeader(): ChatHeaderSite? {
    val sites = classesLoading(CHAT_HEADER_TRACE).flatMap { classDef -> classDef.methods.mapNotNull(::headerSite) }
        .distinctBy { "${it.method.definingClass}->${it.method.name}(${it.method.parameterTypes.joinToString("")})" }
    val site = sites.singleOrNull() ?: run {
        patchLog.warning("$PATCH: found ${sites.size} methods coloring a chat's header, so chat headers keep Instagram's black")
        return null
    }
    if (classDefByOrNull(ACCENT_THEME)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == CHAT_HEADER &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $CHAT_HEADER")
    }
    return site
}

/** The classes of the methods [findChatHeader] picks among, the one coloring an opened chat's header (`LX/08Cr` on 386314286). */
internal fun BytecodePatchContext.chatHeaderClasses(): List<String> =
    classesLoading(CHAT_HEADER_TRACE).flatMap { classDef -> classDef.methods.mapNotNull(::headerSite) }.map { it.method.definingClass }.distinct()

/** [method]'s chat header site, when it loads [CHAT_HEADER_TRACE] into the last argument of a later [COLORS_VIEW] call. */
private fun headerSite(method: Method): ChatHeaderSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val load = code.indexOfFirst { it is NarrowLiteralInstruction && (it.narrowLiteral.toLong() and 0xffffffffL) == CHAT_HEADER_TRACE }
    if (load < 0) return null
    val key = (code[load] as OneRegisterInstruction).registerA
    val call = (load + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.INVOKE_STATIC && (code[it] as ReferenceInstruction).reference.toString().endsWith(COLORS_VIEW)
    } ?: return null
    val colors = code[call] as FiveRegisterInstruction
    if (colors.registerCount != 3 || colors.registerE != key || (load + 1 until call).any { code[it].writes(key) }) return null
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    if (call + 1 >= code.size || addresses[call + 1] in jumpTargets(method, code, addresses)) return null
    return ChatHeaderSite(method, call, colors.registerC)
}

/**
 * Hands the header [findChatHeader] found to AccentTheme.chatHeader right after Instagram colors it, so an
 * opened chat's black header takes the page shade the strip above it and the page below it take (#528).
 * Leaves the header as it is when there's no such method.
 */
internal fun BytecodePatchContext.paintChatHeader() {
    val site = findChatHeader() ?: return
    val method = mutableClassDefBy(site.method.definingClass).methods.single {
        it.name == site.method.name && it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString) &&
            it.returnType == site.method.returnType
    }
    method.addInstructions(site.call + 1, "invoke-static/range { v${site.header} .. v${site.header} }, $CHAT_HEADER")
}

/**
 * The code addresses [code] of [method] can jump or be thrown to: its gotos' and branches' targets, its
 * switches' through their payloads (offsets from the switch), and its exception handlers.
 */
private fun jumpTargets(method: Method, code: List<Instruction>, addresses: List<Int>): Set<Int> {
    val targets = HashSet<Int>()
    code.forEachIndexed { at, instruction ->
        when (instruction.opcode) {
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                val payload = code.getOrNull(addresses.indexOf(addresses[at] + (instruction as OffsetInstruction).codeOffset)) as? SwitchPayload
                payload?.switchElements?.forEach { targets += addresses[at] + it.offset }
            }
            Opcode.FILL_ARRAY_DATA -> Unit
            else -> if (instruction is OffsetInstruction) targets += addresses[at] + instruction.codeOffset
        }
    }
    method.implementation?.tryBlocks?.forEach { block -> block.exceptionHandlers.forEach { targets += it.handlerCodeAddress } }
    return targets
}

/** Whether this writes [register], or a wide value over it. */
private fun Instruction.writes(register: Int): Boolean {
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return (opcode.setsRegister() && written == register) || (opcode.setsWideRegister() && written + 1 == register)
}
