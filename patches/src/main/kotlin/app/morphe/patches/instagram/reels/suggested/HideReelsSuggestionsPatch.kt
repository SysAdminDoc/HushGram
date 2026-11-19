/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.suggested

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.feed.back.enumConstantName
import app.morphe.patches.instagram.feed.requireOneKindField
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide suggested accounts in Reels"
internal const val REELS_SUGGESTIONS = "$EXTENSION_PACKAGE/reels/ReelsSuggestions;"
internal const val REELS_FILTER = "$REELS_SUGGESTIONS->filter(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val NETEGO_FILTER = "$REELS_SUGGESTIONS->netego(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"
internal const val FRIENDS_TAB_FILTER = "$REELS_SUGGESTIONS->friendsTab(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * A string only the converter that makes each Reels item (a clips item) from the server's loads:
 * the start of the id it gives a live's preview item. 456 dropped the converter's trace marker
 * (ClipsItemExt_toClipsItemCombined).
 */
internal const val TO_CLIPS_ITEM = "live_preview_"

/**
 * The item kind the page of people to follow is made with, which the Reels friends tab shows when
 * it has no reels ("Instagram is better with friends", #167). The method making it into a clips
 * item of its own reads it; that item never goes through the converter. 456 dropped that method's
 * trace marker (ClipsItemExt_toFriendLaneEmptyStateClipsItem).
 */
internal const val TO_FRIENDS_TAB_PAGE = "SUGGESTED_USERS"

/**
 * Two of the netego unit types, which the Reels viewer's binder dispatch compares a netego unit's
 * type with to pick the binder of friends or creators to follow.
 */
internal const val FRIENDS_TO_FOLLOW = "friend_su_in_reels"
internal const val CREATORS_TO_FOLLOW = "creators_in_reels"

/** The clips item kinds of a card of accounts or creators to follow, which ReelsSuggestions drops. */
internal val SUGGESTED_KINDS = listOf(
    "SUGGESTED_USERS", "CREATORS_YOU_MAY_FOLLOW", "NETEGO_SUGGESTED_USERS", "NETEGO_SUGGESTED_CREATORS",
)

/** The clips item kind of a Catch up with friends page of stories, which ReelsSuggestions drops under its own switch (#730). */
internal val STORY_KINDS = listOf("STORIES_IN_REELS")

/** The clips item kind of a card bringing people over to Threads, which ReelsSuggestions drops under its own switch (#765). */
internal val THREADS_KINDS = listOf("NETEGO_THREADS_IN_REELS_UNIT_ACQUISITION")

/** Every kind ReelsSuggestions takes out, all named by the one enum field it reads. */
internal val READ_KINDS = SUGGESTED_KINDS + STORY_KINDS + THREADS_KINDS

private const val STRING = "Ljava/lang/String;"

/**
 * Leaves the cards of accounts to follow out of Reels. In the default selection with its switch
 * off: suggestions are one of Instagram's features, so leaving them out is the user's pick. Asked
 * for in #15 and #20.
 */
@Suppress("unused")
val hideReelsSuggestionsPatch = bytecodePatch(
    name = "Hide suggested accounts in Reels",
    description = "Leaves out the cards of accounts to follow that Instagram puts between reels, and on switches of " +
        "their own its Catch up with friends pages of stories and its Threads cards. Every reel still plays. " +
        "Starts off. Turn them on in HushGram settings > Reels.",
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelsSuggestions")
        val sites = findReelsSuggestions()
        hideReelsSuggestions(sites)
        enableStatus("reelsSuggestions")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The converter that makes a clips item from the server's Reels item: the one method loading
 * [TO_CLIPS_ITEM], however Redex pooled it. None or more than one stops [patch] before anything
 * changes.
 */
internal fun BytecodePatchContext.reelsItemConverter(patch: String): MutableMethod {
    // A pool's own getter holds every string it pools, so it's left out.
    val found = classesLoadingString(TO_CLIPS_ITEM).sortedBy { it.type }.flatMap { it.methods }.filter { method ->
        !(method.parameterTypes.map(Any::toString) == listOf("I") && method.returnType == "Ljava/lang/String;") &&
            loadsString(method, TO_CLIPS_ITEM)
    }
    val converter = found.singleOrNull() ?: throw PatchException(
        "$patch: expected exactly one Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutableClassDefBy(converter.definingClass).methods.single { method ->
        method.name == converter.name && method.returnType == converter.returnType &&
            method.parameterTypes.map(Any::toString) == converter.parameterTypes.map(Any::toString)
    }
}

/**
 * The method that makes the friends tab's page of people to follow into a clips item: the one
 * static method of [converter]'s class taking one value that reads the [TO_FRIENDS_TAB_PAGE] constant
 * of an enum. What it answers is checked after, so a maker answering something else is named.
 */
private fun BytecodePatchContext.friendsTabPageMaker(converter: Method): Method {
    val makers = classDefBy(converter.definingClass).methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
            method.instructions().any { instruction ->
                val field = instruction.fieldReference()
                instruction.opcode == Opcode.SGET_OBJECT && field != null && field.type == field.definingClass &&
                    classDefByOrNull(field.definingClass)?.takeIf { it.superclass == "Ljava/lang/Enum;" }
                        ?.let { enumConstantName(it, field) } == TO_FRIENDS_TAB_PAGE
            }
    }
    return makers.singleOrNull() ?: refuse(
        "expected one Reels friends tab page maker of ${converter.definingClass} reading the $TO_FRIENDS_TAB_PAGE kind, " +
            "found ${makers.map { "${it.definingClass}->${it.name}" }}",
    )
}

/**
 * The Reels viewer's binder dispatch for one clips item and its position, answering the binder:
 * the one method taking an object and an int, answering an object, that compares a netego unit's
 * type with [CREATORS_TO_FOLLOW]. 452 compared [FRIENDS_TO_FOLLOW] in the same method; 453 leaves
 * that name to the unit type enum and handles it elsewhere, so it isn't required here.
 */
internal object NetegoDispatchFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L", "I"),
    filters = listOf(string(CREATORS_TO_FOLLOW)),
    custom = { method, _ -> method.holdsString(CREATORS_TO_FOLLOW) },
)

