/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.tray

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.requireOneKindField
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested stories"
internal const val HIDE_TRAY = "$EXTENSION_PACKAGE/stories/StoriesTray;->hideTray()Z"
internal const val TRAY_FILTER = "$EXTENSION_PACKAGE/stories/StoriesTray;->filter(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val TRAY_REMAINING_FILTER = "$EXTENSION_PACKAGE/stories/StoriesTray;->remaining(Ljava/util/ArrayList;)Ljava/util/ArrayList;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

/** The trace name only the method adding Home's story tray row holds. */
internal const val TRAY_ROWS = "MainFeedStoryTrayBinderGroup.buildRowViewTypes"

/** Keys only the stories tray response's parser holds together; [TRAY_ITEMS] is the list of tray items. */
internal const val TRAY_ITEMS = "tray"
internal const val TRAY_TOKEN = "story_ranking_token"
internal const val TRAY_REMAINING = "remaining_reel_ids_to_fetch"

/** The kept interface every stories tray item implements. */
internal const val TRAY_ITEM_INTF = "Lcom/instagram/model/reels/ReelResponseItemIntf;"

/** The reel types of suggested tray items, which StoriesTray drops: an account to follow, its story, and a creator's story. */
internal val SUGGESTED_REELS = listOf("SUGGESTED_USER", "SUGGESTED_USER_REEL", "SUGGESTED_CREATOR_REEL")

/**
 * The reel types of the cards Instagram makes for the tray, which StoriesTray drops on their own
 * switches: a rewind, then memories, your week, the year in review, follow anniversaries and birthdays.
 */
internal val MADE_REELS = listOf(
    "HIGHLIGHT_REWIND_REEL", "MEMORY_REEL", "MY_WEEK_REEL", "END_OF_YEAR", "FOLLOW_VERSARIES", "BIRTHDAY_HIGHLIGHTS",
)

@Suppress("unused")
val hideSuggestedStoriesPatch = bytecodePatch(
    name = "Hide suggested stories",
    description = "Removes stories from accounts you don't follow from the row at the top of Home. More switches " +
        "hide rewinds and memories, or the whole row. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storiesTray")
        guardTrayRow(findTrayRowBuild())
        val parse = findTrayItemParse()
        hookTrayParser(parse, findTrayRemaining(parse.site))
        enableStatus("storiesTray")
    }
}

/** A method by its class, name and parameter types. */
internal class MethodSite(val type: String, val name: String, val parameters: List<String>)

/**
 * Finds the one method holding [TRAY_ROWS]: the story tray binder group's row build, which adds
 * the tray's row to Home's list through the row builder it takes first. Fails when there isn't
 * exactly one, or it adds no row that way, since that's an update the patch hasn't seen.
 */
internal fun BytecodePatchContext.findTrayRowBuild(): MethodSite {
    val found = mutableListOf<Pair<String, Method>>()
    val holders = classesHolding(TRAY_ROWS).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method -> if (TRAY_ROWS in method.strings()) found += classDef.type to method }
    }
    val (type, method) = found.singleOrNull() ?: refuse("expected one method holding $TRAY_ROWS, found ${found.size}")
    val where = "$type->${method.name}"
    val builder = method.parameterTypes.firstOrNull()?.toString()
    if (method.returnType != "V" || builder == null) refuse("$where isn't a row build taking a row builder")
    val adds = method.implementation!!.instructions.count {
        it.opcode == Opcode.INVOKE_INTERFACE && it.methodReference()?.let { call ->
            call.definingClass == builder && call.parameterTypes.map(CharSequence::toString) == listOf("I") && call.returnType == "V"
        } == true
    }
    if (adds == 0) refuse("$where adds no row through $builder")
    val parameterRegisters = method.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + 1
    if (method.implementation!!.registerCount <= parameterRegisters) refuse("$where has no register of its own")
    return MethodSite(type, method.name, method.parameterTypes.map(CharSequence::toString))
}

/** Asks [HIDE_TRAY] first thing in the row build, and on a yes returns before any row is added. */
internal fun BytecodePatchContext.guardTrayRow(site: MethodSite) {
    mutableMethod(site).addInstructions(
        0,
        """
            invoke-static { }, $HIDE_TRAY
            move-result v0
            if-eqz v0, :show
            return-void
            :show
            nop
        """,
    )
}

/** Where the tray parser reads one item: the parser method, and the index and register of the item's move-result. */
internal class TrayItemParse(val site: MethodSite, val moveResult: Int, val register: Int)

/**
 * Finds the one `unsafeParseFromJson` holding [TRAY_ITEMS], [TRAY_TOKEN] and [TRAY_REMAINING], and in
 * it the one read of an item between the [TRAY_ITEMS] key and the next key: a `parseFromJsonParser`
 * call on a parser whose items implement [TRAY_ITEM_INTF], its move-result-object, and a null test of
 * it, so a null item is skipped. Checks the item's reel type is an enum field the extension can read,
 * naming every kind a switch takes out.
 */
