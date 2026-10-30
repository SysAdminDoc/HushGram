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
import app.morphe.patches.instagram.feed.requireOneKindField
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

/** The trace marker of the converter that makes each Reels item (a clips item) from the server's. */
internal const val TO_CLIPS_ITEM = "android_purge_26_q2_ClipsItemExt_toClipsItemCombined"

/**
 * The trace marker of the method that makes the page of people to follow, which the Reels friends
 * tab shows when it has no reels ("Instagram is better with friends", #167), into a clips item of
 * its own. That item never goes through the converter.
 */
internal const val TO_FRIENDS_TAB_PAGE = "android_purge_26_q2_ClipsItemExt_toFriendLaneEmptyStateClipsItem"

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

private const val STRING = "Ljava/lang/String;"

/**
 * Leaves the cards of accounts to follow out of Reels. In the default selection with its switch
 * off: suggestions are one of Instagram's features, so leaving them out is the user's pick. Asked
 * for in #15 and #20.
 */
@Suppress("unused")
val hideReelsSuggestionsPatch = bytecodePatch(
    name = "Hide suggested accounts in Reels",
    description = "Leaves out the cards of accounts to follow that Instagram puts between reels. Every reel still " +
        "plays. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
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

/** The converter that makes a clips item from the server's Reels item, by its trace marker. */
internal object ClipsItemConverterFingerprint : Fingerprint(
    filters = listOf(string(TO_CLIPS_ITEM)),
    custom = { method, _ -> method.holdsString(TO_CLIPS_ITEM) },
)

/** The method that makes the friends tab's page of people to follow into a clips item, by its trace marker. */
internal object FriendsTabPageFingerprint : Fingerprint(
    filters = listOf(string(TO_FRIENDS_TAB_PAGE)),
    custom = { method, _ -> method.holdsString(TO_FRIENDS_TAB_PAGE) },
)

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
 * Finds the converter by [TO_CLIPS_ITEM] and checks that its clips item names [SUGGESTED_KINDS] in
 * one enum field, the one the extension reads. The netego unit's type is the one String field the
 * binder dispatch reads off another class, and that class is the netego unit: the converter has to
 * return, once, the item a call taking the unit makes, with the unit still in its register and
 * nothing jumping to the return. The friends tab's page is made by the one method holding
 * [TO_FRIENDS_TAB_PAGE], which has to answer the same item type and return it somewhere. Fails
 * before anything changes when any of it isn't there exactly once, since that's an update this
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelsSuggestions(): ReelsSuggestionSites {
    val converter = uniqueMethod(PATCH, "Reels item converter holding \"$TO_CLIPS_ITEM\"", ClipsItemConverterFingerprint)
    val where = "${converter.definingClass}->${converter.name}"
    val itemType = converter.returnType
    if (!itemType.startsWith("L") || classDefByOrNull(itemType) == null) refuse("$where answers $itemType, which isn't a class of the app")
    requireOneKindField(PATCH, itemType, SUGGESTED_KINDS)

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
    val netegoReturn = returns.singleOrNull()
        ?: refuse("expected $where to return one item made from a $unitType, found ${returns.size}")
    val item = (code[netegoReturn] as OneRegisterInstruction).registerA
    // The unit is the call's first argument only in a static call; an instance call's first is its receiver.
    val call = code[netegoReturn - 2].opcode
    if (call != Opcode.INVOKE_STATIC && call != Opcode.INVOKE_STATIC_RANGE) {
        refuse("$where makes its netego item with ${call.name}, not a static call")
    }
    val unit = code[netegoReturn - 2].argumentRegisters().firstOrNull() ?: refuse("$where makes its netego item with no arguments")
    if (unit == item) refuse("$where writes the netego item over its unit before returning it")
    if (netegoReturn in converter.jumpTargets()) refuse("something in $where jumps to the return of its netego item")
    // Two locals the netego hook may borrow, checked now so a method short of them changes nothing.
    converter.freeLocalsAt(PATCH, netegoReturn, 2)

    val page = uniqueMethod(PATCH, "Reels friends tab page maker holding \"$TO_FRIENDS_TAB_PAGE\"", FriendsTabPageFingerprint)
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

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
