/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.swipecreate

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Stop swipe to create"
internal const val SWIPE_TO_CREATE = "$EXTENSION_PACKAGE/feed/SwipeToCreate;"
internal const val HOLD = "$SWIPE_TO_CREATE->hold(FFLjava/lang/String;)I"
internal const val ENABLED = "$SWIPE_TO_CREATE->enabled()I"

/**
 * The view that slides Home aside for the camera, and the description of each move it makes.
 * Instagram keeps both names, and the names of the container's methods below.
 */
internal const val SWIPE_CONTAINER = "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"
internal const val POSITION_CONFIG = "Lcom/instagram/ui/swipenavigation/container/PositionConfig;"

/** Every move goes through this one before the panels slide, a drag's steps and its end included. */
internal const val SET_POSITION = "setInternalPosition"

/** Where the panels are now, between the camera on one side and the panel on the other. */
internal const val CLAMPED_POSITION = "getClampedPosition"

/** The container's drag handler, Android's name for a scroll gesture's step. */
internal const val ON_SCROLL = "onScroll"

/**
 * The reason a finger gives for each move: a drag's steps, and the move that settles the panels
 * when it lets go, a fling included. Taps and links never give it.
 */
internal const val DRAG = "swipe"

private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val STRING = "Ljava/lang/String;"

/**
 * Stops a sideways swipe on Home from opening the camera. Included in the default selection with
 * its switch initially off, so turning the swipe off is the user's pick. The + button and every
 * other way into the camera still open it.
 */
