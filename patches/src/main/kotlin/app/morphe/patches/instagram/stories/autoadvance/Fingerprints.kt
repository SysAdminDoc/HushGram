/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.autoadvance

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The story viewer, which Redex leaves under its own name. */
internal const val STORY_VIEWER = "Linstagram/features/stories/fragment/ReelViewerFragment;"

private const val OBJECT = "Ljava/lang/Object;"
private const val STORY_ITEM = "Lcom/instagram/model/reels/ReelItem;"

/**
 * The story viewer's handler for a story item that's done, the one that moves on to the next. It
 * implements a listener method taking the item as an Object, so the compiler made it a bridge.
 * The viewer has two such bridges taking one Object on 449, and both cast it to a ReelItem first;
 * this one holds "userSession", the other doesn't. On 452 each bridge only casts the item and
 * hands it to a viewer method taking the ReelItem, and that method holds the body and the
 * "userSession", so the handler is the one the bridge forwards to. The viewer's progress callback
 * calls it too there when a story reaches its end.
 */
internal object StoryItemDoneFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("userSession"),
    custom = { method, classDef ->
        classDef.type == STORY_VIEWER && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            when (method.parameterTypes.map(CharSequence::toString)) {
                listOf(OBJECT) -> AccessFlags.BRIDGE.isSet(method.accessFlags)
                listOf(STORY_ITEM) -> !AccessFlags.BRIDGE.isSet(method.accessFlags) && classDef.forwardsToFromABridge(method)
                else -> false
            }
    },
)

/**
 * Whether one of the class's bridges taking one Object is nothing but a cast of it to a ReelItem
 * and a call handing this and the item to [target], the shape 452's listener bridges have.
 */
private fun ClassDef.forwardsToFromABridge(target: Method): Boolean = methods.any { bridge ->
    if (!AccessFlags.BRIDGE.isSet(bridge.accessFlags) || AccessFlags.STATIC.isSet(bridge.accessFlags) ||
        bridge.returnType != "V" || bridge.parameterTypes.map(CharSequence::toString) != listOf(OBJECT)
    ) return@any false
    val implementation = bridge.implementation ?: return@any false
    val code = implementation.instructions.toList()
    if (code.size != 3) return@any false
    val self = implementation.registerCount - 2
    val item = implementation.registerCount - 1
    val cast = code[0]
    val call = code[1]
    val called = (call as? ReferenceInstruction)?.reference as? MethodReference
    cast.opcode == Opcode.CHECK_CAST && ((cast as ReferenceInstruction).reference as? TypeReference)?.type == STORY_ITEM &&
        (cast as OneRegisterInstruction).registerA == item &&
        (call.opcode == Opcode.INVOKE_VIRTUAL || call.opcode == Opcode.INVOKE_DIRECT) && called != null &&
        called.definingClass == type && called.name == target.name && called.returnType == "V" &&
        called.parameterTypes.map(CharSequence::toString) == listOf(STORY_ITEM) &&
        (call as FiveRegisterInstruction).registerCount == 2 && call.registerC == self && call.registerD == item &&
        code[2].opcode == Opcode.RETURN_VOID
}
