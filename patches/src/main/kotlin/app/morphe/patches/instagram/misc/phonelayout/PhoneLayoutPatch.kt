/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.phonelayout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Phone layout on big screens"
internal const val PHONE_WIDTH = "$EXTENSION_PACKAGE/misc/PhoneLayout;->width(I)I"
internal const val NOT_FOLDABLE = "$EXTENSION_PACKAGE/misc/PhoneLayout;->foldable(I)Z"

/** The names of Instagram's window width classes, the enum its big screen layout is decided by. */
internal const val COMPACT = "COMPACT"
internal const val MEDIUM = "MEDIUM"
internal const val EXPANDED = "EXPANDED"

/** The widths in dp where a window stops being compact and medium, as Android's size classes have them. */
internal const val MEDIUM_WIDTH = 600
internal const val EXPANDED_WIDTH = 840

/** The system feature Instagram checks to decide the phone is a foldable. */
internal const val HINGE_FEATURE = "android.hardware.sensor.hinge_angle"

private const val ENUM = "Ljava/lang/Enum;"
private const val CONTEXT = "Landroid/content/Context;"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"

/**
 * Keeps Instagram's phone layout on a foldable or tablet. Instagram sorts each window by width into
 * compact, medium and expanded, and anything but compact gets the big screen layout, with the tab
 * bar on a rail down the side. A foldable's hinge sensor marks the phone as a foldable on top of
 * that. With the switch on, every window sorts as compact and the hinge sensor is left out. Off to
 * start, so a tablet keeps Instagram's own layout until its owner picks the phone one.
 */
@Suppress("unused")
val phoneLayoutPatch = bytecodePatch(
    name = "Phone layout on big screens",
    description = "On a foldable or tablet, Instagram keeps its phone layout across the whole screen, with the tab bar " +
        "at the bottom instead of down the side. Restart Instagram to see the change. Starts off. Turn it on in " +
        "HushGram settings > Layout.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("phoneLayout")
        val width = findWidthClass()
        val hinge = findHingeCheck(width)
        keepPhoneLayout(width, hinge)
        enableStatus("phoneLayout")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The width class enum's factory: the static `(I)` method answering the enum for a width in dp. */
internal class WidthClass(val type: String, val factory: String)

/** The hinge check, and where the answer of its hasSystemFeature call is moved out and to which register. */
internal class HingeCheck(val type: String, val name: String, val at: Int, val register: Int)

/**
 * Finds the one enum outside the extension whose static initializer names [COMPACT], [MEDIUM] and
 * [EXPANDED] and that has one static method taking an int and answering the enum, holding both
 * [MEDIUM_WIDTH] and [EXPANDED_WIDTH]: Instagram's window width class and the factory every size
 * check goes through. Instagram has other enums named COMPACT and EXPANDED (a caption's snippet,
 * a header's state), but none with MEDIUM and the two widths. Fails when there isn't exactly one,
 * or when something jumps to the factory's first instruction.
 */
internal fun BytecodePatchContext.findWidthClass(): WidthClass {
    val factories = classesLoadingString(MEDIUM).filter { it.superclass == ENUM }.filter { classDef ->
        classDef.methods.any { it.name == "<clinit>" && listOf(COMPACT, MEDIUM, EXPANDED).all { name -> loadsString(it, name) } }
    }.flatMap { classDef -> classDef.methods.filter { it.isWidthFactory(classDef.type) } }
    val factory = factories.singleOrNull()
        ?: refuse("expected one window width class with a factory telling $MEDIUM_WIDTH and $EXPANDED_WIDTH dp apart, found ${factories.size}")
    if (0 in factory.jumpTargets()) refuse("something in ${factory.definingClass}->${factory.name} jumps to its first instruction")
    return WidthClass(factory.definingClass, factory.name)
}

private fun Method.isWidthFactory(type: String): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != type || parameterTypes.map(CharSequence::toString) != listOf("I")) {
        return false
    }
    val literals = instructions().mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
    return MEDIUM_WIDTH in literals && EXPANDED_WIDTH in literals
}

/**
 * Finds the one method answering a boolean for a Context that loads [HINGE_FEATURE], in a class
 * that also calls the width class factory: Instagram's big screen helper deciding the phone is a
 * foldable. Its one hasSystemFeature call must have its answer moved out right after, and nothing
 * may jump onto the instruction after that. Camera and sensor code loads the same feature in other
 * classes, so the helper is told apart by its calls to the factory. Fails when there isn't exactly
 * one, or it reads the feature some other way.
 */
internal fun BytecodePatchContext.findHingeCheck(width: WidthClass): HingeCheck {
    val sorting = classesCalling(width.type, width.factory).mapTo(HashSet()) { it.type }
    val checks = classesLoadingString(HINGE_FEATURE).filter { it.type in sorting }.flatMap { classDef ->
        classDef.methods.filter {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) && loadsString(it, HINGE_FEATURE)
        }
    }
    val check = checks.singleOrNull()
        ?: refuse("expected one Context check loading $HINGE_FEATURE beside the width class, found ${checks.size}")
    val where = "${check.definingClass}->${check.name}"
    val code = check.instructions()
    val calls = code.indices.filter { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == PACKAGE_MANAGER && called.name == "hasSystemFeature"
    }
    val call = calls.singleOrNull() ?: refuse("expected one hasSystemFeature call in $where, found ${calls.size}")
    val result = code.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) refuse("$where drops what hasSystemFeature answers")
    if (call + 2 in check.jumpTargets()) refuse("something in $where jumps onto the instruction after its hinge check")
    return HingeCheck(check.definingClass, check.name, call + 1, (result as OneRegisterInstruction).registerA)
}

/**
 * Hands the width to [PHONE_WIDTH] first thing in the factory, which answers a compact width while
 * the switch is on, and the hinge check's answer to [NOT_FOLDABLE] right after it's moved out.
 * Range calls, so the registers may be any.
 */
internal fun BytecodePatchContext.keepPhoneLayout(width: WidthClass, hinge: HingeCheck) {
    val factory = mutableClassDefBy(width.type).methods.single {
        it.name == width.factory && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == width.type &&
            it.parameterTypes.map(CharSequence::toString) == listOf("I")
    }
    factory.addInstructions(
        0,
        """
            invoke-static/range { p0 .. p0 }, $PHONE_WIDTH
            move-result p0
        """,
    )
    val check = mutableClassDefBy(hinge.type).methods.single {
        it.name == hinge.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT)
    }
    val register = "v${hinge.register}"
    check.addInstructions(
        hinge.at + 1,
        """
            invoke-static/range { $register .. $register }, $NOT_FOLDABLE
            move-result $register
        """,
    )
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