@Suppress("unused")
val stopSwipeToCreatePatch = bytecodePatch(
    name = "Stop swipe to create",
    description = "Keeps a sideways swipe on Home from opening the camera. The + button and every other way into the " +
        "camera still work. Off until you turn it on. Switch: HushGram settings > Feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("swipeToCreate")
        stopSwipeToCreate(findSwipeToCreate())
        enableStatus("swipeToCreate")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the hook goes in [SET_POSITION]: in front of the read of the move's animate flag, with the
 * registers holding the clamped target and the flag, a free local, `this` and the config, and the
 * field and method the hook reads.
 */
internal class SwipeToCreateSite(
    val at: Int,
    val target: Int,
    val flag: Int,
    val current: Int,
    val self: Int,
    val config: Int,
    val reasonField: String,
    val clamped: String,
)

/**
 * Finds the one place, failing before anything changes when anything about it isn't there exactly
 * once, since that's an update this patch hasn't seen:
 * - [SWIPE_CONTAINER] has one [SET_POSITION] taking a [POSITION_CONFIG], and one private
 *   [CLAMPED_POSITION] answering a float.
 * - Its [ON_SCROLL] loads [DRAG] once and hands it to the one [POSITION_CONFIG] it makes, and that
 *   constructor stores the parameter in one String field: the move's reason.
 * - [SET_POSITION] reads the target's float from its config, clamps it with one of the container's
 *   own float methods, and reads the animate flag right after, once. Nothing jumps past the start of
 *   that run or to the instruction after it, `this` and the config are still in their registers at
 *   the flag's read, the target's register fits an invoke, and a local up to v15 is free there and
 *   after it. The flag's own register is borrowed for the reason: both ways out of the hook write
 *   it before anything reads it.
 */
internal fun BytecodePatchContext.findSwipeToCreate(): SwipeToCreateSite {
    val container = classDefByOrNull(SWIPE_CONTAINER) ?: refuse("$SWIPE_CONTAINER isn't in this build")
    val config = classDefByOrNull(POSITION_CONFIG) ?: refuse("$POSITION_CONFIG isn't in this build")

    val setters = container.methods.filter {
        it.name == SET_POSITION && it.parameterTypes.map(CharSequence::toString) == listOf(POSITION_CONFIG) && it.returnType == "V"
    }
    val setter = setters.singleOrNull()
        ?: refuse("expected one $SET_POSITION($POSITION_CONFIG)V in $SWIPE_CONTAINER, found ${setters.size}")
    if (AccessFlags.STATIC.isSet(setter.accessFlags)) refuse("$SWIPE_CONTAINER->$SET_POSITION is static")
    val clampers = container.methods.filter { it.name == CLAMPED_POSITION && it.parameterTypes.isEmpty() && it.returnType == "F" }
    val clamped = clampers.singleOrNull()
        ?: refuse("expected one $CLAMPED_POSITION()F in $SWIPE_CONTAINER, found ${clampers.size}")
    if (!AccessFlags.PRIVATE.isSet(clamped.accessFlags) || AccessFlags.STATIC.isSet(clamped.accessFlags)) {
        refuse("$SWIPE_CONTAINER->$CLAMPED_POSITION isn't a private instance method")
    }

    val reasonField = reasonField(container, config)

    val code = setter.instructions()
    val where = "$SWIPE_CONTAINER->$SET_POSITION"
    val self = setter.localRegisterCount()
    val configRegister = setter.parameterRegisterNumber(0)
    val runs = code.indices.filter { at ->
        val target = code[at]
        val clamp = code.getOrNull(at + 1)
        val result = code.getOrNull(at + 2)
        val flag = code.getOrNull(at + 3)
        val targetField = target.fieldReference()
        val clampCall = clamp?.methodReference()
        val flagField = flag?.fieldReference()
        target.opcode == Opcode.IGET && targetField?.definingClass == POSITION_CONFIG && targetField.type == "F" &&
            (target as TwoRegisterInstruction).registerB == configRegister &&
            clamp?.opcode == Opcode.INVOKE_DIRECT && clampCall?.definingClass == SWIPE_CONTAINER &&
            clampCall.parameterTypes.map(CharSequence::toString) == listOf("F") && clampCall.returnType == "F" &&
            clamp.argumentRegisters() == listOf(self, target.registerA) &&
            result?.opcode == Opcode.MOVE_RESULT &&
            flag?.opcode == Opcode.IGET_BOOLEAN && flagField?.definingClass == POSITION_CONFIG &&
            (flag as TwoRegisterInstruction).registerB == configRegister
    }
    val run = runs.singleOrNull()
        ?: refuse("expected $where to read and clamp its target and then read its animate flag once, found ${runs.size}")
    val at = run + 3
    if (at + 1 !in code.indices) refuse("$where ends at its animate flag")
    val targets = setter.jumpTargets()
    if ((run + 1..at + 1).any { it in targets }) refuse("something in $where jumps into the read of its target and animate flag")
    val target = (code[run + 2] as OneRegisterInstruction).registerA
    val flag = (code[at] as OneRegisterInstruction).registerA
    if (target > 15) refuse("$where keeps its target in v$target, past what the hook's invoke can name")
    if (flag == target || flag == self || flag == configRegister) {
        refuse("$where reads its animate flag into v$flag, which the hook needs for something else")
    }
    setter.requireThisIntact(PATCH, listOf(at))
    setter.requireParameterIntact(PATCH, 0, listOf(at))
    // Free in front of the flag's read and after it, where the hook's skip lands; the flag and the
    // target are live there, so neither is picked.
    val current = setter.freeLocalsAt(PATCH, at, 1, targets = listOf(at + 1)).single()

    return SwipeToCreateSite(at, target, flag, current, self, configRegister, reasonField, "$SWIPE_CONTAINER->$CLAMPED_POSITION()F")
}

/**
 * The String field of [config] a drag's reason goes in: [ON_SCROLL] loads [DRAG] once and hands it
 * to the one constructor call it makes, and the constructor stores that parameter in one field.
 */
private fun reasonField(container: ClassDef, config: ClassDef): String {
    val scrolls = container.methods.filter {
        it.name == ON_SCROLL && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT, MOTION_EVENT, "F", "F")
    }
    val scroll = scrolls.singleOrNull() ?: refuse("expected one $ON_SCROLL in $SWIPE_CONTAINER, found ${scrolls.size}")
    val code = scroll.instructions()
    val where = "$SWIPE_CONTAINER->$ON_SCROLL"
    // Positions, not instruction objects: a build read from an APK hands out a fresh object for an
    // instruction on each read, so looking one up again by itself finds nothing.
    val loads = code.indices.filter { code[it].stringLoaded() == DRAG }
    val loadAt = loads.singleOrNull() ?: refuse("expected $where to load \"$DRAG\" once, found ${loads.size}")
    val register = (code[loadAt] as OneRegisterInstruction).registerA
    val makes = code.indices.filter { index ->
        val called = code[index].methodReference()
        called?.definingClass == POSITION_CONFIG && called.name == "<init>"
    }
    val makeAt = makes.singleOrNull() ?: refuse("expected $where to make one $POSITION_CONFIG, found ${makes.size}")
    val make = code[makeAt]
    val made = make.methodReference()!!
    // The string has to reach the call as it was loaded: straight down, with nothing writing its
    // register or jumping in on the way.
    val jumps = scroll.jumpTargets()
    val kept = loadAt < makeAt && (loadAt + 1..makeAt).none { it in jumps } &&
        (loadAt + 1 until makeAt).none { code[it].writes(register) }
    // The first argument is the new config itself.
    val passed = if (kept) make.argumentRegisters().drop(1) else emptyList()
    val parameters = made.parameterTypes.map(CharSequence::toString)
    val slots = mutableListOf<Int>()
    parameters.forEachIndexed { index, type -> slots += index; if (type == "J" || type == "D") slots += -1 }
    val parameter = passed.indices.filter { passed[it] == register }.map { slots.getOrElse(it) { -1 } }.singleOrNull { it >= 0 }
        ?: refuse("$where doesn't hand \"$DRAG\" to the $POSITION_CONFIG it makes")
    if (parameters[parameter] != STRING) refuse("$where hands \"$DRAG\" to a ${parameters[parameter]} parameter")

    val constructor = config.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == parameters }
        ?: refuse("$POSITION_CONFIG has no constructor taking what $where hands it")
    val held = constructor.parameterRegisterNumber(parameter)
    val body = constructor.instructions()
    val stores = body.indices.filter { index ->
        val instruction = body[index]
        val field = instruction.fieldReference()
        instruction.opcode == Opcode.IPUT_OBJECT && (instruction as TwoRegisterInstruction).registerA == held &&
            field?.definingClass == POSITION_CONFIG && field.type == STRING
    }
    val storeAt = stores.singleOrNull()
        ?: refuse("expected $POSITION_CONFIG's constructor to store the reason in one field, found ${stores.size}")
    constructor.requireParameterIntact(PATCH, parameter, listOf(storeAt))
    return body[storeAt].fieldReference().toString()
}