internal fun BytecodePatchContext.findTrayItemParse(): TrayItemParse {
    val found = mutableListOf<Pair<String, Method>>()
    val holders = classesHolding(TRAY_ITEMS, TRAY_TOKEN, TRAY_REMAINING).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.name != "unsafeParseFromJson") return@forEach
            val strings = method.strings()
            if (TRAY_ITEMS in strings && TRAY_TOKEN in strings && TRAY_REMAINING in strings) found += classDef.type to method
        }
    }
    val (type, method) = found.singleOrNull()
        ?: refuse("expected one stories tray parser holding $TRAY_ITEMS, $TRAY_TOKEN and $TRAY_REMAINING, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val where = "$type->${method.name}"
    val key = code.indexOfFirst { it.string() == TRAY_ITEMS }
    val nextKey = (key + 1 until code.size).firstOrNull { code[it].string() != null } ?: code.size
    val reads = (key + 1 until nextKey).filter { code[it].methodReference()?.name == "parseFromJsonParser" }
    val read = reads.singleOrNull() ?: refuse("$where reads its tray items ${reads.size} times, expected once")
    val result = code.getOrNull(read + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the tray item it reads")
    val register = (result as OneRegisterInstruction).registerA
    val test = code.getOrNull(read + 2)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't skip a tray item that didn't parse")
    }

    val parser = (read - 3 until read).reversed().firstNotNullOfOrNull { at ->
        code.getOrNull(at)?.takeIf { it.opcode == Opcode.SGET_OBJECT }?.let { ((it as ReferenceInstruction).reference as FieldReference).type }
    } ?: refuse("$where reads its tray items with no parser before the call")
    val casts = classDefBy(parser).methods.filter { it.name == "parseFromJsonParser" }.flatMap { m ->
        m.implementation?.instructions?.filter { it.opcode == Opcode.CHECK_CAST }
            ?.map { ((it as ReferenceInstruction).reference as TypeReference).type }.orEmpty()
    }.distinct()
    val item = casts.singleOrNull { classDefByOrNull(it)?.interfaces?.contains(TRAY_ITEM_INTF) == true }
        ?: refuse("expected $parser to read one class implementing $TRAY_ITEM_INTF, found $casts")
    requireOneKindField(PATCH, item, SUGGESTED_REELS + MADE_REELS)
    return TrayItemParse(MethodSite(type, method.name, method.parameterTypes.map(CharSequence::toString)), read + 1, register)
}

/** Where the tray parser reads the ids of the reels it fetches after the tray: the index and register of that read's move-result. */
internal class TrayRemainingRead(val site: MethodSite, val moveResult: Int, val register: Int)

/**
 * In the tray parser at [site], the one read of [TRAY_REMAINING]'s value between that key and the
 * next: a call answering an ArrayList, and its move-result-object. Fails when there isn't exactly
 * one, since then Stop loading stories would leave those reels to load.
 */
internal fun BytecodePatchContext.findTrayRemaining(site: MethodSite): TrayRemainingRead {
    val method = classDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val code = method.implementation!!.instructions.toList()
    val where = "${site.type}->${site.name}"
    val key = code.indexOfFirst { it.string() == TRAY_REMAINING }
    if (key < 0) refuse("$where doesn't hold $TRAY_REMAINING")
    val nextKey = (key + 1 until code.size).firstOrNull { code[it].string() != null } ?: code.size
    val reads = (key + 1 until nextKey).filter { code[it].methodReference()?.returnType == ARRAY_LIST }
    val read = reads.singleOrNull() ?: refuse("$where reads $TRAY_REMAINING ${reads.size} times, expected once")
    val result = code.getOrNull(read + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the reel ids it reads")
    return TrayRemainingRead(site, read + 1, (result as OneRegisterInstruction).registerA)
}

/** Passes the ids of the reels left to fetch through [TRAY_REMAINING_FILTER] right after the parser reads them. */
internal fun BytecodePatchContext.trimTrayRemaining(read: TrayRemainingRead) {
    val ids = read.register
    mutableMethod(read.site).addInstructions(
        read.moveResult + 1,
        """
            invoke-static/range { v$ids .. v$ids }, $TRAY_REMAINING_FILTER
            move-result-object v$ids
        """,
    )
}

/** Both tray parser hooks, the later one first, so writing one doesn't move where the other goes. */
internal fun BytecodePatchContext.hookTrayParser(parse: TrayItemParse, remaining: TrayRemainingRead) {
    if (remaining.moveResult > parse.moveResult) {
        trimTrayRemaining(remaining)
        filterTrayItems(parse)
    } else {
        filterTrayItems(parse)
        trimTrayRemaining(remaining)
    }
}

/** Passes each tray item through [TRAY_FILTER] right after the parser reads it, ahead of its null test. */
internal fun BytecodePatchContext.filterTrayItems(parse: TrayItemParse) {
    val item = parse.register
    mutableMethod(parse.site).addInstructions(
        parse.moveResult + 1,
        """
            invoke-static/range { v$item .. v$item }, $TRAY_FILTER
            move-result-object v$item
        """,
    )
}

private fun BytecodePatchContext.mutableMethod(site: MethodSite) = mutableClassDefBy(site.type).methods.single {
    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
}

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { it.string() }?.toSet() ?: emptySet()

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
