/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** A kept type only the feed item holds a field of: the row of suggested reels. */
internal const val CLIPS_NETEGO = "Lcom/instagram/api/schemas/ClipsNetego;"

/**
 * The parser that reads one home feed item from Instagram's JSON: a post or ad ("media_or_ad"), a
 * row of suggested reels ("clips_netego") or one of the other units. Its serializer holds the same
 * two keys but returns nothing; the parser answers the item as an Object.
 */
internal object FeedItemParserFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
    strings = listOf("media_or_ad", "clips_netego"),
)

/**
 * Passes each feed item Instagram's static parse helper answers through [filter], a static method
 * of the extension taking and answering an Object, which answers null for an item to drop. Every
 * caller of the helper on 449 skips a null item, the way it skips one that didn't parse. A second
 * patch passing the items through its own filter goes after whichever applied first, and each
 * filter answers null for null, so the order doesn't change what comes back.
 *
 * [kinds] are the item kinds [filter] drops, which one of the item's enum types has to name; see
 * [requireOneKindField]. A filter that drops every item, whatever its kind, passes none.
 */
internal fun BytecodePatchContext.filterParsedFeedItems(patch: String, filter: String, kinds: List<String>) {
    val (itemType, helper) = findFeedItemHelper(patch, kinds)
    val returns = helper.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$patch: ${helper.definingClass}->${helper.name} returns no object")
    returns.asReversed().forEach { (index, register) ->
        // At the return's own label, so a branch straight to the return passes through the filter.
        helper.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$register }, $filter
                move-result-object v$register
                check-cast v$register, $itemType
            """,
        )
    }
}

/**
 * The feed item's type and its static helper parsing one from JSON, which every feed reading items
 * goes through: Home's, Explore's chain of posts, the shop and ad feeds and more. Found from the
 * feed item parser, the one class it makes with a [CLIPS_NETEGO] field. [kinds], when there are
 * any, have to be named by one of the item's enum types; see [requireOneKindField].
 */
internal fun BytecodePatchContext.findFeedItemHelper(patch: String, kinds: List<String>): Pair<String, MutableMethod> {
    val parser = uniqueMethod(patch, "feed item parser", FeedItemParserFingerprint)
    val itemTypes = parser.implementation!!.instructions
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .distinct()
        .filter { type -> classDefByOrNull(type)?.fields?.any { it.type == CLIPS_NETEGO } == true }
    val itemType = itemTypes.singleOrNull() ?: throw PatchException(
        "$patch: expected the feed item parser to make one class with a $CLIPS_NETEGO field, found $itemTypes",
    )
    if (kinds.isNotEmpty()) requireOneKindField(patch, itemType, kinds)

    val item = mutableClassDefBy(itemType)
    val helpers = item.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == itemType && method.parameterTypes.size == 1 &&
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "parseFromJsonParser"
            } == true
    }
    val helper = helpers.singleOrNull() ?: throw PatchException(
        "$patch: expected one static method of $itemType that parses one from JSON, found ${helpers.size}",
    )
    return itemType to helper
}

/**
 * The extension finds an item's kind by reading each of its enum fields and comparing the
 * constant's name with [kinds]. That holds only while one of the item's enum types names them all,
 * and none of the others names any; fail here when an update changes it.
 */
internal fun BytecodePatchContext.requireOneKindField(patch: String, itemType: String, kinds: List<String>) {
    val enumFields = classDefBy(itemType).fields
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
        .mapNotNull { field -> classDefByOrNull(field.type)?.takeIf { it.superclass == "Ljava/lang/Enum;" } }
    val naming = enumFields.map { enum ->
        val names = enum.methods.filter { it.name == "<clinit>" }.flatMap { method ->
            method.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.orEmpty()
        }.toSet()
        enum.type to kinds.filter { it in names }
    }
    val holders = naming.filter { it.second.isNotEmpty() }
    if (holders.size != 1 || holders.single().second != kinds) {
        throw PatchException(
            "$patch: expected one enum field of $itemType whose type names all of $kinds, found $naming",
        )
    }
}
