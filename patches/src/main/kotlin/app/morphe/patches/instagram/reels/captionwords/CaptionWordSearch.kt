/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.captionwords

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.explore.findExploreParser
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide reels by caption words"

internal const val CAPTION_WORD_SEARCH = "$EXTENSION_PACKAGE/reels/CaptionWordSearch;"
internal const val SEARCH_SECTIONS = "$CAPTION_WORD_SEARCH->sections(Ljava/util/List;)V"

/** Keys only the search results grid's parser reads together: its sections, and its paging. */
internal const val RESULT_SECTIONS = "sections"
internal const val RESULT_MORE_REELS = "has_more_reels"
internal const val RESULT_RANK_TOKEN = "rank_token"

private const val LIST = "Ljava/util/List;"

/**
 * A page parser whose page holds the grid's sections: the parser's class and its unsafeParseFromJson
 * parameters, the register holding the page at its one return, that return's index, and the
 * page's section list field.
 */
internal class SectionPage(
    val name: String,
    val type: String,
    val parameters: List<String>,
    val register: Int,
    val returnAt: Int,
    val sections: FieldReference,
)

/**
 * The Explore page's parser, the one Hide the Explore grid empties, and the search results grid's
 * (#807). Both fill their sections through the same section reader, so a section is one row of the
 * grid however the page came. Either one that can't be told is left out with a warning, and the rest
 * of the patch goes in: Also in search then has fewer grids to take rows from.
 */
internal fun BytecodePatchContext.findSearchSectionPages(): List<SectionPage> {
    val extension = classDefByOrNull(CAPTION_WORD_SEARCH)
    val hook = extension?.methods?.any {
        it.name == "sections" && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(LIST)
    } == true
    if (!hook) {
        patchLog.warning("$PATCH: the extension has no public static $SEARCH_SECTIONS. Also in search does nothing.")
        return emptyList()
    }
    val pages = mutableListOf<SectionPage>()
    try {
        val explore = findExploreParser()
        pages += SectionPage("Explore", explore.type, explore.parameters, explore.register, explore.returnAt, explore.sections)
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. Also in search leaves the Explore grid alone.")
    }
    try {
        pages += findSearchResultsPage()
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. Also in search leaves search results alone.")
    }
    return pages
}

/**
 * The search results grid's parser: the one unsafeParseFromJson loading [RESULT_SECTIONS],
 * [RESULT_MORE_REELS] and [RESULT_RANK_TOKEN] (0P4J on 456's 386214574), the list field it reads
 * the sections into, and the one return handing back the page that field is on. Refuses when there
 * isn't exactly one, or it reads them some other way.
 */
internal fun BytecodePatchContext.findSearchResultsPage(): SectionPage {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
    val found = classesLoadingString(RESULT_MORE_REELS).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == "unsafeParseFromJson" && method.implementation != null &&
                listOf(RESULT_SECTIONS, RESULT_MORE_REELS, RESULT_RANK_TOKEN).all { loadsString(method, it) }
        }.map { classDef.type to it }
    }
    val (type, method) = found.singleOrNull()
        ?: refuse("expected one search results parser holding $RESULT_SECTIONS, $RESULT_MORE_REELS and $RESULT_RANK_TOKEN, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val where = "$type->${method.name}"

    val key = code.indices.singleOrNull { stringLoadedAt(code, it) == RESULT_SECTIONS } ?: refuse("$where reads $RESULT_SECTIONS more than once")
    val next = code.indices.firstOrNull { it > key && stringLoadedAt(code, it) != null } ?: code.size
    val write = (key until next).singleOrNull {
        code[it].opcode == Opcode.IPUT_OBJECT && ((code[it] as ReferenceInstruction).reference as FieldReference).type == LIST
    } ?: refuse("$where doesn't read $RESULT_SECTIONS into one list field")
    val sections = (code[write] as ReferenceInstruction).reference as FieldReference
    val page = sections.definingClass

    val made = code.indices.singleOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && ((code[it] as ReferenceInstruction).reference as TypeReference).type == page
    } ?: refuse("$where doesn't make one $page")
    val register = (code[made] as OneRegisterInstruction).registerA
    if ((code[write] as TwoRegisterInstruction).registerB != register) refuse("$where reads $RESULT_SECTIONS onto something other than its page")
    val returns = code.indices.filter {
        code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == register
    }
    val returnAt = returns.singleOrNull() ?: refuse("$where hands its page back ${returns.size} times, expected once")
    if (register > 15) refuse("$where keeps its page in v$register, out of reach of a field read")
    return SectionPage("search results", type, method.parameterTypes.map(Any::toString), register, returnAt, sections)
}

/**
 * At the page's return, hands the page's section list to [SEARCH_SECTIONS], which takes the rows
 * holding a blocked post out of it in place. The hook sits on the return's own label, since the
 * parsers' loops branch straight to it, and Hide the Explore grid's hook there runs before or after
 * this one in either patch order.
 */
internal fun BytecodePatchContext.filterSearchSections(page: SectionPage) {
    val method = mutableClassDefBy(page.type).methods.single {
        it.name == "unsafeParseFromJson" && it.parameterTypes.map(Any::toString) == page.parameters
    }
    val temp = (0..15).first { it != page.register }
    val field = "${page.sections.definingClass}->${page.sections.name}:${page.sections.type}"
    method.addInstructionsAtControlFlowLabel(
        page.returnAt,
        """
            iget-object v$temp, v${page.register}, $field
            invoke-static { v$temp }, $SEARCH_SECTIONS
        """,
    )
}
