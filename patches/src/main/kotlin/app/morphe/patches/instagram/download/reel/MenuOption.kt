/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

/**
 * Proves a new option of Instagram's post and reel menus can be made the way Download is: the
 * option is an enum whose public (String, int, int) constructor hands the name and the ordinal to
 * Enum's and keeps the icon, and whose public icon getter reads it back, so an option made with
 * Download's ordinal and icon is drawn and handled like Download. Answers the getter's name, for
 * [newOption]. A build where either differs stops the patch [patch] before anything changes.
 */
internal fun BytecodePatchContext.optionIcon(patch: String): String {
    val option = classDefBy(OPTION)
    val constructor = option.methods.singleOrNull {
        it.name == "<init>" && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "I", "I")
    } ?: throw PatchException("$patch: $OPTION has no public (String, int, int) constructor")
    val icon = option.methods.singleOrNull {
        it.name == "getIconDrawable" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "I" && it.parameterTypes.isEmpty()
    } ?: throw PatchException("$patch: $OPTION has no public icon getter")
    val initialization = constructor.code()
    val readIcon = icon.code()
    val self = (constructor.implementation?.registerCount ?: 0) - 4
    val getterSelf = (icon.implementation?.registerCount ?: 0) - 1
    if (!AccessFlags.ENUM.isSet(option.accessFlags) || option.superclass != "Ljava/lang/Enum;" ||
        AccessFlags.STATIC.isSet(constructor.accessFlags) || self < 0 || getterSelf < 0 ||
        initialization.map { it.opcode } != listOf(Opcode.INVOKE_DIRECT, Opcode.IPUT, Opcode.RETURN_VOID) ||
        initialization.first().referenceText() != "Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V" ||
        initialization.first().argumentRegisters() != listOf(self, self + 1, self + 2) ||
        readIcon.map { it.opcode } != listOf(Opcode.IGET, Opcode.RETURN) ||
        initialization[1].referenceText() != readIcon[0].referenceText() ||
        (initialization[1] as TwoRegisterInstruction).let { it.registerA != self + 3 || it.registerB != self } ||
        (readIcon[0] as TwoRegisterInstruction).let {
            it.registerB != getterSelf || it.registerA != (readIcon[1] as OneRegisterInstruction).registerA
        }
    ) throw PatchException("$patch: $OPTION constructor and icon getter differ from the native menu option")
    return icon.name
}

/**
 * The body of a method answering a new option with Download's ordinal and icon, made by the
 * option's own constructor, named by what [loadName] puts in v1. [icon] is the getter
 * [optionIcon] proved. It uses v0 to v3.
 */
internal fun newOption(icon: String, loadName: String): String = listOf(
    "sget-object v0, $DOWNLOAD",
    "invoke-virtual { v0 }, Ljava/lang/Enum;->ordinal()I",
    "move-result v2",
    "invoke-virtual { v0 }, $OPTION->$icon()I",
    "move-result v3",
    loadName,
    "new-instance v0, $OPTION",
    "invoke-direct { v0, v1, v2, v3 }, $OPTION-><init>(Ljava/lang/String;II)V",
    "return-object v0",
).joinToString("\n")

private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is Instruction35c -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
