/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Hide the Explore grid"
internal const val HIDE_GRID = "$EXTENSION_PACKAGE/explore/ExploreGrid;->hide(Ljava/util/List;)Z"
internal const val TRACK_STATE = "$EXTENSION_PACKAGE/explore/ExploreGrid;->track(Ljava/lang/Object;)V"
internal const val LOAD_MORE_ROW = "$EXTENSION_PACKAGE/explore/ExploreGrid;->loadMoreRow(Ljava/lang/Object;I)I"

/** The load more row's view and the method that picks what it draws, both kept names. */
internal const val LOAD_MORE_BUTTON = "Lcom/instagram/ui/widget/loadmore/LoadMoreButton;"
internal const val SET_VIEW_TYPE = "setViewType"

/** A log tag one of the Explore fragment's own methods holds. */
internal const val EXPLORE_FRAGMENT_TAG = "ExploreFragment.setupAutoplay"

/** The keys of the topical Explore page the parser reads: its sections, and whether there's more. */
internal const val SECTIONS = "sectional_items"
internal const val MORE_AVAILABLE = "more_available"
internal const val AUTO_LOAD_MORE = "auto_load_more_enabled"

/** A string only the topical Explore page's parser holds beside [SECTIONS]. */
internal const val PAGING_TOKEN = "session_paging_token"

private val FIELD_WRITES = setOf(
    Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
)

/**
 * Empties the Search tab's Explore grid. In the default selection with its switch off: Explore is
 * one of Instagram's main screens, so taking it away is the user's pick.
 */
@Suppress("unused")
val hideExploreGridPatch = bytecodePatch(
    name = "Hide the Explore grid",
    description = "Empties the grid of posts and reels under the Search tab's bar. Search, your recent searches and " +
        "search results stay. Off until you turn it on. Switch: HushGram settings > Explore.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("exploreGrid")
        emptyExplorePages(findExploreParser())
        val row = findLoadMoreRow()
        hideLoadMoreRow(row, findExploreAdapter(findExploreFragment(), row))
        enableStatus("exploreGrid")
    }
}

/**
 * The topical Explore page's JSON parser: the class and method, the page's type and register, the
 * index of the return that hands the page back, and the page fields the hook writes.
 */
internal class ExploreParser(
    val type: String,
    val parameters: List<String>,
    val page: String,
    val register: Int,
    val returnAt: Int,
    val sections: FieldReference,
    val moreAvailable: FieldReference,
    val autoLoadMore: FieldReference,
)

/**
 * Finds the one `unsafeParseFromJson` holding [SECTIONS] and [PAGING_TOKEN], and in it the fields
 * the page's sections and its two "more" flags are read into. Fails when there isn't exactly one
 * such parser, or it reads them some other way, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findExploreParser(): ExploreParser {
    val found = mutableListOf<Pair<String, Method>>()
    classesHolding(SECTIONS, PAGING_TOKEN).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.name != "unsafeParseFromJson") return@forEach
            val strings = method.strings()
            if (SECTIONS in strings && PAGING_TOKEN in strings) found += classDef.type to method
        }
    }
    val (type, method) = found.singleOrNull()
        ?: refuse("expected one Explore page parser holding $SECTIONS and $PAGING_TOKEN, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val where = "$type->${method.name}"

    // A key is loaded by const-string or asked of a Redex string pool, which differs by build:
    // 450's 385611395 pools auto_load_more_enabled and the others load it themselves (#77).
    fun fieldAfter(key: String, opcode: Opcode, fieldType: String): Pair<FieldReference, Int> {
        val at = code.indices.firstOrNull { stringLoadedAt(code, it) == key } ?: refuse("$where doesn't read $key")
        val write = code.drop(at + 1).firstOrNull { it.opcode in FIELD_WRITES }
            ?: refuse("$where reads $key into nothing")
        val field = (write as ReferenceInstruction).reference as FieldReference
        if (write.opcode != opcode || field.type != fieldType) {
            refuse("$where reads $key into ${field.definingClass}->${field.name}:${field.type}, not a $fieldType")
        }
        return field to (write as TwoRegisterInstruction).registerB
    }

    val (sections, register) = fieldAfter(SECTIONS, Opcode.IPUT_OBJECT, "Ljava/util/List;")
    val page = sections.definingClass
    val (moreAvailable, moreOn) = fieldAfter(MORE_AVAILABLE, Opcode.IPUT_BOOLEAN, "Z")
    val (autoLoadMore, autoOn) = fieldAfter(AUTO_LOAD_MORE, Opcode.IPUT_BOOLEAN, "Z")
    for ((field, on) in listOf(moreAvailable to moreOn, autoLoadMore to autoOn)) {
        if (field.definingClass != page || on != register) refuse("$where writes ${field.name} on something other than its page")
    }
    val returns = code.withIndex().filter {
        it.value.opcode == Opcode.RETURN_OBJECT && (it.value as OneRegisterInstruction).registerA == register
    }
    val returnAt = returns.singleOrNull()?.index ?: refuse("$where hands its page back ${returns.size} times, expected once")
    if (register > 15) refuse("$where keeps its page in v$register, out of reach of a field write")
    return ExploreParser(
        type, method.parameterTypes.map(CharSequence::toString), page, register, returnAt,
        sections, moreAvailable, autoLoadMore,
    )
}

/**
 * At the parser's return, asks [HIDE_GRID] with the page's sections, and on a yes gives the page an
 * empty section list and turns off its "more" flags, so nothing loads more on its own. The cursor
 * stays: without one Instagram leaves Explore on its loading placeholder for good. With it the
 * empty page would show a load more row, which [hideLoadMoreRow] hides. The hook sits on the
 * return's own label, since the parser's loop branches straight to it.
 */
