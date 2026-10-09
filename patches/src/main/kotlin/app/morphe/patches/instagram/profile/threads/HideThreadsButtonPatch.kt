/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.threads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Hide the Threads button"

/** A profile's top bar, which keeps its name in Instagram's builds. */
internal const val PROFILE_ACTION_BAR = "Lcom/instagram/profile/actionbar/ProfileActionBar;"

/** The two groups the bar's buttons go in, one each side of the name. */
internal const val BAR_GROUP = "Lcom/instagram/common/ui/base/IgLinearLayout;"

internal const val THREADS_BUTTON = "$EXTENSION_PACKAGE/profile/ThreadsButton;"
internal const val BUTTONS = "$THREADS_BUTTON->buttons(Ljava/util/List;)Ljava/util/List;"
internal const val ICON_STUB = "icon"

private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
private const val ITERATOR = "Ljava/util/List;->iterator()Ljava/util/Iterator;"

/**
 * Takes the Threads button off the top bar of profiles. Included in the default selection with its
 * switch initially off, so leaving the button out remains the user's pick.
 *
 * Only the list the bar builds its buttons from changes. The menu, the bell and the other buttons
 * are built as before, and the Threads profile is still a tap away in the menu.
 */
@Suppress("unused")
val hideThreadsButtonPatch = bytecodePatch(
    name = "Hide the Threads button",
    description = "Takes the Threads button off the top of profiles, yours and other people's. The menu and the " +
        "other buttons stay. Off until you turn it on. Switch: HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("threadsButton")
        hideThreadsButton(findThreadsButton())
        enableStatus("threadsButton")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the hook goes: in the bar's method [name], right at [hook], after the list of buttons is
 * read into [register]. [button] is the type the bar's builder takes each item as, and [icon] its
 * int field holding the icon's resource id.
 */
internal class ThreadsButtonSite(
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val hook: Int,
    val register: Int,
    val button: String,
    val icon: String,
)

/**
 * Finds the list the bar builds its buttons from, failing before anything changes when any of it
 * isn't there exactly once, since that's an update this patch hasn't seen. In [PROFILE_ACTION_BAR],
 * the one static call taking a list and the bar's two [BAR_GROUP]s builds the buttons. Its list is
 * read once, from a getter with no arguments. Between that read and the call, branches are fine as
 * long as nothing jumps in from outside and nothing writes over the list's register, so every build
 * gets the list the hook answered. The builder goes through
 * the list it's handed once, casting each item to the button type, which is public with one int
 * field: the icon. Other lists it walks don't count.
 */
internal fun BytecodePatchContext.findThreadsButton(): ThreadsButtonSite {
    val bar = classDefByOrNull(PROFILE_ACTION_BAR) ?: refuse("this Instagram build has no $PROFILE_ACTION_BAR")
    val calls = bar.methods.flatMap { method ->
        val code = method.code()
        code.indices.filter { code[it].buildsBar() }.map { method to it }
    }
    val (binder, building) = calls.singleOrNull()
        ?: refuse("expected one call building the buttons in $PROFILE_ACTION_BAR, found ${calls.size}")
    val where = "${bar.type}->${binder.name}"
    val code = binder.code()
    val builder = code[building].call()!!
    val takes = builder.parameters()
    // A long or a double takes two registers, so the list's register is counted past them.
    val register = code[building].arguments()[takes.take(takes.indexOf(LIST)).sumOf { if (it == "J" || it == "D") 2 else 1 }]

    val reads = code.indices.filter { at ->
        at > 0 && code[at].opcode == Opcode.MOVE_RESULT_OBJECT && (code[at] as OneRegisterInstruction).registerA == register &&
            code[at - 1].call()?.let { it.parameterTypes.isEmpty() && it.returnType == LIST } == true
    }
    val read = reads.singleOrNull() ?: refuse("expected one list of buttons read into v$register in $where, found ${reads.size}")
    if (read > building) refuse("$where reads its list of buttons after building them")
    val flow = try {
        ControlFlow.of(binder)
    } catch (failure: IllegalArgumentException) {
        refuse("$where can't be followed: ${failure.message}")
    }
    val from = List(code.size) { mutableListOf<Int>() }
    for (at in code.indices) (flow.normal[at] + flow.exceptional[at]).forEach { from[it] += at }
    if ((read + 1..building).any { at -> from[at].any { it !in read until building } }) {
        refuse("something jumps in between $where's list of buttons and the call building them")
    }
    if ((read + 1 until building).any { code[it].writes(register) }) {
        refuse("$where writes over v$register between its list of buttons and the call building them")
    }

    val built = classDefByOrNull(builder.definingClass) ?: refuse("the buttons' builder ${builder.definingClass} isn't in the app")
    val build = built.methods.singleOrNull { it.name == builder.name && it.parameters() == takes && it.returnType == builder.returnType }
        ?: refuse("${builder.definingClass} has no ${builder.name}")
    val steps = build.code()
    // Only the walk of the list it was handed: 450's builder also walks a list of Booleans later on.
    val width = { type: String -> if (type == "J" || type == "D") 2 else 1 }
    val registers = build.implementation?.registerCount ?: refuse("${builder.definingClass}->${builder.name} has no code")
    val handed = registers - takes.sumOf(width) + takes.take(takes.indexOf(LIST)).sumOf(width)
    val walks = steps.indices.filter { at ->
        steps[at].call()?.toString() == ITERATOR && steps[at].arguments() == listOf(handed) &&
            steps.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }.map { (steps[it + 1] as OneRegisterInstruction).registerA }.toSet()
    val casts = steps.indices.filter { at ->
        steps[at].call()?.toString() == NEXT && steps[at].arguments().singleOrNull() in walks &&
            steps.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            steps.getOrNull(at + 2)?.opcode == Opcode.CHECK_CAST &&
            (steps[at + 2] as OneRegisterInstruction).registerA == (steps[at + 1] as OneRegisterInstruction).registerA
    }
    val cast = casts.singleOrNull()
        ?: refuse("expected ${builder.definingClass}->${builder.name} to go through its buttons once, found ${casts.size}")
    val button = (steps[cast + 2].reference() as TypeReference).type
    val buttonClass = classDefByOrNull(button) ?: refuse("the button type $button isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(buttonClass.accessFlags)) refuse("$button isn't public, so the extension can't read its icon")
    val icons = buttonClass.fields.filter { it.type == "I" && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val icon = icons.singleOrNull() ?: refuse("expected one int field on $button, its icon, found ${icons.size}")
    if (!AccessFlags.PUBLIC.isSet(icon.accessFlags)) refuse("$button's icon ${icon.name} isn't public, so the extension can't read it")

    val extension = classDefByOrNull(THREADS_BUTTON) ?: refuse("the extension has no $THREADS_BUTTON")
    if (extension.methods.none { it.signature() == BUTTONS && it.publicStatic() }) refuse("the extension has no public static $BUTTONS")
    if (extension.methods.none { it.isIconStub() }) refuse("$THREADS_BUTTON has no public static I $ICON_STUB($OBJECT)")
    return ThreadsButtonSite(binder.name, binder.parameters(), binder.returnType, read + 1, register, button, "$button->${icon.name}:I")
}

/**
 * Writes the stub that reads a button's icon, then hands the list of buttons to [BUTTONS] right
 * after it's read, keeping the answer in the same register. Only called once [findThreadsButton]
 * found everything.
 */
internal fun BytecodePatchContext.hideThreadsButton(site: ThreadsButtonSite) {
    replace(mutableClassDefBy(THREADS_BUTTON).methods.single { it.isIconStub() }, 1, """
        check-cast p0, ${site.button}
        iget p0, p0, ${site.icon}
        return p0
    """)
    val binder = mutableClassDefBy(PROFILE_ACTION_BAR).methods.single {
        it.name == site.name && it.parameters() == site.parameters && it.returnType == site.returnType
    }
    binder.addInstructions(
        site.hook,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $BUTTONS
            move-result-object v${site.register}
        """,
    )
}

private fun BytecodePatchContext.replace(method: MutableMethod, registers: Int, body: String) {
    val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(registers, emptyList(), null, null)).toMutable().apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }
    val owner = mutableClassDefBy(method.definingClass)
    owner.methods.remove(method)
    owner.methods.add(replacement)
}

/** Whether this is a static call taking one list and two of the bar's groups: the call building its buttons. */
private fun Instruction.buildsBar(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val takes = call()?.parameters() ?: return false
    return takes.count { it == LIST } == 1 && takes.count { it == BAR_GROUP } == 2
}

/** Whether this writes [register], or a wide pair covering it. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun Method.isIconStub() = name == ICON_STUB && parameters() == listOf(OBJECT) && returnType == "I" && publicStatic()

private fun Method.publicStatic() = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)

private fun Method.signature() = "$definingClass->$name(${parameters().joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

private fun Instruction.call() = reference() as? MethodReference

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }

/** The registers an invoke hands over, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
