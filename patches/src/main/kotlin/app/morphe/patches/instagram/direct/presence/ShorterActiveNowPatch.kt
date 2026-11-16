/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val ACTIVE_NOW_PATCH = "Shorter Active now"
internal const val ACTIVE_NOW = "$EXTENSION_PACKAGE/direct/ActiveNow;"
internal const val ACTIVE_NOW_WINDOW = "$ACTIVE_NOW->window(J)J"
internal const val TO_MILLIS = "Ljava/util/concurrent/TimeUnit;->toMillis(J)J"

/** Instagram's Active now limit, `TimeUnit.SECONDS.toMillis(390)`, as 455's Redex writes it into each check. */
internal const val ACTIVE_NOW_LIMIT = 390_000L

/**
 * Where the limit is set: the class whose static initializer stores it, the field, the index of the
 * store, and the first of the two registers holding the limit in milliseconds there.
 */
internal class ActiveNowSite(val type: String, val field: FieldReference, val index: Int, val value: Int)

/**
 * A check that loads the limit itself: the method, the index of its `const-wide` of
 * [ACTIVE_NOW_LIMIT], and the first of the two registers it loads it into.
 */
internal class ActiveNowCheck(val method: Method, val index: Int, val value: Int)

/** Everywhere the limit comes from: the static initializer's store, the checks' own copies, or both. */
internal class ActiveNowTargets(val stored: ActiveNowSite?, val checks: List<ActiveNowCheck>)

/**
 * Lets someone drop off Active now one minute after Instagram last saw them, as the website does,
 * instead of six and a half (#683).
 *
 * Up to 454, and on 455's 386113922 and 386113923, Instagram keeps that limit in a static final long
 * of its presence formatter, set once in the class's static initializer from
 * `TimeUnit.SECONDS.toMillis(390)`, and every Active now check in the app compares against it. The
 * patch hands the value to the extension right before it's stored, so a restart is needed after
 * flipping the switch there. The other 455 builds drop the field and load 390000 in each of the six
 * checks; the patch hands each check's copy to the extension right after it's loaded. Someone the
 * server reports as active keeps Active now either way. In the default selection with its switch off.
 */
