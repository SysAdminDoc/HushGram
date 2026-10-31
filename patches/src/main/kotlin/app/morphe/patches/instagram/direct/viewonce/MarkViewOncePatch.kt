/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.viewonce

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val VIEW_ONCE_MARK_PATCH = "Mark one-time photos and videos"
internal const val VIEW_ONCE_MARK = "$EXTENSION_PACKAGE/direct/ViewOnceMark;"
internal const val VIEW_ONCE_MARK_HOOK = "$VIEW_ONCE_MARK->mark(Landroid/view/View;I)V"

/** The view once flag, and the keys the photo and video bubble's bind loads: it is the one method loading both. */
internal const val VIEW_ONCE_MODE = "once"
internal val VIEW_ONCE_BIND_KEYS = listOf("story_camera_reply", "story_remix_reply")
private const val FRAME_LAYOUT = "Landroid/widget/FrameLayout;"

@Suppress("unused")
val markViewOncePatch = bytecodePatch(
    name = "Mark one-time photos and videos",
    description = "Puts a border and Instagram's own view once icon on a one-time photo or video in a chat, so " +
        "you can tell it before you open it. Only how the chat looks changes. Nothing is saved, replayed, " +
        "marked seen or sent. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("viewOnceMark")
        findViewOnceBind().markBubble()
        enableStatus("viewOnceMark")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$VIEW_ONCE_MARK_PATCH: $why")

/**
 * Where a photo or video bubble is bound, and what the hook reads there: the bind [method], the
 * instruction after its two casts of the holder and the message [at], the registers they sit in,
 * the holder's bubble frame field and the message's view once flag.
 */
internal class ViewOnceBind(
    val method: MutableMethod,
    val at: Int,
    val holder: Int,
    val message: Int,
    val frame: FieldReference,
    val flag: FieldReference,
)

private fun Method.isBindShape() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
    parameterTypes.size == 2 && parameterTypes.all { it.startsWith("L") }

/**
 * The bind of the old photo and video bubble: the one instance (holder, message) method returning
 * nothing that loads both [VIEW_ONCE_BIND_KEYS]. Its first two casts turn its two parameters into
 * the holder class and the message class, in place. The holder's one FrameLayout field is the
 * bubble. The message class's constructor takes the view once flag as its first boolean and stores
 * it in a field the bind reads, and a place that makes the message loads "once". Instagram's Compose
 * bubble already draws its own icon, so only this one is marked.
 */
internal fun BytecodePatchContext.findViewOnceBind(): ViewOnceBind {
    val binds = classesLoadingString(VIEW_ONCE_BIND_KEYS[0]).flatMap { classDef ->
        classDef.methods.filter { method -> method.isBindShape() && VIEW_ONCE_BIND_KEYS.all { loadsString(method, it) } }
    }
    val found = binds.singleOrNull() ?: refuse(
        "expected exactly one photo and video bubble bind in this Instagram build, found " +
            if (binds.isEmpty()) "none" else binds.joinToString { "${it.definingClass}->${it.name}" },
    )
    val method = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
    }
    val code = method.implementation!!.instructions.toList()
    val origin = hashMapOf(method.parameterRegisterNumber(0) to 0, method.parameterRegisterNumber(1) to 1)
    val casts = mutableListOf<Triple<Int, Int, String>>()
    for ((index, instruction) in code.withIndex()) {
        if (instruction is OffsetInstruction || instruction.opcode == Opcode.PACKED_SWITCH ||
            instruction.opcode == Opcode.SPARSE_SWITCH) {
            refuse("the bind branches before it casts its holder and message")
        }
        if (instruction.opcode == Opcode.CHECK_CAST) {
            val register = (instruction as OneRegisterInstruction).registerA
            val from = origin[register] ?: continue
            val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
            casts += Triple(index, from, type)
            if (casts.size == 2) break
            continue
        }
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        if (destination != null && instruction.opcode.setsRegister()) {
            val moved = when (instruction.opcode) {
                Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                    origin[(instruction as TwoRegisterInstruction).registerB]
                else -> null
            }
            if (moved != null) origin[destination] = moved else origin.remove(destination)
            if (instruction.opcode.setsWideRegister()) origin.remove(destination + 1)
        }
    }
    if (casts.size != 2 || casts[0].second != 0 || casts[1].second != 1) {
        refuse("the bind doesn't cast its holder and then its message first")
    }
    val (holderCast, _, holderType) = casts[0]
    val (messageCast, _, messageType) = casts[1]
    val holderRegister = (code[holderCast] as OneRegisterInstruction).registerA
    val messageRegister = (code[messageCast] as OneRegisterInstruction).registerA
    if (holderRegister > 15) refuse("the holder is past v15")
    if (messageRegister > 15) refuse("the message is past v15")
    if (holderRegister == messageRegister) refuse("the holder and the message share a register")
    val at = messageCast + 1
    if (at >= code.size) refuse("the message cast ends the bind")
    if (at in method.jumpTargets()) refuse("something jumps straight past the bind's casts")

    val holderClass = classDefByOrNull(holderType) ?: refuse("can't read the holder $holderType")
    val frames = holderClass.fields.filter { it.type == FRAME_LAYOUT && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val frame = frames.singleOrNull() ?: refuse("the holder $holderType has ${frames.size} FrameLayout fields, not one")

    val messageClass = classDefByOrNull(messageType) ?: refuse("can't read the message $messageType")
    val withFlag = messageClass.methods.filter { it.name == "<init>" && it.parameterTypes.any { type -> type.toString() == "Z" } }
    val constructor = withFlag.singleOrNull() ?: refuse("the message $messageType has ${withFlag.size} constructors taking a flag, not one")
    val flagParameter = constructor.parameterTypes.indexOfFirst { it.toString() == "Z" }
    val flagRegister = constructor.parameterRegisterNumber(flagParameter)
    val constructorCode = constructor.implementation!!.instructions.toList()
    val stores = constructorCode.indices.filter { index ->
        val instruction = constructorCode[index]
        instruction.opcode == Opcode.IPUT_BOOLEAN && (instruction as TwoRegisterInstruction).registerA == flagRegister &&
            ((instruction as ReferenceInstruction).reference as FieldReference).definingClass == messageType
    }
    val store = stores.singleOrNull() ?: refuse("the message's first flag is stored ${stores.size} times, not once")
    constructor.requireParameterIntact(VIEW_ONCE_MARK_PATCH, flagParameter, listOf(store))
    val flag = (constructorCode[store] as ReferenceInstruction).reference as FieldReference
    val reads = code.count { it.opcode == Opcode.IGET_BOOLEAN && (it as ReferenceInstruction).reference == flag }
    if (reads == 0) refuse("the bind never reads the message's first flag")
    val makers = classesCreating(messageType).filter { maker ->
        maker.methods.any { loadsString(it, VIEW_ONCE_MODE) }
    }
    if (makers.isEmpty()) refuse("nothing that makes a $messageType loads \"$VIEW_ONCE_MODE\"")

    return ViewOnceBind(method, at, holderRegister, messageRegister, frame, flag)
}

/** Hands the bubble's frame and the message's view once flag to the extension, once the bind has both cast. */
internal fun ViewOnceBind.markBubble() {
    val free = method.freeLocalsAt(VIEW_ONCE_MARK_PATCH, at, 2, except = listOf(holder, message))
    val (flagRegister, frameRegister) = free
    method.addInstructions(
        at,
        """
            iget-boolean v$flagRegister, v$message, ${flag.definingClass}->${flag.name}:Z
            iget-object v$frameRegister, v$holder, ${frame.definingClass}->${frame.name}:$FRAME_LAYOUT
            invoke-static { v$frameRegister, v$flagRegister }, $VIEW_ONCE_MARK_HOOK
        """,
    )
}
