/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Hide suggested posts"

/**
 * Kotlin's messages for the end of feed card binder's casts of its view holder and of its card,
 * which keep the names Instagram's source gives those classes. Only that binder's bindView loads both.
 */
internal const val END_CARD_HOLDER =
    "null cannot be cast to non-null type com.instagram.mainfeed.unconnectedcontent.demarcator.EndOfFeedUnitDemarcatorHolder"
internal const val END_CARD_UNIT = "null cannot be cast to non-null type com.instagram.feed.feeditem.EndOfFeedDemarcatorUnit"

internal const val CAUGHT_UP_TITLE = "$FEED_SUGGESTIONS->caughtUpTitle(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"

/** The extension's stub the patch fills to find the section an end of feed card shows under its title. */
internal const val CAUGHT_UP_SECTION_STUB = "caughtUpSection"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private val BIND_VIEW = listOf("I", "Landroid/view/View;", OBJECT, OBJECT)

/**
 * The end of feed card's title, found: its binder, the holder's (title, subtitle) call and the fields
 * of the card's data it shows, the read of the link's text right before the holder's link call, the
 * registers holding the holder and the card there, two registers nothing reads after it, and the
 * walk from the card to the section its group set has open, as the card's own section getter walks
 * it: the card's data, the data's group set, a new sections view of that group set, and the view's
 * lookup by the open section's id.
 *
 * The binder reaches the link only when the card draws an Older posts link beside the title, and a
 * card with buttons never gets there. On 451 that title stands as the open section's header, seen on
 * a phone. The link being there is taken as the sign the title is a header, which 451 shows and no
 * other build has proven. A card without a group set reaches the link too, with the fallback section
 * its getter makes, but the stub finds no section for it, so it keeps its title.
 *
 * [write] fills the extension's stub with the walk and, right before the link's text is read, sets
 * the holder's title and subtitle again, the title through FeedSuggestions.caughtUpTitle, which
 * answers "" when the open section shows nothing. Seven of 451's nine holders behind that call hide
 * an empty title, LX/07Ks leaves a blank row and LX/0OUh ignores it. The caught up card's (LX/06ML)
 * drops it, seen on a phone. The subtitle and the link stay. The card's own text is its title, not
 * the section's name: "Suggested for you" over a section named "Suggested Posts" (#104, 2026-10-17).
 */
internal class CaughtUpCardTitle(
    val binder: String,
    val titleAt: Int,
    val titleCall: String,
    val titleField: String,
    val subtitleField: String,
    val linkAt: Int,
    val linkRead: String,
    val linkCall: String,
    val holder: Int,
    val card: Int,
    /** Two locals nothing reads after [linkAt]: the card's data, then its subtitle, and the title. */
    val scratch: List<Int>,
    val cardType: String,
    val data: String,
    val groupSet: String,
    val groupSetType: String,
    val sectionsType: String,
    val activeId: String,
    val lookup: String,
    /** The section's class, the same one the header's hook reads. */
    val sectionType: String,
    /** The section's list of items, for FeedSuggestions.sectionItems when the header's hook didn't fill it. */
    val items: String,
) {
    /** [itemsFilled] when the header's hook already filled FeedSuggestions.sectionItems. */
    fun write(context: BytecodePatchContext, itemsFilled: Boolean) = with(context) {
        val bind = mutableClassDefBy(binder).methods.single { it.isBindView() }
        // Found before the patch's other steps wrote anything: if one moved the link, leave the card be.
        val code = bind.implementation?.instructions?.toList()
        val read = code?.getOrNull(linkAt)?.fieldReference()
        if (read?.smali() != linkRead || code.getOrNull(linkAt + 1)?.methodReference()?.toString() != linkCall) {
            patchLog.warning("$PATCH: the caught up card's link moved before it was hooked. " +
                "The card keeps its Suggested for you title when nothing is left under it.")
            return@with
        }
        val extension = mutableClassDefBy(FEED_SUGGESTIONS)
        val stub = staticStub(FEED_SUGGESTIONS, CAUGHT_UP_SECTION_STUB, OBJECT)
        extension.methods.remove(stub)
        // Three registers: the sections view and the open section's id next to the card in p0.
        extension.methods.add(
            ImmutableMethod(
                stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags,
                stub.annotations, stub.hiddenApiRestrictions, ImmutableMethodImplementation(3, emptyList(), null, null),
            ).toMutable().apply {
                // Answers an Object: the open section, or null when the card has none.
                addInstructionsWithLabels(
                    0,
                    """
                        check-cast p0, $cardType
                        iget-object p0, p0, $data
                        if-eqz p0, :none
                        iget-object p0, p0, $groupSet
                        if-eqz p0, :none
                        new-instance v0, $sectionsType
                        invoke-direct { v0, p0 }, $sectionsType-><init>($groupSetType)V
                        iget-object v1, v0, $activeId
                        invoke-virtual { v0, v1 }, $lookup
                        move-result-object p0
                        :none
                        return-object p0
                    """,
                )
            },
        )
        if (!itemsFilled) {
            staticStub(FEED_SUGGESTIONS, SECTION_ITEMS_STUB, LIST).addInstructions(
                0,
                """
                    check-cast p0, $sectionType
                    iget-object p0, p0, $items
                    return-object p0
                """,
            )
        }
        val (fields, title) = scratch
        bind.addInstructions(
            linkAt,
            """
                iget-object v$fields, v$card, $data
                iget-object v$title, v$fields, $titleField
                invoke-static { v$title, v$card }, $CAUGHT_UP_TITLE
                move-result-object v$title
                iget-object v$fields, v$fields, $subtitleField
                invoke-virtual { v$holder, v$title, v$fields }, $titleCall
            """,
        )
    }
}