@Suppress("unused")
val shorterActiveNowPatch = bytecodePatch(
    name = "Shorter Active now",
    description = "Someone shows as Active now for one minute after they leave, like on the website, instead of " +
        "six and a half. Starts off. Turn it on in HushGram settings > Messages.",
) {
    // Messages is at the limit, and this fixes Instagram's app keeping a stale Active now.
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("shortActiveNow")
        shortenActiveNow(findActiveNow())
        enableStatus("shortActiveNow")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$ACTIVE_NOW_PATCH: $why")

/**
 * Finds the limit through Instagram's one presence text method, the one [findPresenceText] hooks.
 *
 * Where that method reads a static long off its own class, which a long compare uses right after,
 * the class's static initializer has to store that field once, straight from `TimeUnit.toMillis`,
 * with nothing jumping to the store, and the field has to be static final so nothing else writes it.
 *
 * Every method that reads a long off the presence the text method casts its first item to, and loads
 * [ACTIVE_NOW_LIMIT] as a long, is a check too (`LX/04Cv;->A03` and five more on 386113969), and has
 * to compare against it as soon as it's loaded, with nothing jumping onto the compare. The text
 * method has to be one of them where it reads no static long. Anything else fails the patch before
 * an instruction changes.
 */
internal fun BytecodePatchContext.findActiveNow(): ActiveNowTargets {
    val extension = classDefByOrNull(ACTIVE_NOW) ?: refuse("the extension has no $ACTIVE_NOW")
    extension.methods.singleOrNull {
        it.name == "window" && it.returnType == "J" && it.parameterTypes.map(CharSequence::toString) == listOf("J") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the extension has no public static $ACTIVE_NOW_WINDOW")

    val methods = classesLoading(WEEK_IN_MINUTES).flatMap { classDef ->
        classDef.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/String;" &&
                method.parameterTypes.map(CharSequence::toString) == PRESENCE_TEXT_PARAMETERS &&
                method.wides().containsAll(listOf(WEEK_IN_MINUTES, EIGHT_HOURS_IN_MINUTES))
        }
    }
    val method = methods.singleOrNull() ?: refuse("expected one presence text method, found ${methods.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()

    val loads = code.indices.filter { code[it].opcode == Opcode.SGET_WIDE }
    val stored = if (loads.isEmpty()) null else storedLimit(method, code, loads)
    val model = presenceModel(code) ?: refuse("$where casts the first presence it reads to no class")
    val checks = limitChecks(model)
    if (stored == null && checks.none { it.method.key() == method.key() }) {
        refuse("expected $where to read one static long or load $ACTIVE_NOW_LIMIT itself, found neither")
    }
    return ActiveNowTargets(stored, checks)
}

/** The field form: the one static long [method] reads, and where its class's static initializer stores it. */
private fun BytecodePatchContext.storedLimit(method: Method, code: List<Instruction>, loads: List<Int>): ActiveNowSite {
    val where = "${method.definingClass}->${method.name}"
    val load = loads.singleOrNull() ?: refuse("expected $where to read one static long, found ${loads.size}")
    val field = code[load].reference() as? FieldReference ?: refuse("$where reads its limit through no field")
    if (field.type != "J" || field.definingClass != method.definingClass) refuse("$where reads its limit off $field")
    val limit = (code[load] as OneRegisterInstruction).registerA
    val compare = code.getOrNull(load + 1) as? ThreeRegisterInstruction
    if (compare?.opcode != Opcode.CMP_LONG || (compare.registerB != limit && compare.registerC != limit)) {
        refuse("$where doesn't compare against $field as soon as it reads it")
    }

    val owner = classDefByOrNull(field.definingClass) ?: refuse("this Instagram build has no ${field.definingClass}")
    val declared = owner.fields.singleOrNull { it.name == field.name && it.type == "J" } ?: refuse("${owner.type} has no ${field.name}")
    if (!AccessFlags.STATIC.isSet(declared.accessFlags) || !AccessFlags.FINAL.isSet(declared.accessFlags)) {
        refuse("$field isn't static final, so something besides its initializer could set it")
    }
    val clinit = owner.methods.singleOrNull { it.name == "<clinit>" } ?: refuse("${owner.type} has no static initializer")
    val init = clinit.implementation?.instructions?.toList() ?: refuse("${owner.type}'s static initializer has no code")
    val stores = init.indices.filter { init[it].opcode == Opcode.SPUT_WIDE && init[it].reference()?.toString() == field.toString() }
    val store = stores.singleOrNull() ?: refuse("expected ${owner.type}'s static initializer to set $field once, found ${stores.size}")
    val value = (init[store] as OneRegisterInstruction).registerA
    val result = init.getOrNull(store - 1)
    if (result?.opcode != Opcode.MOVE_RESULT_WIDE || (result as OneRegisterInstruction).registerA != value ||
        init.getOrNull(store - 2)?.reference()?.toString() != TO_MILLIS
    ) refuse("$field isn't set straight from $TO_MILLIS")
    if (store in clinit.jumpTargets()) refuse("something in ${owner.type}'s static initializer jumps to where $field is set")
    return ActiveNowSite(owner.type, field, store, value)
}

/** The class the presence text method casts the first item of its list to, right after it takes it. */
private fun presenceModel(code: List<Instruction>): String? {
    val get = code.indexOfFirst { instruction ->
        val call = instruction.reference() as? MethodReference
        instruction.opcode == Opcode.INVOKE_INTERFACE && call?.definingClass == "Ljava/util/List;" && call.name == "get"
    }
    if (get < 0) return null
    val item = code.getOrNull(get + 1)
    val cast = code.getOrNull(get + 2)
    if (item?.opcode != Opcode.MOVE_RESULT_OBJECT || cast?.opcode != Opcode.CHECK_CAST) return null
    if ((item as OneRegisterInstruction).registerA != (cast as OneRegisterInstruction).registerA) return null
    return (cast.reference() as? TypeReference)?.type
}

/** Every load of [ACTIVE_NOW_LIMIT] as a long in a method reading a long off [model], each checked. */
private fun BytecodePatchContext.limitChecks(model: String): List<ActiveNowCheck> =
    classesLoading(ACTIVE_NOW_LIMIT).flatMap { classDef ->
        classDef.methods.filter { method -> method.limitLoads().isNotEmpty() && method.readsLongOff(model) }.flatMap { method ->
            val where = "${method.definingClass}->${method.name}"
            val code = method.implementation!!.instructions.toList()
            val targets = method.jumpTargets()
            method.limitLoads().map { at ->
                val value = (code[at] as OneRegisterInstruction).registerA
                val compare = code.getOrNull(at + 1) as? ThreeRegisterInstruction
                if (compare?.opcode != Opcode.CMP_LONG || (compare.registerB != value && compare.registerC != value)) {
                    refuse("$where doesn't compare against the Active now limit as soon as it loads it")
                }
                if (at + 1 in targets) refuse("something in $where jumps onto its compare with the Active now limit")
                if (value > 254) refuse("$where loads the Active now limit into v$value, past v254")
                ActiveNowCheck(method, at, value)
            }
        }
    }

/**
 * Only called once [findActiveNow] found everything: the limit goes through [ACTIVE_NOW_WINDOW] right
 * before the initializer stores it, and right after each check loads its own copy.
 */
internal fun BytecodePatchContext.shortenActiveNow(targets: ActiveNowTargets) {
    targets.stored?.let { site ->
        val clinit = mutableClassDefBy(site.type).methods.single { it.name == "<clinit>" }
        clinit.addInstructions(site.index, window(site.value))
    }
    // Last first, so a method with two loads keeps the earlier one's index.
    for (check in targets.checks.sortedByDescending { it.index }) {
        mutableCheck(check.method).addInstructions(check.index + 1, window(check.value))
    }
}

private fun window(value: Int) = """
    invoke-static/range { v$value .. v${value + 1} }, $ACTIVE_NOW_WINDOW
    move-result-wide v$value
"""

private fun BytecodePatchContext.mutableCheck(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.key() == method.key() }

private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private val LONG_CONSTANTS = setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE)

private fun Method.limitLoads(): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        code[at].opcode in LONG_CONSTANTS && (code[at] as WideLiteralInstruction).wideLiteral == ACTIVE_NOW_LIMIT
    }
}

private fun Method.readsLongOff(model: String): Boolean = implementation?.instructions?.any { instruction ->
    instruction.opcode == Opcode.IGET_WIDE && (instruction.reference() as? FieldReference)?.definingClass == model
} == true

private fun Method.wides(): List<Long> =
    implementation?.instructions?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }.orEmpty()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