/**
 * The converter, its item type, and its return of the item made from a netego unit: that return's
 * index and register, the register holding the netego unit there, and the unit's type field. Then
 * the method making the friends tab's page of people to follow into an item of the same type.
 */
internal class ReelsSuggestionSites(
    val converter: String,
    val name: String,
    val parameters: List<String>,
    val itemType: String,
    val netegoReturn: Int,
    val netegoItem: Int,
    val netegoUnit: Int,
    val typeField: String,
    val friendsTab: String,
    val friendsTabName: String,
    val friendsTabParameters: List<String>,
)

/**
 * Finds the converter by [TO_CLIPS_ITEM] and checks that its clips item names [READ_KINDS] in
 * one enum field, the one the extension reads. The netego unit's type is the one String field the
 * binder dispatch reads off another class, and that class is the netego unit: the converter has to
 * return, once, the item a call taking the unit makes, with the unit still in its register and
 * nothing jumping to the return. Where Redex built that call's item in place, as 455 does on
 * some builds ([inlinedNetegoReturns]), the item's constructor right before the return counts. The friends tab's page is made by the one method
 * [friendsTabPageMaker] finds, which has to return its item somewhere. Fails
 * before anything changes when any of it isn't there exactly once, since that's an update this
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelsSuggestions(): ReelsSuggestionSites {
    val converter = reelsItemConverter(PATCH)
    val where = "${converter.definingClass}->${converter.name}"
    val itemType = converter.returnType
    if (!itemType.startsWith("L") || classDefByOrNull(itemType) == null) refuse("$where answers $itemType, which isn't a class of the app")
    requireOneKindField(PATCH, itemType, READ_KINDS)

    val dispatch = uniqueMethod(PATCH, "Reels binder dispatch comparing $CREATORS_TO_FOLLOW", NetegoDispatchFingerprint)
    if (dispatch.parameterTypes.first().toString() != itemType) {
        refuse("the Reels binder dispatch ${dispatch.definingClass}->${dispatch.name} doesn't take a $itemType")
    }
    val typeFields = dispatch.instructions().filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.fieldReference() }
        .filter { it.type == STRING && it.definingClass != dispatch.definingClass && it.definingClass != itemType }
        .map { it.toString() }.distinct()
    val typeField = typeFields.singleOrNull()
        ?: refuse("expected the Reels binder dispatch to read one netego unit type, found $typeFields")
    val unitType = typeField.substringBefore("->")

    val code = converter.instructions()
    val returns = code.indices.filter { at ->
        if (code[at].opcode != Opcode.RETURN_OBJECT || at < 2) return@filter false
        val made = code[at - 2].methodReference()
        code[at - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (code[at - 1] as OneRegisterInstruction).registerA == (code[at] as OneRegisterInstruction).registerA &&
            made != null && made.returnType == itemType && made.parameterTypes.firstOrNull()?.toString() == unitType
    }
    val targets = converter.jumpTargets()
    val inlined = inlinedNetegoReturns(code, targets, itemType, unitType)
    val netegoReturn = (returns + inlined.map { it.first }).singleOrNull()
        ?: refuse("expected $where to return one item made from a $unitType, found ${returns.size + inlined.size}")
    val item = (code[netegoReturn] as OneRegisterInstruction).registerA
    val unit = inlined.singleOrNull { it.first == netegoReturn }?.second ?: run {
        // The unit is the call's first argument only in a static call; an instance call's first is its receiver.
        val call = code[netegoReturn - 2].opcode
        if (call != Opcode.INVOKE_STATIC && call != Opcode.INVOKE_STATIC_RANGE) {
            refuse("$where makes its netego item with ${call.name}, not a static call")
        }
        code[netegoReturn - 2].argumentRegisters().firstOrNull() ?: refuse("$where makes its netego item with no arguments")
    }
    if (unit == item) refuse("$where writes the netego item over its unit before returning it")
    if (netegoReturn in targets) refuse("something in $where jumps to the return of its netego item")
    // Two locals the netego hook may borrow, checked now so a method short of them changes nothing.
    converter.freeLocalsAt(PATCH, netegoReturn, 2)

    val page = friendsTabPageMaker(converter)
    val pageWhere = "${page.definingClass}->${page.name}"
    if (page.returnType != itemType) refuse("$pageWhere answers ${page.returnType}, not a $itemType")
    if (page.instructions().none { it.opcode == Opcode.RETURN_OBJECT }) refuse("$pageWhere returns no item")

    return ReelsSuggestionSites(
        converter.definingClass, converter.name, converter.parameterTypes.map(CharSequence::toString), itemType,
        netegoReturn, item, unit, typeField,
        page.definingClass, page.name, page.parameterTypes.map(CharSequence::toString),
    )
}

/**
 * First, at the netego return, the unit's type is read and the item handed to [NETEGO_FILTER]
 * with it. Then every return of the converter passes its item through [REELS_FILTER], at the
 * return's own label so a branch straight to a return passes through it too. Each answer is cast
 * back to the item type; null stays null, and every caller of the converter skips a null item.
 * Every return of the friends tab's page maker passes its item through [FRIENDS_TAB_FILTER] the
 * same way. That method answers null itself when the server sent no page, and the friends tab
 * then shows Instagram's own empty state.
 */
