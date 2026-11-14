/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
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

internal const val ACTIVE_NOW_PATCH = "Shorter Active now"
internal const val ACTIVE_NOW = "$EXTENSION_PACKAGE/direct/ActiveNow;"
internal const val ACTIVE_NOW_WINDOW = "$ACTIVE_NOW->window(J)J"
internal const val TO_MILLIS = "Ljava/util/concurrent/TimeUnit;->toMillis(J)J"

/**
 * Where the limit is set: the class whose static initializer stores it, the field, the index of the
 * store, and the first of the two registers holding the limit in milliseconds there.
 */
internal class ActiveNowSite(val type: String, val field: FieldReference, val index: Int, val value: Int)

/**
 * Lets someone drop off Active now one minute after Instagram last saw them, as the website does,
 * instead of six and a half (#683).
 *
 * Instagram keeps that limit in a static final long of its presence formatter, set once in the
 * class's static initializer from `TimeUnit.SECONDS.toMillis(390)`, and every Active now check in
 * the app compares against it. The patch hands the value to the extension right before it's stored,
 * so a restart is needed after flipping the switch. Someone the server reports as active keeps
 * Active now either way. In the default selection with its switch off.
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
 * Finds the limit through Instagram's one presence text method, the one [findPresenceText] hooks:
 * the one static long it reads off its own class, which a long compare uses right after. The class's
 * static initializer has to store that field once, straight from `TimeUnit.toMillis`, with nothing
 * jumping to the store, and the field has to be static final so nothing else writes it. Anything
 * else fails the patch before an instruction changes.
 */
internal fun BytecodePatchContext.findActiveNow(): ActiveNowSite {
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

/** Only called once [findActiveNow] found everything: the limit goes through [ACTIVE_NOW_WINDOW] right before it's stored. */
internal fun BytecodePatchContext.shortenActiveNow(site: ActiveNowSite) {
    val clinit = mutableClassDefBy(site.type).methods.single { it.name == "<clinit>" }
    clinit.addInstructions(
        site.index,
        """
            invoke-static/range { v${site.value} .. v${site.value + 1} }, $ACTIVE_NOW_WINDOW
            move-result-wide v${site.value}
        """,
    )
}

private fun Method.wides(): List<Long> =
    implementation?.instructions?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }.orEmpty()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
