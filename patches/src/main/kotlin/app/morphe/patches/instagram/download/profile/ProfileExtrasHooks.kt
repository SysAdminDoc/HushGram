/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.profile.friendship.ProfileUser
import app.morphe.patches.instagram.profile.friendship.findProfileUser
import app.morphe.patches.instagram.profile.threads.BAR_GROUP
import app.morphe.patches.instagram.profile.threads.PROFILE_ACTION_BAR
import app.morphe.patches.instagram.profile.threads.USER_DETAIL
import app.morphe.patches.instagram.profile.threads.USER_SESSION
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PROFILE_EXTRAS = "$EXTENSION_PACKAGE/download/ProfileExtras;"
internal const val EXTRAS_BOUND = "$PROFILE_EXTRAS->bound(Landroid/view/View;Landroid/view/View;)V"
internal const val EXTRAS_SCREEN = "$PROFILE_EXTRAS->screen(Landroid/view/View;Ljava/lang/Object;)V"
internal const val EXTRAS_MODEL_USER = "modelUser"

/** The profile screen's view model, which keeps its name in Instagram's builds, and holds the account the screen shows. The extension reads the account off the screen by these two types. */
internal const val USER_DETAIL_MODEL = "Lcom/instagram/profile/fragment/UserDetailViewModel;"

private const val VIEW = "Landroid/view/View;"
private const val LIST = "Ljava/util/List;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * The bar's bind and the row the three dots go in: [method] is the bar's method that fills its two
 * rows, [right] the bar's field holding the row on the right. [caller]s are the places outside the
 * bar that bind it.
 */
internal class ProfileExtrasSite(
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val right: String,
    val ties: List<ProfileExtrasTie>,
    /** Where the screen's view model keeps the account, the same read Show if a profile follows you has. */
    val user: ProfileUser,
)

/**
 * One call of the bar's bind from the profile screen: the call is at [call] of [method] in [owner]
 * and the bar was read off the screen's own field right before it, so right after the call the
 * extension is handed that bar and the screen, which it reads the account off.
 */
internal class ProfileExtrasTie(
    val owner: String,
    val method: String,
    val parameters: List<String>,
    val returnType: String,
    val call: Int,
    val bar: Int,
    val screen: Int,
)

private fun refuse(why: String): Nothing = throw PatchException("$PROFILE_PICTURE_PATCH: the profile extras button: $why")

/**
 * Finds the places the profile extras button needs, before anything changes. In [PROFILE_ACTION_BAR]
 * one method fills the two rows of buttons through a static call taking the activity, a context, the
 * session, two [BAR_GROUP]s, a state, a list and a callback; its fifth argument is the right row, a
 * field of the bar. The bind is called from the profile screen, [USER_DETAIL], which keeps the bar
 * in a field of its own, and each call is found by the bar read off that field right before it. The
 * screen's account is reached through its one [USER_DETAIL_MODEL] field, and the model's account is
 * read the way Show if a profile follows you reads it.
 */