internal fun BytecodePatchContext.hideReelsSuggestions(sites: ReelsSuggestionSites) {
    val converter = mutableClassDefBy(sites.converter).methods.single {
        it.name == sites.name && it.parameterTypes.map(CharSequence::toString) == sites.parameters
    }
    val (item, type) = converter.freeLocalsAt(PATCH, sites.netegoReturn, 2)
    converter.addInstructions(
        sites.netegoReturn,
        """
            move-object/from16 v$type, v${sites.netegoUnit}
            iget-object v$type, v$type, ${sites.typeField}
            move-object/from16 v$item, v${sites.netegoItem}
            invoke-static { v$item, v$type }, $NETEGO_FILTER
            move-result-object v${sites.netegoItem}
            check-cast v${sites.netegoItem}, ${sites.itemType}
        """,
    )

    converter.filterEveryReturn(REELS_FILTER, sites.itemType)

    val page = mutableClassDefBy(sites.friendsTab).methods.single {
        it.name == sites.friendsTabName && it.parameterTypes.map(CharSequence::toString) == sites.friendsTabParameters
    }
    page.filterEveryReturn(FRIENDS_TAB_FILTER, sites.itemType)
}

/** Passes the item at each object return through [filter], at the return's own label, and casts it back to [itemType]. */
private fun MutableMethod.filterEveryReturn(filter: String, itemType: String) {
    val returns = instructions().withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    returns.asReversed().forEach { (index, register) ->
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $filter
                move-result-object v$register
                check-cast v$register, $itemType
            """,
        )
    }
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.holdsString(value: String) = instructions().any {
    (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((it as ReferenceInstruction).reference as StringReference).string == value
}

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * The returns of [code] whose item Redex built in place from a netego unit, the way 455 inlines
 * the netego maker on some builds (`Companion_ofNetego` in `LX/07f5;->A03` at 1055 on 386113922):
 * the return's register gets [itemType]'s constructor right before it, and before that, along code
 * nothing jumps into, a constructor taking just a [unitType] wraps the unit, whose register nothing
 * writes after. Each comes with the unit's register.
 */
private fun inlinedNetegoReturns(code: List<Instruction>, targets: Set<Int>, itemType: String, unitType: String): List<Pair<Int, Int>> =
    code.indices.mapNotNull { at ->
        if (code[at].opcode != Opcode.RETURN_OBJECT || at < 2 || at in targets) return@mapNotNull null
        val item = (code[at] as OneRegisterInstruction).registerA
        val made = code[at - 1]
        val built = made.methodReference()
        if (built == null || built.definingClass != itemType || built.name != "<init>" || !made.isDirectCall() ||
            made.argumentRegisters().firstOrNull() != item
        ) {
            return@mapNotNull null
        }
        for (index in at - 2 downTo 0) {
            if (index + 1 in targets) return@mapNotNull null
            val wrap = code[index].methodReference() ?: continue
            if (wrap.name != "<init>" || !code[index].isDirectCall() || wrap.parameterTypes.map(CharSequence::toString) != listOf(unitType)) continue
            val unit = code[index].argumentRegisters().getOrNull(1) ?: return@mapNotNull null
            return@mapNotNull if ((index + 1 until at).none { code[it].writesRegister(unit) }) at to unit else null
        }
        null
    }

private fun Instruction.isDirectCall() = opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE

/** Whether this instruction writes [register], a wide write's second half included. */
private fun Instruction.writesRegister(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
