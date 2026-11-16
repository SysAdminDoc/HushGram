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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Phone layout on big screens"
internal const val PHONE_WIDTH = "$EXTENSION_PACKAGE/misc/PhoneLayout;->width(I)I"
internal const val NOT_FOLDABLE = "$EXTENSION_PACKAGE/misc/PhoneLayout;->foldable(I)Z"
internal const val ROTATES = "$EXTENSION_PACKAGE/misc/PhoneLayout;->rotates(I)Z"

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
private const val ACTIVITY = "Landroid/app/Activity;"
private const val SET_ORIENTATION = "setRequestedOrientation"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"

/**
 * Keeps Instagram's phone layout on a foldable or tablet. Instagram sorts each window by width into
 * compact, medium and expanded, and anything but compact gets the big screen layout, with the tab
 * bar on a rail down the side. A foldable's hinge sensor marks the phone as a foldable on top of
 * that. With the switch on, every window sorts as compact and the hinge sensor is left out. Off to
 * start, so a tablet keeps Instagram's own layout until its owner picks the phone one.
 *
 * The sorting lives in two places on most builds: the factory, and the helper's "isn't this compact"
 * check, which Redex built with its own copy of the factory's comparisons. Instagram's main activity
 * and the rail ask that copy, so a hook on the factory alone changes the feed's header and leaves the
 * rail (#515). A build that kept the check as a call to the factory needs only the factory's hook.
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
        val widthCheck = findWidthCheck(width, hinge)
        val turning = findOrientationSites(findScreenCheck(hinge))
        keepPhoneLayout(width, widthCheck, hinge)
        letBigScreensTurn(turning)
        enableStatus("phoneLayout")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The width class enum's factory: the static `(I)` method answering the enum for a width in dp. */
internal class WidthClass(val type: String, val factory: String)

/** The big screen helper's static `(I)Z` check, answering whether a width in dp isn't compact. */
internal class WidthCheck(val type: String, val name: String)

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
    return holdsBothWidths()
}

private fun Method.holdsBothWidths(): Boolean {
    val literals = instructions().mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
    return MEDIUM_WIDTH in literals && EXPANDED_WIDTH in literals
}

/**
 * Finds the static method in the big screen helper taking an int and answering a boolean that holds
 * [MEDIUM_WIDTH] and [EXPANDED_WIDTH]: Instagram's "isn't this window compact" check, carrying its
 * own copy of the factory's comparisons. Null on a build where that check calls the factory instead,
 * so the factory's hook already covers it. Fails when there's more than one copy, when something
 * jumps to a copy's first instruction, or when the helper has neither.
 */
internal fun BytecodePatchContext.findWidthCheck(width: WidthClass, hinge: HingeCheck): WidthCheck? {
    val checks = mutableClassDefBy(hinge.type).methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf("I")
    }
    val copies = checks.filter { it.holdsBothWidths() }
    if (copies.size > 1 || (copies.isEmpty() && checks.none { it.callsFactory(width) })) {
        refuse("expected one width check holding $MEDIUM_WIDTH and $EXPANDED_WIDTH dp or calling the factory in ${hinge.type}, found ${copies.size}")
    }
    val copy = copies.singleOrNull() ?: return null
    if (0 in copy.jumpTargets()) refuse("something in ${copy.definingClass}->${copy.name} jumps to its first instruction")
    return WidthCheck(copy.definingClass, copy.name)
}