internal fun BytecodePatchContext.emptyExplorePages(parser: ExploreParser) {
    val method = mutableClassDefBy(parser.type).methods.single {
        it.name == "unsafeParseFromJson" && it.parameterTypes.map(CharSequence::toString) == parser.parameters
    }
    val page = parser.register
    val temp = (0..15).first { it != page }
    fun ref(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"
    method.addInstructionsAtControlFlowLabel(
        parser.returnAt,
        """
            iget-object v$temp, v$page, ${ref(parser.sections)}
            invoke-static { v$temp }, $HIDE_GRID
            move-result v$temp
            if-eqz v$temp, :keep
            new-instance v$temp, Ljava/util/ArrayList;
            invoke-direct { v$temp }, Ljava/util/ArrayList;-><init>()V
            iput-object v$temp, v$page, ${ref(parser.sections)}
            const/4 v$temp, 0x0
            iput-boolean v$temp, v$page, ${ref(parser.moreAvailable)}
            iput-boolean v$temp, v$page, ${ref(parser.autoLoadMore)}
            :keep
            nop
        """,
    )
}

/**
 * The load more row's state interface, and its check: the static on the row's state picker that
 * answers whether a list shows the row at all. [LOAD_MORE_BUTTON]'s [SET_VIEW_TYPE] takes the state
 * first and hands it to the picker class's state static. The check shows the row on a list with no
 * items whatever else the state says, which is the "+" left on an emptied Explore page.
 */
internal class LoadMoreRow(val state: String, val check: MethodReference)

internal fun BytecodePatchContext.findLoadMoreRow(): LoadMoreRow {
    val setViewType = classDefByOrNull(LOAD_MORE_BUTTON)?.methods?.singleOrNull {
        it.name == SET_VIEW_TYPE && it.parameterTypes.size == 2
    } ?: refuse("$LOAD_MORE_BUTTON has no two-argument $SET_VIEW_TYPE")
    val state = setViewType.parameterTypes.first().toString()
    val pickers = setViewType.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_STATIC }
        .map { (it as ReferenceInstruction).reference as MethodReference }
        .filter { it.returnType == "Ljava/lang/Integer;" && it.parameterTypes.map(CharSequence::toString) == listOf(state) }
    val picker = pickers.singleOrNull()?.definingClass
        ?: refuse("$SET_VIEW_TYPE picks its state through ${pickers.size} statics, expected one")
    val checks = classDefBy(picker).methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(state)
    }
    val check = checks.singleOrNull() ?: refuse("$picker has ${checks.size} boolean statics taking $state, expected one")
    val asks = check.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_INTERFACE }
        .map { ((it as ReferenceInstruction).reference as MethodReference).name }
    if ("isLoading" !in asks) refuse("$picker->${check.name} doesn't ask isLoading")
    return LoadMoreRow(state, ImmutableMethodReference(picker, check.name, check.parameterTypes, check.returnType))
}

/** The Explore fragment: the one class with a method holding [EXPLORE_FRAGMENT_TAG]. */
internal fun BytecodePatchContext.findExploreFragment(): String {
    val found = mutableSetOf<String>()
    classesHolding(EXPLORE_FRAGMENT_TAG).forEach { classDef ->
        if (classDef.methods.any { EXPLORE_FRAGMENT_TAG in it.strings() }) found += classDef.type
    }
    return found.singleOrNull() ?: refuse("expected one class holding $EXPLORE_FRAGMENT_TAG, found ${found.size}")
}

