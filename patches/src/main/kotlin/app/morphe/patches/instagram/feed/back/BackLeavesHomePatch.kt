/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.back

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Back leaves Home"
internal const val BACK_LEAVES_HOME = "$EXTENSION_PACKAGE/feed/BackLeavesHome;"
internal const val LEAVE = "$BACK_LEAVES_HOME->leave()I"

/**
 * The title Instagram's own tracing gives Home's scroll-to-the-top method (MainFeedFragment is a
 * name Instagram keeps in the string), which is what finds the fragment among 200,000 classes.
 */
internal const val SCROLL_TITLE = "MainFeedFragment.scrollToTopWithReason."

/** The name of the reason Home's Back handler gives that scroll, as the reason enum spells it. */
internal const val BACK_PRESS = "BACK_BUTTON_PRESS"

private const val ENUM = "Ljava/lang/Enum;"

/**
 * Lets Back on Home leave as it is. Instagram's Home answers Back by scrolling the feed to the top
 * and reloading it, and only a second Back leaves. Included in the default selection with its
 * switch off.
 */
@Suppress("unused")
val backLeavesHomePatch = bytecodePatch(
    name = "Back leaves Home",
    description = "Makes Back on Home leave Instagram at once, instead of first scrolling the feed to the top and " +
        "reloading it. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("backLeavesHome")
        backLeavesHome(findBackOnHome())
        enableStatus("backLeavesHome")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the hook goes in Home's Back handler: the handler itself, the index of the call that
 * scrolls to the top, and a free local to hold the hook's answer.
 */
internal class BackOnHomeSite(val handler: Method, val scroll: Int, val free: Int)

/**
 * Finds the one place Home's Back handler scrolls the feed to the top, and checks it before
 * anything changes:
 * - One class loads [SCROLL_TITLE]: Home's feed fragment.
 * - It has one instance `scroll(reason)V` taking a reason enum and loading that title.
 * - It has one `onBackPressed()Z`, which calls that scroll once, right after reading a static
 *   field of the reason enum into the register it hands over, and the enum's field is the one built
 *   as [BACK_PRESS]. Back at the top of the feed gives another reason and another call.
 * - Nothing jumps to the call, so the hook goes in front of it and every path still passes through
 *   it, `this` is intact at the call, and a local up to v15 is free there.
 */
internal fun BytecodePatchContext.findBackOnHome(): BackOnHomeSite {
    val holders = classesLoadingString(SCROLL_TITLE)
    val home = holders.singleOrNull() ?: refuse("expected one class loading \"$SCROLL_TITLE\", found ${holders.size}")

    val scrolls = home.methods.filter { scroll ->
        !AccessFlags.STATIC.isSet(scroll.accessFlags) && scroll.returnType == "V" && scroll.parameterTypes.size == 1 &&
            classDefByOrNull(scroll.parameterTypes.single().toString())?.superclass == ENUM &&
            scroll.instructions().any { it.stringLoaded() == SCROLL_TITLE }
    }
    val scroll = scrolls.singleOrNull()
        ?: refuse("expected one scroll(reason) in ${home.type} that loads \"$SCROLL_TITLE\", found ${scrolls.size}")
    val reasonType = scroll.parameterTypes.single().toString()

    val handlers = home.methods.filter {
        it.name == "onBackPressed" && it.parameterTypes.isEmpty() && it.returnType == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val handler = handlers.singleOrNull() ?: refuse("expected one onBackPressed()Z in ${home.type}, found ${handlers.size}")

    val code = handler.instructions()
    val self = handler.localRegisterCount()
    val calls = code.indices.filter { code[it].methodReference()?.sameMethodAs(scroll) == true }
    val call = calls.singleOrNull()
        ?: refuse("expected ${home.type}->onBackPressed to scroll to the top once, found ${calls.size}")
    val invoke = code[call]
    if (invoke.opcode != Opcode.INVOKE_VIRTUAL && invoke.opcode != Opcode.INVOKE_VIRTUAL_RANGE) {
        refuse("${home.type}->onBackPressed doesn't call the scroll with invoke-virtual")
    }
    val arguments = invoke.argumentRegisters()
    if (arguments.size != 2 || arguments[0] != self) refuse("${home.type}->onBackPressed doesn't scroll on this with one reason")
    val reason = code.getOrNull(call - 1)
    val field = reason?.fieldReference()
    if (reason?.opcode != Opcode.SGET_OBJECT || (reason as OneRegisterInstruction).registerA != arguments[1] ||
        field?.definingClass != reasonType || field.type != reasonType
    ) {
        refuse("${home.type}->onBackPressed doesn't read a $reasonType constant right before scrolling")
    }
    val named = enumConstantName(classDefByOrNull(reasonType) ?: refuse("$reasonType isn't in this build"), field)
    if (named != BACK_PRESS) refuse("Back scrolls with the reason ${named ?: "of no name"}, not $BACK_PRESS")

    val targets = handler.jumpTargets()
    if (call in targets) refuse("something in ${home.type}->onBackPressed jumps to the scroll call")
    handler.requireThisIntact(PATCH, listOf(call))
    val free = handler.freeLocalsAt(PATCH, call, 1).single()
    return BackOnHomeSite(handler, call, free)
}

/**
 * In front of Home's scroll to the top, the hook is asked whether to leave Back alone. On a 1 the
 * handler answers false, "not handled", the answer it already gives at the top of the feed, and
 * the activity does the rest. On a 0 the scroll goes ahead as before.
 */
internal fun BytecodePatchContext.backLeavesHome(site: BackOnHomeSite) {
    val handler = mutableClassDefBy(site.handler.definingClass).methods.single {
        it.name == site.handler.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
    }
    val scroll = handler.getInstruction(site.scroll)
    handler.addInstructionsWithLabels(
        site.scroll,
        """
            invoke-static { }, $LEAVE
            move-result v${site.free}
            if-eqz v${site.free}, :scroll
            const/4 v${site.free}, 0x0
            return v${site.free}
        """,
        ExternalLabel("scroll", scroll),
    )
}

/**
 * The name [field], one of [enum]'s constants, is built with: its static initializer makes each
 * constant as `new-instance`, a call of the constructor with the name's register second, and a store
 * of the new object into the field, and the name is the string loaded into that register. Null when
 * the initializer doesn't build it that way.
 */
internal fun enumConstantName(enum: ClassDef, field: FieldReference): String? {
    val code = enum.methods.singleOrNull { it.name == "<clinit>" }?.instructions() ?: return null
    val stores = code.indices.filter { index ->
        val stored = code[index].fieldReference()
        code[index].opcode == Opcode.SPUT_OBJECT && stored?.definingClass == field.definingClass && stored.name == field.name
    }
    val store = stores.singleOrNull() ?: return null
    val constant = (code[store] as OneRegisterInstruction).registerA
    val construct = (store - 1 downTo 0).firstOrNull { index ->
        val called = code[index].methodReference()
        called?.name == "<init>" && called.definingClass == enum.type && code[index].argumentRegisters().firstOrNull() == constant
    } ?: return null
    if ((construct until store).any { code[it].opcode == Opcode.SPUT_OBJECT }) return null
    val arguments = code[construct].argumentRegisters()
    if (arguments.size < 2) return null
    val name = arguments[1]
    val load = (construct - 1 downTo 0).firstOrNull { index ->
        val instruction = code[index]
        instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == name
    } ?: return null
    return code[load].stringLoaded()
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.sameMethodAs(method: Method): Boolean =
    definingClass == method.definingClass && name == method.name && returnType == method.returnType &&
        parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