/**
 * Finds the end of feed card's title. The card's binder is the one bindView(int, View, Object, Object)
 * loading both [END_CARD_HOLDER] and [END_CARD_UNIT]. In it:
 * - the card's register and class come from the first cast after [END_CARD_UNIT] and the holder's
 *   class from the first cast after [END_CARD_HOLDER];
 * - the title is the one call on the holder taking two Strings, its (title, subtitle), each read
 *   right there from a String field of the card's data;
 * - the card's section getter is its one call on the card taking nothing and answering a class of the app;
 * - the link is the one call on the holder taking a String read right before it from a String field
 *   of the getter's section. The card has to stay in its register from its cast to the link, and
 *   nothing may jump in between or onto the title's call or the link's read.
 * On 451's 385713073 that's LX/05ws's bindView: LX/0Bft->A07 at 34 with LX/06LF->A0G and A0F, the
 * card, LX/06hB, in v8 with its getter A00, and the link's read of LX/06LI->A04 at 58 before
 * LX/0Bft->A06 on the holder in v3, with v0 and v1 free.
 *
 * In the getter, the one constructor taking one value, other than the section's, is the sections
 * view of the group set (LX/06LH from LX/06LE). The getter's read of a field of the group set's
 * class is the data's group set (LX/06LF->A05), and its read of a field of the data's class on the
 * card is the card's data (LX/06hB->A01). The sections view's call taking a String and answering
 * the getter's class is its lookup by id (LX/06LH->A00), and the one String its constructor keeps is
 * the open section's id (LX/06LH->A01). The section class (LX/06LI) has one list field, its items.
 * Every class and member the stub reaches has to be public, since the extension calls them.
 *
 * Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findCaughtUpCardTitle(): CaughtUpCardTitle {
    val binds = mutableListOf<Pair<ClassDef, Method>>()
    classesHolding(END_CARD_HOLDER, END_CARD_UNIT).forEach { classDef ->
        classDef.methods.filter { it.isBindView() && it.loads(END_CARD_HOLDER) && it.loads(END_CARD_UNIT) }
            .forEach { binds += classDef to it }
    }
    val (binder, bind) = binds.singleOrNull()
        ?: refuse("expected one bindView loading both end of feed card casts, found ${binds.size}")
    val code = bind.implementation?.instructions?.toList() ?: refuse("${binder.type}->bindView has no code")

    val (cardAt, cardType) = castAfter(code, END_CARD_UNIT) ?: refuse("bindView doesn't cast the card after naming it")
    val card = (code[cardAt] as OneRegisterInstruction).registerA
    val holderType = castAfter(code, END_CARD_HOLDER)?.second ?: refuse("bindView doesn't cast the holder after naming it")

    val titles = code.indices.filter { at ->
        val call = code[at].methodReference() ?: return@filter false
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call.definingClass == holderType &&
            call.parameterTypes.map(CharSequence::toString) == listOf(STRING, STRING) && call.returnType == "V"
    }
    val titleAt = titles.singleOrNull()
        ?: refuse("expected one (String, String) call on the holder $holderType, found ${titles.size}")
    if (titleAt <= cardAt) refuse("bindView sets the title before it casts the card")

    val getters = code.mapNotNull { it.methodReference() }.filter {
        it.definingClass == cardType && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") &&
            !it.returnType.startsWith("Ljava/")
    }.map { it.toString() }.distinct()
    val getterRef = getters.singleOrNull()
        ?: refuse("expected one section getter called on the card $cardType, found ${getters.size}")
    val cardClass = classDefByOrNull(cardType) ?: refuse("$cardType isn't in this build")
    val getter = cardClass.methods.singleOrNull { it.reference() == getterRef } ?: refuse("$getterRef isn't in $cardType")
    val sectionType = getter.returnType
    val walk = getter.implementation?.instructions?.toList() ?: refuse("$getterRef has no code")

    val inits = walk.filter { it.opcode == Opcode.INVOKE_DIRECT }.mapNotNull { it.methodReference() }
        .filter { it.name == "<init>" && it.parameterTypes.size == 1 && it.definingClass != sectionType }
        .map { it.definingClass to it.parameterTypes.single().toString() }.distinct()
    val (sectionsType, groupSetType) = inits.singleOrNull()
        ?: refuse("expected the getter to make one view of the group set, found ${inits.size}")
    val reads = walk.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
    val groupSet = reads.filter { it.type == groupSetType }.distinctBy { it.smali() }.singleOrNull()
        ?: refuse("expected the getter to read one $groupSetType field")
    val data = reads.filter { it.definingClass == cardType && it.type == groupSet.definingClass }.distinctBy { it.smali() }.singleOrNull()
        ?: refuse("expected the getter to read the card's ${groupSet.definingClass} once")
    val lookups = walk.mapNotNull { it.methodReference() }.filter {
        it.definingClass == sectionsType && it.parameterTypes.map(CharSequence::toString) == listOf(STRING) &&
            it.returnType == sectionType
    }.map { it.toString() }.distinct()
    val lookupRef = lookups.singleOrNull() ?: refuse("expected one lookup by id on $sectionsType, found ${lookups.size}")
    val sections = classDefByOrNull(sectionsType) ?: refuse("$sectionsType isn't in this build")
    val lookup = sections.methods.singleOrNull { it.reference() == lookupRef } ?: refuse("$lookupRef isn't in $sectionsType")
    val constructor = sections.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(groupSetType)
    } ?: refuse("$sectionsType has no constructor from $groupSetType")
    val ids = constructor.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass == sectionsType && it.type == STRING }
        .distinctBy { it.smali() }
    val activeId = ids.singleOrNull() ?: refuse("expected $sectionsType's constructor to keep one id, found ${ids.size}")

    val sectionClass = classDefByOrNull(sectionType) ?: refuse("$sectionType isn't in this build")
    val lists = sectionClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LIST }
    val items = lists.singleOrNull() ?: refuse("expected one list field in $sectionType, found ${lists.size}")

    val dataType = groupSet.definingClass
    val titleCall = code[titleAt] as FiveRegisterInstruction
    val (titleField, subtitleField) = listOf(titleCall.registerD, titleCall.registerE).map { register ->
        val read = (titleAt - 1 downTo cardAt + 1).firstOrNull { code[it].writes(register) }?.let { code[it] }
        read?.fieldReference()?.takeIf { read.opcode == Opcode.IGET_OBJECT && it.definingClass == dataType && it.type == STRING }
            ?: refuse("the card's title and subtitle aren't read from its $dataType")
    }
    val links = code.indices.drop(1).filter { at ->
        val call = code[at].methodReference() ?: return@filter false
        val text = code[at - 1].fieldReference() ?: return@filter false
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call.definingClass == holderType && call.parameterTypes.size == 2 &&
            call.parameterTypes[0].toString() == STRING && call.returnType == "V" &&
            code[at - 1].opcode == Opcode.IGET_OBJECT && text.definingClass == sectionType && text.type == STRING &&
            (code[at - 1] as TwoRegisterInstruction).registerA == (code[at] as FiveRegisterInstruction).registerD
    }
    val linkAt = (links.singleOrNull() ?: refuse("expected one link from a $sectionType on the holder, found ${links.size}")) - 1
    val holder = (code[linkAt + 1] as FiveRegisterInstruction).registerC
    if (card > 15 || holder > 15) refuse("bindView keeps the card or the holder past v15")
    if (linkAt <= cardAt) refuse("bindView sets the link before it casts the card")
    if ((cardAt + 1 until linkAt).any { code[it].writes(card) }) refuse("bindView writes over the card before the link")
    val flow = ControlFlow.of(bind)
    val last = maxOf(titleAt, linkAt)
    val jumpsIn = flow.instructions.indices.any { from ->
        (from < cardAt || from >= last) && (flow.normal[from] + flow.exceptional[from]).any { it in cardAt + 1..last }
    }
    val targets = bind.jumpTargets()
    if (jumpsIn || titleAt in targets || linkAt in targets) refuse("something jumps between the card's cast and the link")
    val scratch = bind.freeLocalsAt("$PATCH: the caught up card's title", linkAt, 2, except = listOf(card, holder))

    for (type in listOf(cardType, data.definingClass, groupSetType, sectionsType, sectionType)) {
        val classDef = classDefByOrNull(type) ?: refuse("$type isn't in this build")
        if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("$type isn't public, so the extension can't reach it")
    }
    for (field in listOf(data, groupSet, activeId, items)) {
        val declared = classDefByOrNull(field.definingClass)?.fields?.singleOrNull { it.name == field.name && it.type == field.type }
            ?: refuse("${field.smali()} isn't declared where it's read")
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            refuse("${field.smali()} isn't a public instance field")
        }
    }
    for (method in listOf(constructor, lookup)) {
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) {
            refuse("${method.reference()} isn't a public instance method")
        }
    }
    // Both stubs the write fills have to be there.
    staticStub(FEED_SUGGESTIONS, CAUGHT_UP_SECTION_STUB, OBJECT)
    staticStub(FEED_SUGGESTIONS, SECTION_ITEMS_STUB, LIST)

    return CaughtUpCardTitle(
        binder.type, titleAt, code[titleAt].methodReference().toString(), titleField.smali(), subtitleField.smali(),
        linkAt, code[linkAt].fieldReference()!!.smali(), code[linkAt + 1].methodReference().toString(), holder, card, scratch,
        cardType, data.smali(), groupSet.smali(), groupSetType, sectionsType, activeId.smali(), lookupRef, sectionType, items.smali(),
    )
}

/**
 * [findCaughtUpCardTitle], or null after the patch log says why, and the caught up card keeps its
 * title. [header] is the Suggested for you header's hook when it was found: its section class has to
 * be the card's, since both read their items through the same stub.
 */