/**
 * Where the Explore fragment builds its grid adapter: the one type the fragment builds with the row
 * state whose own code asks the row check, the fragment's method and the index of that constructor
 * call. The adapter's type also draws other grids (a location's page among them), so what marks
 * Explore's row is the state the fragment hands this adapter.
 */
internal class ExploreAdapter(val fragment: String, val method: String, val parameters: List<String>, val built: Int, val type: String)

internal fun BytecodePatchContext.findExploreAdapter(fragment: String, row: LoadMoreRow): ExploreAdapter {
    fun asks(type: String) = classDefByOrNull(type)?.methods?.any { method ->
        method.implementation?.instructions?.any { it.calls(row.check) } == true
    } == true
    val sites = classDefBy(fragment).methods.flatMap { method ->
        method.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
            instruction.opcode in CONSTRUCTOR_CALLS && ((instruction as ReferenceInstruction).reference as MethodReference).let {
                it.name == "<init>" && row.state in it.parameterTypes.map(CharSequence::toString) && asks(it.definingClass)
            }
        }?.map { (index, instruction) -> Triple(method, index, ((instruction as ReferenceInstruction).reference as MethodReference).definingClass) }
            .orEmpty()
    }
    val (method, index, type) = sites.singleOrNull()
        ?: refuse("$fragment builds ${sites.size} adapters that take ${row.state} and ask the row check, expected one")
    return ExploreAdapter(fragment, method.name, method.parameterTypes.map(CharSequence::toString), index, type)
}

/**
 * Hides the load more row on the emptied Explore grid and leaves every other list's row alone.
 * Right after the fragment builds its adapter, [TRACK_STATE] notes the row state it hands over.
 * When [LOAD_MORE_BUTTON] binds, it asks the row check and hides itself on a no, and that answer
 * goes through [LOAD_MORE_ROW] with the state, which says no for Explore's while the switch is on.
 * The row stays in the adapter: an Explore grid with nothing in it at all shows Instagram's loading
 * placeholder for good, and so does telling Explore there's no next page.
 */
internal fun BytecodePatchContext.hideLoadMoreRow(row: LoadMoreRow, adapter: ExploreAdapter) {
    val fragmentMethod = mutableClassDefBy(adapter.fragment).methods.single {
        it.name == adapter.method && it.parameterTypes.map(CharSequence::toString) == adapter.parameters
    }
    val construct = fragmentMethod.implementation!!.instructions.elementAt(adapter.built)
    val parameters = ((construct as ReferenceInstruction).reference as MethodReference).parameterTypes.map(CharSequence::toString)
    // After the new instance, each argument takes one register, or two for a long or a double.
    val offset = 1 + parameters.takeWhile { it != row.state }.map { if (it == "J" || it == "D") 2 else 1 }.sum()
    val state = when (construct) {
        is RegisterRangeInstruction -> construct.startRegister + offset
        is FiveRegisterInstruction ->
            listOf(construct.registerC, construct.registerD, construct.registerE, construct.registerF, construct.registerG)[offset]
        else -> refuse("${adapter.fragment}->${adapter.method} builds its adapter some other way")
    }
    fragmentMethod.addInstructions(adapter.built + 1, "invoke-static/range { v$state .. v$state }, $TRACK_STATE")

    var hooked = 0
    mutableClassDefBy(LOAD_MORE_BUTTON).methods.forEach { method ->
        val where = "$LOAD_MORE_BUTTON->${method.name}"
        val code = method.implementation?.instructions?.toList() ?: return@forEach
        // From the last site back, so an insert never moves a site still to come.
        code.indices.filter { code[it].calls(row.check) }.reversed().forEach { call ->
            val asked = when (val instruction = code[call]) {
                is RegisterRangeInstruction -> instruction.startRegister
                else -> (instruction as FiveRegisterInstruction).registerC
            }
            val result = code.getOrNull(call + 1)
            if (result?.opcode != Opcode.MOVE_RESULT) refuse("$where drops the row check's answer")
            val show = (result as OneRegisterInstruction).registerA
            if (asked > 15 || show > 15) refuse("$where keeps the state or the answer out of reach of invoke-static")
            method.addInstructions(
                call + 2,
                """
                    invoke-static { v$asked, v$show }, $LOAD_MORE_ROW
                    move-result v$show
                """,
            )
            hooked++
        }
    }
    if (hooked != 1) refuse("$LOAD_MORE_BUTTON asks the row check $hooked times, expected once")
}

private val CONSTRUCTOR_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

private fun Instruction.calls(method: MethodReference) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == method.toString()

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    ?.toSet() ?: emptySet()

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