/**
 * In front of the animate flag's read, the hook gets the clamped target, where the panels are now
 * and the move's reason, read into the flag's register. Disabled, paused or unready skips those
 * added native reads entirely. On a 0 the flag is read as before. On a 1
 * the target becomes 0, Home, and the flag false in place of its read, so the move lands at rest
 * without the spring carrying it toward the camera.
 */
internal fun BytecodePatchContext.stopSwipeToCreate(site: SwipeToCreateSite) {
    val setter = mutableClassDefBy(SWIPE_CONTAINER).methods.single {
        it.name == SET_POSITION && it.parameterTypes.map(CharSequence::toString) == listOf(POSITION_CONFIG)
    }
    val read = setter.getInstruction(site.at)
    val past = setter.getInstruction(site.at + 1)
    val clamp = if (site.self > 15) "invoke-direct/range { v${site.self} .. v${site.self} }" else "invoke-direct { v${site.self} }"
    setter.addInstructionsWithLabels(
        site.at,
        """
            invoke-static { }, $ENABLED
            move-result v${site.current}
            if-eqz v${site.current}, :read
            $clamp, ${site.clamped}
            move-result v${site.current}
            move-object/from16 v${site.flag}, v${site.config}
            iget-object v${site.flag}, v${site.flag}, ${site.reasonField}
            invoke-static { v${site.target}, v${site.current}, v${site.flag} }, $HOLD
            move-result v${site.current}
            if-eqz v${site.current}, :read
            const/16 v${site.target}, 0x0
            const/16 v${site.flag}, 0x0
            goto :past
        """,
        ExternalLabel("read", read),
        ExternalLabel("past", past),
    )
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/** Whether this writes [register], or a wide pair that covers it. */
private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (destination == register || (opcode.setsWideRegister() && destination + 1 == register))
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