internal fun BytecodePatchContext.caughtUpCardTitleOrWarn(header: SuggestedSectionHeader?): CaughtUpCardTitle? = try {
    findCaughtUpCardTitle().also {
        if (header != null && header.sectionType != it.sectionType) {
            refuse("its section ${it.sectionType} isn't the header's ${header.sectionType}")
        }
    }
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The caught up card keeps its Suggested for you title when nothing is left under it.")
    null
}

/** The first cast after the one load of [message] in [code]: its index and type. */
private fun castAfter(code: List<Instruction>, message: String): Pair<Int, String>? {
    val named = code.indexOfFirst { it.loadsString(message) }
    if (named < 0) return null
    val at = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.CHECK_CAST } ?: return null
    return at to ((code[at] as ReferenceInstruction).reference as TypeReference).type
}

private fun FieldReference.smali() = "$definingClass->$name:$type"

private fun Method.reference() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.loads(value: String) = implementation?.instructions?.any { it.loadsString(value) } == true

private fun Instruction.loadsString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.isBindView(): Boolean =
    name == "bindView" && returnType == "V" && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == BIND_VIEW

/** Whether this instruction writes [register], as the low or the high half of a wide value too. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = when (this) {
        is OneRegisterInstruction -> registerA
        is TwoRegisterInstruction -> registerA
        else -> return false
    }
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the caught up card's title: $detail")
