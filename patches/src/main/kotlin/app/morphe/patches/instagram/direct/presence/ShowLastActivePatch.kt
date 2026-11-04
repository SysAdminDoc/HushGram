/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val LAST_ACTIVE_PATCH = "Show when someone was last active"
internal const val LAST_ACTIVE = "$EXTENSION_PACKAGE/direct/LastActive;"
internal const val EXACT_ACTIVE = "$LAST_ACTIVE->exact(J)Ljava/lang/String;"

/**
 * Two limits Instagram's presence text method loads as literals, which no Redex rename touches:
 * it counts hours up to eight of them, and gives nothing past a week. Both are in minutes.
 */
internal const val EIGHT_HOURS_IN_MINUTES = 0x1e0L
internal const val WEEK_IN_MINUTES = 0x2760L

/** The method's parameters: the phone's resources and the list holding the person's presence. */
internal val PRESENCE_TEXT_PARAMETERS = listOf("Landroid/content/res/Resources;", "Ljava/util/List;")

/**
 * Shows the exact time someone was last active, under their name in your messages and in a chat's
 * header, instead of Active 3h ago. In the default selection with its switch off, since
 * Instagram's own wording is the design.
 *
 * Only the text changes. Active now, and the rows Instagram gives no presence, stay as Instagram
 * has them, and nothing is requested or sent.
 */
@Suppress("unused")
val showLastActivePatch = bytecodePatch(
    name = "Show when someone was last active",
    description = "Shows the exact time someone was last on, like Active at 3:42 PM, in your messages and in a " +
        "chat, instead of Active 3h ago. Active now stays. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("lastActive")
        showLastActive(findPresenceText())
        enableStatus("lastActive")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$LAST_ACTIVE_PATCH: $why")

/**
 * Where the hook goes: the method, the instruction it goes in front of, and the first of the two
 * registers holding the time the person was last active, in milliseconds.
 */
internal class PresenceTextSite(val type: String, val name: String, val index: Int, val time: Int)

/**
 * Instagram 450 turns a person's presence into text in one instance method of a presence helper
 * class: (Resources, List) answering a String, which loads the limits [EIGHT_HOURS_IN_MINUTES] and
 * [WEEK_IN_MINUTES]. Your messages and a chat's header both go through it, and more than one
 * method matching, or none, fails the patch.
 *
 * Inside it the person is the list's first item, cast to Instagram's presence class, whose long
 * field is the time they were last active. The first check on that item, a static (item, long)
 * boolean followed by an if-nez (454 inlines it as the item's own boolean, then a limit compare to
 * the same place), says Active now, and the long compare after it that branches to
 * the same place is the second Active now check, on how long ago that was. The hook goes right
 * after both, so Active now stays Instagram's and everything past it counts minutes and hours.
 */
internal fun BytecodePatchContext.findPresenceText(): PresenceTextSite {
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

    // The item's time: the first long read of a field of the item cast out of the list.
    val read = code.indices.firstOrNull { code[it].opcode == Opcode.IGET_WIDE } ?: refuse("$where reads no long field")
    val field = code[read].reference() as? FieldReference ?: refuse("$where reads its time through no field")
    val cast = code.getOrNull(read - 1)
    val get = code.getOrNull(read - 3)?.reference() as? MethodReference
    if (field.type != "J" || cast?.opcode != Opcode.CHECK_CAST || code.getOrNull(read - 2)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        get?.definingClass != "Ljava/util/List;" || get.name != "get" ||
        (cast.reference() as? TypeReference)?.type != field.definingClass
    ) refuse("$where doesn't read its time from the list's first item")
    val time = (code[read] as OneRegisterInstruction).registerA

    // The Active now check on the item and the clock: a static (item, long) boolean, then if-nez.
    // Instagram 454 inlines it: the item's own boolean and an if-nez, then the time since against
    // the limit and an if-lez, both going to Active now.
    val call = (read + 1 until code.size).firstOrNull { at ->
        val called = code[at].reference() as? MethodReference
        code[at].opcode == Opcode.INVOKE_STATIC && called != null && called.returnType == "Z" &&
            called.parameterTypes.map(CharSequence::toString) == listOf(field.definingClass, "J")
    }
    val inlined = if (call != null) null else (read + 1 until code.size).firstOrNull { at ->
        code[at].opcode == Opcode.IGET_BOOLEAN && (code[at].reference() as? FieldReference)?.definingClass == field.definingClass
    }
    val branch = when {
        call != null -> call + 2
        inlined != null -> inlined + 1
        else -> refuse("$where has no Active now check on the item")
    }
    if (call != null && code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT || code.getOrNull(branch)?.opcode != Opcode.IF_NEZ) {
        refuse("$where doesn't branch on its Active now check")
    }
    val flow = ControlFlow.of(method)
    val active = flow.normal[branch].firstOrNull() ?: refuse("$where's Active now branch goes nowhere")
    fun limitCheck(after: Int) = (after + 1 until code.size).firstOrNull { at ->
        code[at].opcode == Opcode.IF_LEZ && flow.normal[at].firstOrNull() == active
    }
    val first = if (inlined == null) branch else limitCheck(branch) ?: refuse("$where's inlined Active now check has no limit")
    // The second check: the compare of the time since against the limit, branching to the same place.
    val second = limitCheck(first) ?: refuse("$where has no second Active now check going where the first does")
    if (code[second - 1].opcode != Opcode.CMP_LONG) refuse("$where's second Active now check isn't on a long compare")
    val index = second + 1
    if (index >= code.size) refuse("$where ends at its second Active now check")

    // The time stays in its pair from the read to the hook, and nothing jumps into the code between.
    for (at in read + 1 until index) {
        val written = (code[at] as? OneRegisterInstruction)?.registerA ?: continue
        if (!code[at].opcode.setsRegister()) continue
        val covered = if (code[at].opcode.setsWideRegister()) setOf(written, written + 1) else setOf(written)
        if (covered.any { it == time || it == time + 1 }) refuse("$where writes over the time before the hook, at instruction $at")
    }
    if (method.jumpTargets().any { it in (read + 1)..index }) refuse("$where jumps into the code between the time and the hook")

    mutableClassDefBy(LAST_ACTIVE).methods.singleOrNull {
        it.name == "exact" && it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(Any::toString) == listOf("J") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the extension has no public static $EXACT_ACTIVE")
    return PresenceTextSite(method.definingClass, method.name, index, time)
}

/**
 * Asks [EXACT_ACTIVE] with the time the person was last active, in front of the instruction after
 * the second Active now check, and returns what it answers unless that's null, when Instagram's
 * own counting goes on. The answer borrows a local nothing reads past the hook.
 */
internal fun BytecodePatchContext.showLastActive(site: PresenceTextSite) {
    val method: MutableMethod = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(CharSequence::toString) == PRESENCE_TEXT_PARAMETERS
    }
    val scratch = method.freeLocalsAt(
        LAST_ACTIVE_PATCH, site.index, 1, highest = 255, except = listOf(site.time, site.time + 1),
    ).single()
    method.addInstructionsWithLabels(
        site.index,
        """
            invoke-static/range { v${site.time} .. v${site.time + 1} }, $EXACT_ACTIVE
            move-result-object v$scratch
            if-eqz v$scratch, :count
            return-object v$scratch
        """,
        ExternalLabel("count", method.getInstruction(site.index)),
    )
}

private fun Method.wides(): List<Long> =
    implementation?.instructions?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }.orEmpty()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