internal fun BytecodePatchContext.findProfileExtras(): ProfileExtrasSite {
    val bar = classDefByOrNull(PROFILE_ACTION_BAR) ?: refuse("this Instagram build has no $PROFILE_ACTION_BAR")
    val binders = bar.methods.filter { method -> method.instructions().any { it.isRowFiller() } }
    val binder = binders.singleOrNull() ?: refuse("expected one method of $PROFILE_ACTION_BAR filling its rows, found ${binders.size}")
    val code = binder.instructions()
    val fills = code.indices.filter { code[it].isRowFiller() }
    val fill = fills.singleOrNull() ?: refuse("expected one call filling the rows in ${binder.name}, found ${fills.size}")
    val rows = code[fill].arguments()
    val left = rowField(code, fill, rows[3], bar.type) ?: refuse("the left row isn't read off a field of the bar")
    val right = rowField(code, fill, rows[4], bar.type) ?: refuse("the right row isn't read off a field of the bar")
    if (left.name == right.name) refuse("the bar's two rows are the same field ${left.name}")
    // The entry hook writes v0 and v1 before the bind's own code runs, so both must be locals.
    val words = binder.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2L else 1L } + 1
    if ((binder.implementation?.registerCount ?: 0) - words < 2) refuse("${binder.name} has fewer than two locals for the hook")
    // The screen's hook goes right after each call, which would split an answer from its move-result.
    if (binder.returnType != "V") refuse("${binder.name} answers ${binder.returnType}, so a hook after its calls would split the answer off")

    val screen = classDefByOrNull(USER_DETAIL) ?: refuse("this Instagram build has no $USER_DETAIL")
    val bars = screen.fields.filter { it.type == PROFILE_ACTION_BAR && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val barField = bars.singleOrNull() ?: refuse("expected one $PROFILE_ACTION_BAR field on the profile screen, found ${bars.size}")
    val models = screen.fields.filter { it.type == USER_DETAIL_MODEL && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val modelField = models.singleOrNull() ?: refuse("expected one $USER_DETAIL_MODEL field on the profile screen, found ${models.size}")
    if (!AccessFlags.PUBLIC.isSet(modelField.accessFlags)) refuse("the profile screen's ${modelField.name} isn't public, so the hook can't read it")
    val user = findProfileUser(::refuse)

    val parameters = binder.parameterTypes.map { it.toString() }
    val ties = classesCalling(bar.type, binder.name, parameters, binder.returnType).filter { it.type != bar.type }.flatMap { owner ->
        owner.methods.flatMap { method ->
            val steps = method.instructions()
            steps.indices.filter { at -> steps[at].isBind(bar.type, binder.name, parameters) }.map { at ->
                tieAt(method, steps, at, barField.name)
            }
        }
    }
    if (ties.isEmpty()) refuse("nothing outside $PROFILE_ACTION_BAR binds it")
    val perMethod = ties.groupBy { Triple(it.owner, it.method, it.parameters) }
    if (perMethod.values.any { it.size > 1 }) refuse("a method binds the bar more than once")

    val extension = classDefByOrNull(PROFILE_EXTRAS) ?: refuse("the extension has no $PROFILE_EXTRAS")
    for (hook in listOf(EXTRAS_BOUND, EXTRAS_SCREEN)) {
        if (extension.methods.none {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }
        ) refuse("the extension has no public static $hook")
    }
    if (extension.methods.none { it.name == EXTRAS_MODEL_USER && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/lang/Object;" }) {
        refuse("the extension has no $EXTRAS_MODEL_USER to fill in")
    }
    return ProfileExtrasSite(binder.name, parameters, binder.returnType, "${bar.type}->${right.name}:$BAR_GROUP", ties, user)
}

private fun tieAt(method: Method, steps: List<Instruction>, call: Int, barField: String): ProfileExtrasTie {
    val where = "${method.definingClass}->${method.name}"
    val bar = steps[call].arguments().firstOrNull() ?: refuse("$where's bind has no bar")
    val read = (call - 1 downTo 0).firstOrNull { steps[it].writes(bar) }
        ?: refuse("$where doesn't read the bar before binding it")
    val field = steps[read].field()
    if (steps[read].opcode != Opcode.IGET_OBJECT || field == null || field.definingClass != USER_DETAIL || field.name != barField) {
        refuse("$where's bar isn't read off the profile screen's $barField right before binding")
    }
    val screen = (steps[read] as TwoRegisterInstruction).registerB
    if ((read + 1 until call).any { steps[it].writes(screen) }) refuse("$where overwrites the profile screen before binding the bar")
    if (bar > 15 || screen > 15) refuse("$where holds the bar or the profile screen past v15")
    if (call + 1 >= steps.size) refuse("$where ends at the bind")
    return ProfileExtrasTie(
        method.definingClass, method.name, method.parameterTypes.map { it.toString() }, method.returnType,
        call, bar, screen,
    )
}

/** The field a row's register was read off, following plain moves back to the read. */
private fun rowField(code: List<Instruction>, before: Int, register: Int, owner: String): FieldReference? {
    var held = register
    for (at in before - 1 downTo 0) {
        val step = code[at]
        if (!step.writes(held)) continue
        return when {
            step.opcode == Opcode.IGET_OBJECT -> step.field()?.takeIf { it.definingClass == owner && it.type == BAR_GROUP }
            step.opcode == Opcode.MOVE_OBJECT || step.opcode == Opcode.MOVE_OBJECT_FROM16 || step.opcode == Opcode.MOVE_OBJECT_16 -> {
                held = (step as TwoRegisterInstruction).registerB
                continue
            }
            else -> null
        }
    }
    return null
}

private fun Instruction.isRowFiller(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val call = call() ?: return false
    val p = call.parameterTypes.map { it.toString() }
    return call.returnType == "V" && p.size == 8 && p[0] == ACTIVITY && p[1] == CONTEXT && p[2] == USER_SESSION &&
        p[3] == BAR_GROUP && p[4] == BAR_GROUP && p[6] == LIST
}

private fun Instruction.isBind(bar: String, name: String, parameters: List<String>): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = call() ?: return false
    return call.definingClass == bar && call.name == name && call.parameterTypes.map { it.toString() } == parameters
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/**
 * Only called once [findProfileExtras] found everything. First thing in the bar's bind the extension
 * is handed the bar and its right row, and right after each call that binds it the profile screen
 * goes to the extension with the bar, which reads the screen's account off it.
 */
internal fun BytecodePatchContext.applyProfileExtras(site: ProfileExtrasSite) {
    val binder = mutableClassDefBy(PROFILE_ACTION_BAR).methods.single {
        it.name == site.name && it.parameterTypes.map { type -> type.toString() } == site.parameters && it.returnType == site.returnType
    }
    // Entry: no local holds anything yet, so v0 and v1 are free. p0 is the bar, past v15, so it's moved down first.
    binder.addInstructions(
        0,
        """
            move-object/from16 v0, p0
            iget-object v1, v0, ${site.right}
            invoke-static { v0, v1 }, $EXTRAS_BOUND
        """,
    )
    for (tie in site.ties.sortedByDescending { it.call }) {
        val method = mutableClassDefBy(tie.owner).methods.single {
            it.name == tie.method && it.parameterTypes.map { type -> type.toString() } == tie.parameters && it.returnType == tie.returnType
        }
        method.addInstructions(tie.call + 1, "invoke-static { v${tie.bar}, v${tie.screen} }, $EXTRAS_SCREEN")
    }
    // Answers an Object, so its ways out may meet at one return.
    mutableClassDefBy(PROFILE_EXTRAS).methods.single { it.name == EXTRAS_MODEL_USER }.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $USER_DETAIL_MODEL
            ${site.user.load("p0")}
            :none
            return-object p0
        """,
    )
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
