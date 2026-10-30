/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested posts"

/**
 * The binder of the header Instagram draws after Home's end of feed card: the active section's name
 * ("Suggested for you") on the left and a link to another section ("Older posts") on the right. Its
 * name is kept. Its model is the feed's group set, which Instagram takes out of the end of feed
 * card's own data when it reads a page and puts after the card with the active section's items.
 */
internal const val GROUP_SET_BINDER = "Lcom/instagram/mainfeed/unconnectedcontent/GroupSetBinderGroup;"

internal const val SECTION_TITLE = "$FEED_SUGGESTIONS->sectionTitle(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"

/** The extension's stub the patch fills to read a section's items. */
internal const val SECTION_ITEMS_STUB = "sectionItems"

private const val SET_TEXT = "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V"
private const val ITERATOR_NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private val BIND_VIEW = listOf("I", "Landroid/view/View;", "Ljava/lang/Object;", "Ljava/lang/Object;")

/**
 * Where the header's binder sets its title, found: the index of that setText, the registers holding
 * the title and the section it was read from, the section's class and its list of items, and the
 * extension's stub. [write] fills the stub to read that list and passes the title through
 * FeedSuggestions.sectionTitle right before it's set, so a section with nothing left in it gets no
 * title over nothing. The link to Older posts is set before, from another section, and stays.
 */
internal class SuggestedSectionHeader(
    val titleAt: Int,
    val text: Int,
    val section: Int,
    val sectionType: String,
    val items: FieldReference,
    private val stub: MutableMethod,
) {
    /** Whether it hooked the title, and so filled the items stub. */
    fun write(context: BytecodePatchContext): Boolean {
        val bind = context.mutableClassDefBy(GROUP_SET_BINDER).methods.single { it.isBindView() }
        // Found before the patch's other steps wrote anything: if one moved the title's setText, leave the header be.
        val now = bind.implementation?.instructions?.toList()?.takeIf { titleAt < it.size }?.let { titleSetAt(it, titleAt) }
        if (now == null || now.text != text || now.section != section) {
            patchLog.warning("$PATCH: the Suggested for you header's title moved before it was hooked. " +
                "The header keeps its title when nothing is left under it.")
            return false
        }
        stub.addInstructions(
            0,
            """
                check-cast p0, $sectionType
                iget-object p0, p0, ${items.definingClass}->${items.name}:${items.type}
                return-object p0
            """,
        )
        // Right after the title is read, so only the path that read it from the section passes the hook.
        bind.addInstructions(
            titleAt,
            """
                invoke-static { v$text, v$section }, $SECTION_TITLE
                move-result-object v$text
            """,
        )
        return true
    }
}

/**
 * Finds where the header's binder sets its title. Its bindView sets two texts, each right after
 * reading a section's name: the link's, from the section it looks up by id, and the title's, from
 * the section it finds by walking the group set's sections, whose register comes straight from the
 * iterator's next() and a cast to the section class. That cast picks the title. The section class
 * has to be public with one public list field, its items. On 450's 385611438 that's bindView's
 * setText at 54, the title in v0 read from LX/09QK.A05 in v2, and the items LX/09QK.A06.
 * Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findSuggestedSectionHeader(): SuggestedSectionHeader {
    val binder = classDefByOrNull(GROUP_SET_BINDER) ?: refuse("$GROUP_SET_BINDER isn't in this build")
    val binds = binder.methods.filter { it.isBindView() }
    val bind = binds.singleOrNull() ?: refuse("expected one bindView(int, View, Object, Object) in $GROUP_SET_BINDER, found ${binds.size}")
    val code = bind.implementation?.instructions?.toList() ?: refuse("$GROUP_SET_BINDER->bindView has no code")

    val titles = code.indices.filter { code[it].calls(SET_TEXT) }.mapNotNull { at -> titleSetAt(code, at) }
    val title = titles.singleOrNull()
        ?: refuse("expected bindView to set one title from a section it walks to, found ${titles.size}")
    val (at, text, section, name) = title
    if (text > 15 || section > 15) refuse("bindView keeps its title or section past v15")

    val sectionClass = classDefByOrNull(name.definingClass) ?: refuse("${name.definingClass} isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(sectionClass.accessFlags)) refuse("${sectionClass.type} isn't public, so the extension can't reach it")
    val lists = sectionClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LIST }
    val items = lists.singleOrNull() ?: refuse("expected one list field in ${sectionClass.type}, found ${lists.size}")
    if (!AccessFlags.PUBLIC.isSet(items.accessFlags)) refuse("${sectionClass.type}'s list field ${items.name} isn't public")

    val stub = staticStub(FEED_SUGGESTIONS, SECTION_ITEMS_STUB, LIST)
    return SuggestedSectionHeader(at, text, section, sectionClass.type, items, stub)
}

/** [findSuggestedSectionHeader], or null after the patch log says why, and the header keeps its title. */
internal fun BytecodePatchContext.suggestedSectionHeaderOrWarn(): SuggestedSectionHeader? = try {
    findSuggestedSectionHeader()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The Suggested for you header keeps its title when nothing is left under it.")
    null
}

/** One title set: the setText's index, the text's register, the section's register and the name field read. */
private data class TitleSet(val at: Int, val text: Int, val section: Int, val name: FieldReference)

/**
 * The title set at [at] when it's one: setText(view, text) right after text = section.<String field>,
 * with the section's register last written by a cast to the field's class, right after it came out of
 * an iterator's next(). Null for any other setText, the link's among them.
 */
private fun titleSetAt(code: List<Instruction>, at: Int): TitleSet? {
    val set = code[at] as? FiveRegisterInstruction ?: return null
    if (code[at].opcode != Opcode.INVOKE_VIRTUAL || set.registerCount != 2 || at < 1) return null
    val read = code[at - 1]
    val name = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return null
    if (read.opcode != Opcode.IGET_OBJECT || name.type != STRING || (read as TwoRegisterInstruction).registerA != set.registerD) return null
    val section = read.registerB
    val castAt = (at - 2 downTo 0).firstOrNull { code[it].writes(section) } ?: return null
    val cast = code[castAt]
    if (cast.opcode != Opcode.CHECK_CAST || ((cast as ReferenceInstruction).reference as TypeReference).type != name.definingClass) return null
    val result = code.getOrNull(castAt - 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA != section) return null
    if (code.getOrNull(castAt - 2)?.calls(ITERATOR_NEXT) != true) return null
    return TitleSet(at, set.registerD, section, name)
}

private fun Method.isBindView(): Boolean =
    name == "bindView" && returnType == "V" && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == BIND_VIEW

/** Whether this instruction writes [register], as the low or the high half of a wide value too. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun Instruction.calls(reference: String) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the Suggested for you header: $detail")