private fun Method.callsFactory(width: WidthClass): Boolean = instructions().any {
    val called = (it as? ReferenceInstruction)?.reference as? MethodReference
    called != null && called.definingClass == width.type && called.name == width.factory
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
 * Hands the width to [PHONE_WIDTH] first thing in the factory and in the width check if the build
 * has its own copy, which answers a compact width while the switch is on, and the hinge check's
 * answer to [NOT_FOLDABLE] right after it's moved out. Range calls, so the registers may be any.
 */
internal fun BytecodePatchContext.keepPhoneLayout(width: WidthClass, widthCheck: WidthCheck?, hinge: HingeCheck) {
    val sorters = listOfNotNull(
        Triple(width.type, width.factory, width.type),
        widthCheck?.let { Triple(it.type, it.name, "Z") },
    ).map { (type, name, returns) ->
        mutableClassDefBy(type).methods.single {
            it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I")
        }
    }
    for (sorter in sorters) {
        sorter.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, $PHONE_WIDTH
                move-result p0
            """,
        )
    }
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

/** The big screen helper's static `(Activity)Z` check, answering whether a screen's width and height are both past compact. */
internal class ScreenCheck(val type: String, val name: String)

/** A screen check's answer moved out at [at] into [register], in a method picking an orientation. */
internal class OrientationSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val at: Int,
    val register: Int,
)

/**
 * Finds the one static method in the big screen helper taking an Activity and answering a boolean that
 * calls the helper's own `(FII)Z` sorting of a size: the check whether the activity's largest window is
 * big. The helper's other Activity check asks only about split screen. Fails when there isn't exactly one.
 */
internal fun BytecodePatchContext.findScreenCheck(hinge: HingeCheck): ScreenCheck {
    val checks = mutableClassDefBy(hinge.type).methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY) && it.sortsASize(hinge.type)
    }
    val check = checks.singleOrNull()
        ?: refuse("expected one Activity check sorting a screen's size in ${hinge.type}, found ${checks.size}")
    return ScreenCheck(check.definingClass, check.name)
}

private fun Method.sortsASize(helper: String): Boolean = instructions().any {
    val called = (it as? ReferenceInstruction)?.reference as? MethodReference
    called != null && called.definingClass == helper && called.returnType == "Z" &&
        called.parameterTypes.map(CharSequence::toString) == listOf("F", "I", "I")
}

/**
 * Finds every call to the screen check whose answer picks an orientation: in a method that calls
 * setRequestedOrientation itself, calls a static `(Activity, int)` method that does (Instagram's
 * orientation helper), or makes a Runnable whose run() calls that helper (the main activity's chooser
 * posts one). The check's other callers sort layouts, and those keep the phone answer. A call whose
 * answer is dropped picks nothing and is left out. Fails when nothing picks an orientation, or
 * something jumps onto the instruction after a call's move-result.
 */
internal fun BytecodePatchContext.findOrientationSites(check: ScreenCheck): List<OrientationSite> {
    val helpers = classesCalling(ACTIVITY, SET_ORIENTATION).flatMap { classDef ->
        classDef.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY, "I") && it.calls { call -> call.turns() }
        }.map { "${it.definingClass}->${it.name}" }
    }.toSet()
    val runnables = classesCallingAll(helpers).filter { classDef ->
        classDef.methods.any { it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() && it.callsAny(helpers) }
    }.mapTo(HashSet()) { it.type }
    val sites = mutableListOf<OrientationSite>()
    for (classDef in classesCalling(check.type, check.name, listOf(ACTIVITY), "Z")) {
        for (method in classDef.methods) {
            val code = method.instructions()
            val calls = code.indices.filter { at -> code[at].calls(check) }
            if (calls.isEmpty() || !method.picksAnOrientation(helpers, runnables)) continue
            val where = "${method.definingClass}->${method.name}"
            for (call in calls) {
                val result = code.getOrNull(call + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) continue
                if (call + 2 in method.jumpTargets()) refuse("something in $where jumps onto the instruction after its screen check")
                sites += OrientationSite(method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString),
                    method.returnType, call + 1, (result as OneRegisterInstruction).registerA)
            }
        }
    }
    if (sites.isEmpty()) refuse("expected the screen check ${check.type}->${check.name} to pick an orientation somewhere, found nowhere")
    return sites
}

private fun BytecodePatchContext.classesCallingAll(methods: Set<String>): List<ClassDef> = methods.flatMap { method ->
    classesCalling(method.substringBefore("->"), method.substringAfter("->"))
}.distinctBy { it.type }

private fun Method.picksAnOrientation(helpers: Set<String>, runnables: Set<String>): Boolean = instructions().any { instruction ->
    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        is MethodReference -> reference.turns() || "${reference.definingClass}->${reference.name}" in helpers
        is TypeReference -> instruction.opcode == Opcode.NEW_INSTANCE && reference.type in runnables
        else -> false
    }
}

private fun MethodReference.turns(): Boolean = name == SET_ORIENTATION && parameterTypes.map(CharSequence::toString) == listOf("I")

private fun Method.calls(wanted: (MethodReference) -> Boolean): Boolean = instructions().any {
    val called = (it as? ReferenceInstruction)?.reference as? MethodReference
    called != null && wanted(called)
}

private fun Method.callsAny(methods: Set<String>): Boolean = calls { "${it.definingClass}->${it.name}" in methods }

private fun Instruction.calls(check: ScreenCheck): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val called = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return called.definingClass == check.type && called.name == check.name &&
        called.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY) && called.returnType == "Z"
}

/**
 * Hands each orientation site's answer to [ROTATES] right after it's moved out, so a screen whose own
 * size is big still turns with the device while every window sorts as compact. Range calls, so the
 * registers may be any; a method's later sites go in first, so the earlier sites' indices hold.
 */
internal fun BytecodePatchContext.letBigScreensTurn(sites: List<OrientationSite>) {
    for (site in sites.sortedByDescending { it.at }) {
        val method = mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        val register = "v${site.register}"
        method.addInstructions(
            site.at + 1,
            """
                invoke-static/range { $register .. $register }, $ROTATES
                move-result $register
            """,
        )
    }
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
