/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.requireOneKindField
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Download any reel"

internal const val REEL_BUTTON_TYPE = "$EXTENSION_PACKAGE/download/ReelDownloadButton;"
internal const val REEL_BUTTON_ON = "$REEL_BUTTON_TYPE->on()Z"
internal const val REEL_BUTTON_LITHO = "$REEL_BUTTON_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val REEL_BUTTON_LITHO_ABOVE_MORE =
    "$REEL_BUTTON_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"

/** The render of the column beside a reel, which collects its buttons in a list. */
internal const val COLUMN_MARKER = "ClipsUfiComponent_render"

/** The column's builders of Save and of its count, which the render calls one after the other. */
internal const val SAVE_MARKER = "ClipsUfiComponent_maybeCreateSaveButtonComponent"
internal const val SAVE_COUNT_MARKER = "ClipsUfiComponent_maybeCreateSaveCountComponent"

/**
 * The column's builders of Share and of More. Save goes either over Share or, past the share count,
 * over More, and More pulls itself up into the margin of what's over it unless the share count shows.
 */
internal const val SHARE_MARKER = "ClipsUfiComponent_createShareButtonComponent"
internal const val MORE_MARKER = "ClipsUfiComponent_getMoreButton"

/** The column's color filter for its icons, set only where Instagram skips dark mode there. */
internal const val TINT_MARKER = "ClipsUfiComponent_maybeGetBypassDarkModeColorFilter"

/** Save's own render, whose styles the Download icon is made with. */
internal const val SAVE_ICON_MARKER = "ClipsSaveButtonComponent_render"

/** The kind of an ordinary reel, which the extension compares the reel's item with. */
private const val ORGANIC = "ORGANIC"

private const val OBJECT = "Ljava/lang/Object;"
private const val COLOR_FILTER = "Landroid/graphics/ColorFilter;"
private const val COLOR_STATE_LIST = "Landroid/content/res/ColorStateList;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val SCALE_TYPE = "Landroid/widget/ImageView\$ScaleType;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val ENUM = "Ljava/lang/Enum;"
private const val DOUBLE_BITS = "Ljava/lang/Double;->doubleToRawLongBits(D)J"

/**
 * One place in the column's render where Save and its count have just joined the list: [at] is the
 * instruction after the count's add, [first] the first of the six registers both builders were
 * handed (the column, the scope, the size and the margin), [list] the list's register and [window]
 * the first of six free registers the hook borrows. [aboveMore] says whether the next button this
 * place reaches is More rather than Share.
 */
internal class ReelSaveSite(val at: Int, val first: Int, val list: Int, val window: Int, val aboveMore: Boolean)

/**
 * What the Download button beside a reel needs, all found by structure.
 *
 * The column ([column]) renders in [render], adding each button with [add], which takes [addType].
 * [sites] are where Save and its count were just added. [tint] is the column's own color filter
 * for its icons. The reel is read through the column's state field [state], its getter [item] of
 * the reel's item ([itemType]) and the item's [media].
 *
 * The icon is made the way Save's render makes Save: [empty] is the empty style, [width] and
 * [height] take Save's size, [margin] takes the margin the column hands Save as a dimension
 * ([dimen]) in slot [marginSlot] of [marginSlots] (its bottom, the gap under Save), [align] centers it
 * with [alignTo], [description] and [tap] give it a description and a tap, and [image] makes the
 * icon of a drawable and the style.
 *
 * More's builder sets More's top margin, slot [moreTopSlot] of the same margin style, to the
 * dimension resource [moreTop] unless the state's getter [shareCount] answers a [countType], the
 * share count showing, and then to none. On 450 that resource is minus 8dp, so More eats 8dp of the
 * margin over it.
 */
internal class ReelButtonSite(
    val column: String,
    val render: MethodReference,
    val sites: List<ReelSaveSite>,
    val add: MethodReference,
    val addType: String,
    val tint: MethodReference,
    val tintPrivate: Boolean,
    val state: FieldReference,
    val item: MethodReference,
    val itemType: String,
    val media: FieldReference,
    val empty: FieldReference,
    val width: MethodReference,
    val height: MethodReference,
    val margin: MethodReference,
    val dimen: String,
    val marginSlot: Int,
    val marginSlots: Int,
    val align: MethodReference,
    val alignTo: FieldReference,
    val description: MethodReference,
    val tap: MethodReference,
    val image: MethodReference,
    val shareCount: MethodReference,
    val countType: String,
    val moreTop: Int,
    val moreTopSlot: Int,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH, the Download button beside a reel: $detail")

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.types() = parameterTypes.map(CharSequence::toString)

private fun Method.types() = parameterTypes.map(CharSequence::toString)

private fun reference(method: Method) =
    ImmutableMethodReference(method.definingClass, method.name, method.types(), method.returnType)

private fun Instruction.calls(method: Method): Boolean {
    val called = called() ?: return false
    return called.definingClass == method.definingClass && called.name == method.name &&
        called.returnType == method.returnType && called.types() == method.types()
}

private val RANGE_CALLS = setOf(Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_VIRTUAL_RANGE)

/**
 * Adds the Download button beside a reel (#178) when the column is as this patch knows it. A build
 * whose column differs gets a warning and keeps Download in the reel's menu without the button.
 */
internal fun BytecodePatchContext.addReelDownloadButtonWhereItFits() {
    val site = try {
        findReelButtonSite()
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $PATCH goes in without the Download button beside a reel.")
        return
    }
    addReelDownloadButton(site)
    addNotInterestedButtonWhereItFits(site)
    addSoundButtonWhereItFits(site)
    addTextButtonWhereItFits(site)
}

/**
 * Finds what [addReelDownloadButton] needs: the column's render and builders by their markers, the
 * places Save and its count join the column, six registers free at each and the button each goes
 * over, the reel's item and Media, Save's styles in Save's own render and the margin More takes over
 * itself. Fails naming what it didn't find exactly once, before anything changes.
 */
internal fun BytecodePatchContext.findReelButtonSite(): ReelButtonSite {
    val names = listOf(COLUMN_MARKER, SAVE_MARKER, SAVE_COUNT_MARKER, TINT_MARKER, SAVE_ICON_MARKER, SHARE_MARKER, MORE_MARKER)
    val found = names.associateWith { mutableListOf<Method>() }
    typesMarked(*names.toTypedArray()).forEach { type ->
        classDefByOrNull(type)?.methods?.forEach { method -> method.markers().forEach { found[it]?.add(method) } }
    }
    fun one(name: String): Method = found.getValue(name).distinctBy { "${it.definingClass}->${it.name}${it.types()}" }.singleOrNull()
        ?: refuse("expected one method holding the $name marker, found ${found.getValue(name).size}")
    val render = one(COLUMN_MARKER)
    val save = one(SAVE_MARKER)
    val count = one(SAVE_COUNT_MARKER)
    val tint = one(TINT_MARKER)
    val saveIcon = one(SAVE_ICON_MARKER)
    val share = one(SHARE_MARKER)
    val more = one(MORE_MARKER)
    val column = render.definingClass
    val columnClass = classDefBy(column)
    if (!AccessFlags.PUBLIC.isSet(columnClass.accessFlags)) refuse("the column $column isn't public")
    if (share.definingClass != column || more.definingClass != column) refuse("Share's or More's builder isn't the column's")
    if (AccessFlags.STATIC.isSet(render.accessFlags) || render.parameterTypes.size != 1 || !render.returnType.startsWith("L")) {
        refuse("$column->${render.name}, the column's render, isn't an instance method of one parameter")
    }
    val builder = save.types()
    for (method in listOf(save, count)) {
        if (method.definingClass != column || AccessFlags.STATIC.isSet(method.accessFlags) || method.types() != builder ||
            builder.size != 3 || !builder[0].startsWith("L") || builder[1] != "J" || builder[2] != "J" || !method.returnType.startsWith("L")
        ) {
            refuse("${method.definingClass}->${method.name} isn't the column's builder of a scope, a size and a margin")
        }
    }
    if (tint.definingClass != column || AccessFlags.STATIC.isSet(tint.accessFlags) || tint.types() != listOf(builder[0]) ||
        tint.returnType != COLOR_FILTER
    ) {
        refuse("${tint.definingClass}->${tint.name} isn't the column's color filter of a scope")
    }

    val (state, getter, media) = reelItem(columnClass)
    requireOneKindField(PATCH, getter.returnType, listOf(ORGANIC))

    // Read as any patch before this one left it, since that's what the hook goes into.
    val body = mutableClassDefBy(column).methods.single {
        it.name == render.name && it.types() == render.types() && it.returnType == render.returnType
    }
    val code = body.implementation?.instructions?.toList() ?: refuse("$column->${render.name} has no body")
    val targets = body.jumpTargets()
    // More is made by its builder or by one of the column's methods calling it.
    val makesMore = columnClass.methods.filter { method ->
        method.implementation?.instructions?.any { it.calls(more) } == true
    }.map { reference(it).toString() }.toSet() + reference(more).toString()
    /** Whether the first of Share's builder and More's that the render reaches past [from] is More's. */
    fun aboveMore(from: Int): Boolean = (from until code.size).firstNotNullOfOrNull { index ->
        when {
            code[index].calls(share) -> false
            code[index].called()?.let { it.toString() in makesMore } == true -> true
            else -> null
        }
    } ?: refuse("in $column->${render.name} Save's count at ${from - 1} is followed by neither Share nor More")
    val calls = code.indices.filter { code[it].calls(count) }
    if (calls.isEmpty()) refuse("the column's render never adds Save's count")
    var add: MethodReference? = null
    val places = calls.map { at ->
        val countCall = code[at] as? RegisterRangeInstruction
        val saveCall = code.getOrNull(at - 3) as? RegisterRangeInstruction
        val saveResult = code.getOrNull(at - 2) as? OneRegisterInstruction
        val saveAdd = code.getOrNull(at - 1) as? FiveRegisterInstruction
        val countResult = code.getOrNull(at + 1) as? OneRegisterInstruction
        val countAdd = code.getOrNull(at + 2) as? FiveRegisterInstruction
        if (countCall == null || saveCall == null || saveResult == null || saveAdd == null || countResult == null || countAdd == null ||
            code[at].opcode !in RANGE_CALLS || code[at - 3].opcode != code[at].opcode || !code[at - 3].calls(save) ||
            countCall.registerCount != 6 || saveCall.startRegister != countCall.startRegister || saveCall.registerCount != 6 ||
            code[at - 2].opcode != Opcode.MOVE_RESULT_OBJECT || code[at + 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
            code[at - 1].opcode != Opcode.INVOKE_VIRTUAL || code[at + 2].opcode != Opcode.INVOKE_VIRTUAL ||
            saveAdd.registerCount != 2 || countAdd.registerCount != 2 || saveAdd.registerC != countAdd.registerC ||
            saveAdd.registerD != saveResult.registerA || countAdd.registerD != countResult.registerA ||
            code[at - 1].called().toString() != code[at + 2].called().toString() || at + 3 >= code.size
        ) {
            refuse("in $column->${render.name} Save's count at $at isn't added right after Save, by the same call")
        }
        val adds = code[at + 2].called()!!
        if (adds.returnType != "V" || adds.parameterTypes.size != 1 || !adds.parameterTypes[0].startsWith("L")) {
            refuse("$adds, the column's add, doesn't take one component")
        }
        if (add != null && add.toString() != adds.toString()) refuse("the column's render adds Save with $add and with $adds")
        add = adds
        val first = countCall.startRegister
        val range = first until first + 6
        if (saveResult.registerA in range || countResult.registerA in range || saveAdd.registerC in range) {
            refuse("in $column->${render.name} Save's add at $at writes a register the builders were handed")
        }
        if ((at - 2..at + 2).any { it in targets }) refuse("in $column->${render.name} something jumps between Save's add and its count's")
        Triple(at + 3, first, saveAdd.registerC)
    }
    val adds = add!!
    val addType = adds.parameterTypes[0].toString()

    // Each hook reads its own builders' registers and list, so none borrows a register any hook reads.
    val read = places.flatMap { (_, first, list) -> (first until first + 6) + list }.toSet()
    val locals = body.localRegisterCount()
    val sites = places.map { (at, first, list) ->
        val live = body.liveAcrossInjection(at)
        val window = (0..minOf(locals, 256) - 6).firstOrNull { start -> (start until start + 6).none { it in live || it in read } }
            ?: refuse("$column->${render.name} has no six free registers in a row after Save's count at ${at - 1}")
        ReelSaveSite(at, first, list, window, aboveMore(at))
    }.sortedByDescending { it.at }

    val icon = saveIconStyles(saveIcon, addType)
    val top = moreTop(more, state, icon)
    if (classDefByOrNull(REEL_BUTTON_TYPE) == null) refuse("$REEL_BUTTON_TYPE is missing")
    return ReelButtonSite(
        column, reference(render), sites, adds, addType, reference(tint), AccessFlags.PRIVATE.isSet(tint.accessFlags),
        state, getter, getter.returnType, media,
        icon.empty, icon.width, icon.height, icon.margin, icon.dimen, icon.slot, icon.slots, icon.align, icon.alignTo,
        icon.description, icon.tap, icon.image,
        top.shareCount, top.countType, top.id, top.slot,
    )
}

private class MoreTop(val shareCount: MethodReference, val countType: String, val id: Int, val slot: Int)

/**
 * The margin More's builder [more] puts over More. It loads a dimension resource by its number as
 * More's top, then swaps in zero when the column's [state] answers a count from a getter, a count
 * being what the share count shows: the getter's answer is checked with instance-of, and a zero
 * double becomes the dimension More's top is made of, a slot of the margin style Save's bottom is
 * set with. (Ads of three kinds get zero too, before this, but the button is never on an ad.) The
 * count's class has to be public for the extension to check it.
 */
private fun BytecodePatchContext.moreTop(more: Method, state: FieldReference, icon: IconStyles): MoreTop {
    val where = "${more.definingClass}->${more.name}"
    val code = more.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val flow = ControlFlow.of(more)
    val zeroed = code.indices.filter { at ->
        val check = code[at] as? TwoRegisterInstruction ?: return@filter false
        val getter = code.getOrNull(at - 2)?.called() ?: return@filter false
        val result = code.getOrNull(at - 1) as? OneRegisterInstruction ?: return@filter false
        val branch = code.getOrNull(at + 1) as? OneRegisterInstruction ?: return@filter false
        val zero = code.getOrNull(at + 2) as? WideLiteralInstruction ?: return@filter false
        val moved = code.getOrNull(at + 4) as? OneRegisterInstruction ?: return@filter false
        val made = code.getOrNull(at + 5) as? OneRegisterInstruction ?: return@filter false
        val built = code.getOrNull(at + 6) as? FiveRegisterInstruction ?: return@filter false
        val constructor = code[at + 6].called() ?: return@filter false
        code[at].opcode == Opcode.INSTANCE_OF &&
            (code[at - 2].opcode == Opcode.INVOKE_INTERFACE || code[at - 2].opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            getter.definingClass == state.type && getter.parameterTypes.isEmpty() && getter.returnType.startsWith("L") &&
            code[at - 1].opcode == Opcode.MOVE_RESULT_OBJECT && check.registerB == result.registerA &&
            code[at + 1].opcode == Opcode.IF_EQZ && branch.registerA == check.registerA &&
            flow.normal[at + 1].firstOrNull() == at + 5 && code[at + 2].opcode.setsWideRegister() && zero.wideLiteral == 0L &&
            code[at + 3].called()?.toString() == DOUBLE_BITS &&
            code[at + 4].opcode == Opcode.MOVE_RESULT_WIDE && moved.registerA == (zero as OneRegisterInstruction).registerA &&
            code[at + 5].opcode == Opcode.NEW_INSTANCE && (code[at + 5] as ReferenceInstruction).reference.toString() == icon.dimen &&
            constructor.definingClass == icon.dimen && constructor.name == "<init>" && constructor.types() == listOf("J") &&
            built.registerCount == 3 && built.registerC == made.registerA && built.registerD == moved.registerA
    }
    val at = zeroed.singleOrNull() ?: refuse("expected one place in $where where More's top margin goes to zero for a count, found ${zeroed.size}")
    val shareCount = code[at - 2].called()!!
    val countType = (code[at] as ReferenceInstruction).reference.toString()
    val countClass = classDefByOrNull(countType)
    if (countClass == null || !AccessFlags.PUBLIC.isSet(countClass.accessFlags)) refuse("the count $countType isn't public")

    // What More's top is otherwise: a dimension resource by its number, loaded before the getter.
    val value = (code[at + 2] as OneRegisterInstruction).registerA
    val loaded = lastWrite(code, at - 2, value, wide = true)
    val resolve = loaded?.let { code.getOrNull(it - 1)?.called() }
    if (loaded == null || resolve == null || code[loaded].opcode != Opcode.MOVE_RESULT_WIDE ||
        code[loaded - 1].opcode != Opcode.INVOKE_STATIC || resolve.types().size != 2 || resolve.types()[1] != "I" || resolve.returnType != "J"
    ) {
        refuse("in $where More's top margin isn't a dimension loaded by its number")
    }
    val number = lastWrite(code, loaded - 1, (code[loaded - 1] as FiveRegisterInstruction).registerD)
        ?.let { code[it] as? NarrowLiteralInstruction }?.narrowLiteral
    if (number == null || number ushr 24 != 0x7f) refuse("in $where More's top margin isn't one of Instagram's own dimensions")

    // The slot of the margin style that the zero or the dimension goes in.
    val slots = code.indices.filter { code[it].called()?.toString() == icon.margin.toString() }.flatMap { call ->
        val range = code[call] as? RegisterRangeInstruction ?: return@flatMap emptyList()
        (1..icon.slots).filter { comesFrom(code, call, range.startRegister + it, at + 5) }
    }
    val slot = slots.singleOrNull() ?: refuse("in $where More's top goes in ${slots.size} slots of the margin style, not one")
    if (slot == icon.slot) refuse("in $where More's zeroed margin is the slot Save's bottom is in")
    return MoreTop(shareCount, countType, number, slot)
}

/**
 * The last instruction before [at] that writes [register], or [register] and the one after it when
 * [wide], counting a write of a pair as a write of both its registers.
 */
private fun lastWrite(code: List<Instruction>, at: Int, register: Int, wide: Boolean = false): Int? = (at - 1 downTo 0).firstOrNull { index ->
    val instruction = code[index]
    val written = (instruction as? OneRegisterInstruction)?.registerA
    if (written == null || !instruction.opcode.setsRegister()) return@firstOrNull false
    val last = if (instruction.opcode.setsWideRegister()) written + 1 else written
    written <= (if (wide) register + 1 else register) && register <= last
}

/** Whether [register] holds, at [at], what instruction [source] made, through moves of objects. */
private fun comesFrom(code: List<Instruction>, at: Int, register: Int, source: Int): Boolean {
    var held = register
    var before = at
    while (true) {
        val written = lastWrite(code, before, held) ?: return false
        val instruction = code[written]
        when (instruction.opcode) {
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                held = (instruction as TwoRegisterInstruction).registerB
                before = written
            }
            else -> return written == source
        }
    }
}

/**
 * The column's state field, its getter of the reel's item and the item's Media: the one instance
 * field of [column] typed by a public interface with one getter taking nothing that answers a public
 * class holding exactly one instance Media field, itself public.
 */
private fun BytecodePatchContext.reelItem(column: ClassDef): Triple<FieldReference, MethodReference, FieldReference> {
    class Found(val state: FieldReference, val getter: MethodReference, val media: FieldReference)
    val found = column.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.mapNotNull { field ->
        val type = classDefByOrNull(field.type) ?: return@mapNotNull null
        if (!AccessFlags.INTERFACE.isSet(type.accessFlags)) return@mapNotNull null
        val getters = type.methods.mapNotNull { getter ->
            if (AccessFlags.STATIC.isSet(getter.accessFlags) || getter.parameterTypes.isNotEmpty()) return@mapNotNull null
            val item = classDefByOrNull(getter.returnType) ?: return@mapNotNull null
            val media = item.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MEDIA }.singleOrNull()
                ?: return@mapNotNull null
            Triple(getter, item, media)
        }
        val (getter, item, media) = getters.singleOrNull() ?: return@mapNotNull null
        if (!AccessFlags.PUBLIC.isSet(field.accessFlags) || !AccessFlags.PUBLIC.isSet(type.accessFlags) ||
            !AccessFlags.PUBLIC.isSet(item.accessFlags) || !AccessFlags.PUBLIC.isSet(media.accessFlags)
        ) {
            refuse("the column's state ${column.type}->${field.name}, its reel item or the item's Media isn't public")
        }
        Found(
            ImmutableFieldReference(field.definingClass, field.name, field.type),
            reference(getter),
            ImmutableFieldReference(media.definingClass, media.name, media.type),
        )
    }
    val one = found.singleOrNull() ?: refuse("expected one state field in ${column.type} that gets a reel item with a Media, found ${found.size}")
    return Triple(one.state, one.getter, one.media)
}

private class IconStyles(
    val empty: FieldReference,
    val width: MethodReference,
    val height: MethodReference,
    val margin: MethodReference,
    val dimen: String,
    val slot: Int,
    val slots: Int,
    val align: MethodReference,
    val alignTo: FieldReference,
    val description: MethodReference,
    val tap: MethodReference,
    val image: MethodReference,
)

/**
 * Save's styles, read off Save's render [saveIcon] by their shapes. The icon is made with the one
 * constructor taking a color list, a drawable, a scale type, a style and three flags, and the style
 * type is its fourth parameter. Of the static calls taking a style first and answering one: the two
 * taking a long are the width and the height, the one taking nothing but dimensions after it is the
 * margin (Save sets one of them, its bottom, to the margin the column hands it, and More's builder
 * sets More's top with the same style), the one taking an enum loaded right before is the alignment, the
 * one taking a CharSequence the description, and of the two taking a Function1 in the class of the
 * one taking an int (the id), the first is the tap. The icon has to reach [addType], what the
 * column's add takes, and everything the extension calls has to be public.
 */
private fun BytecodePatchContext.saveIconStyles(saveIcon: Method, addType: String): IconStyles {
    val where = "${saveIcon.definingClass}->${saveIcon.name}"
    val code = saveIcon.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val images = code.mapNotNull { instruction ->
        instruction.called()?.takeIf {
            it.name == "<init>" && it.returnType == "V" && it.types().size == 7 &&
                it.types().let { types ->
                    types[0] == COLOR_STATE_LIST && types[1] == DRAWABLE && types[2] == SCALE_TYPE && types[3].startsWith("L") &&
                        types[4] == "Z" && types[5] == "Z" && types[6] == "Z"
                }
        }
    }.distinctBy { it.toString() }
    val image = images.singleOrNull() ?: refuse("expected one icon constructor in $where, found ${images.size}")
    val style = image.types()[3]
    var chain: String? = image.definingClass
    val seen = mutableListOf<String>()
    while (chain != null && chain !in seen) {
        seen += chain
        chain = classDefByOrNull(chain)?.superclass
    }
    if (addType !in seen) refuse("the icon ${image.definingClass} isn't a $addType, which the column takes")
    requirePublic(image)

    val empties = code.mapNotNull { instruction ->
        instruction.field()?.takeIf { instruction.opcode == Opcode.SGET_OBJECT && it.definingClass == style }
    }.distinctBy { it.toString() }
    val empty = empties.singleOrNull() ?: refuse("expected one empty style in $where, found ${empties.size}")
    val emptyField = classDefByOrNull(style)?.fields?.singleOrNull { it.name == empty.name && it.type == empty.type }
    if (emptyField == null || !AccessFlags.PUBLIC.isSet(emptyField.accessFlags) || !AccessFlags.STATIC.isSet(emptyField.accessFlags)) {
        refuse("the empty style $empty isn't public")
    }

    val styled = code.indices.mapNotNull { at ->
        val called = code[at].called() ?: return@mapNotNull null
        val types = called.types()
        if (code[at].opcode != Opcode.INVOKE_STATIC && code[at].opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        if (called.returnType != style || types.firstOrNull() != style) return@mapNotNull null
        at to called
    }
    fun distinct(shape: (MethodReference) -> Boolean) = styled.map { it.second }.filter(shape).distinctBy { it.toString() }

    val sizes = distinct { it.types() == listOf(style, "J") }
    if (sizes.size != 2) refuse("expected a width and a height style in $where, found ${sizes.size}")
    val margins = distinct { it.types().size >= 3 && it.types().drop(1).toSet().size == 1 && it.types()[1] != style && it.types()[1].startsWith("L") }
    val margin = margins.singleOrNull() ?: refuse("expected one margin style in $where, found ${margins.size}")
    val dimen = margin.types()[1]
    val slots = margin.types().size - 1
    if (slots > 13) refuse("$margin takes $slots dimensions, more than the icon bridge has registers for")
    val marginCalls = styled.filter { it.second.toString() == margin.toString() }.map { it.first }
    val slot = marginCalls.map { at ->
        val call = code[at] as? RegisterRangeInstruction ?: refuse("$where hands its margin over without a range")
        val set = (1..slots).filter { !holdsNull(code, at, call.startRegister + it) }
        set.singleOrNull() ?: refuse("$where sets ${set.size} of Save's margin dimensions, not one")
    }.distinct().singleOrNull() ?: refuse("$where sets Save's margin in more than one way")
    val dimenClass = classDefByOrNull(dimen) ?: refuse("the dimension $dimen is missing")
    val dimenConstructor = dimenClass.methods.singleOrNull { it.name == "<init>" && it.types() == listOf("J") }
    if (!AccessFlags.PUBLIC.isSet(dimenClass.accessFlags) || dimenConstructor == null || !AccessFlags.PUBLIC.isSet(dimenConstructor.accessFlags)) {
        refuse("the dimension $dimen has no public constructor of a long")
    }

    val aligns = styled.filter { (at, called) ->
        val types = called.types()
        if (types.size != 2) return@filter false
        val enum = classDefByOrNull(types[1]) ?: return@filter false
        val loaded = code.getOrNull(at - 1) ?: return@filter false
        enum.superclass == ENUM && loaded.opcode == Opcode.SGET_OBJECT && loaded.field()?.definingClass == types[1] &&
            (code[at] as? FiveRegisterInstruction)?.registerD == (loaded as OneRegisterInstruction).registerA
    }
    val align = aligns.map { it.second }.distinctBy { it.toString() }.singleOrNull()
        ?: refuse("expected one alignment style in $where, found ${aligns.size}")
    val alignTo = aligns.map { code[it.first - 1].field()!! }.distinctBy { it.toString() }.singleOrNull()
        ?: refuse("$where aligns Save in more than one way")
    val alignField = classDefByOrNull(alignTo.definingClass)?.fields?.singleOrNull { it.name == alignTo.name && it.type == alignTo.type }
    if (alignField == null || !AccessFlags.PUBLIC.isSet(alignField.accessFlags)) refuse("the alignment $alignTo isn't public")

    val descriptions = distinct { it.types() == listOf(style, CHAR_SEQUENCE) }
    val description = descriptions.singleOrNull() ?: refuse("expected one description style in $where, found ${descriptions.size}")
    val ids = distinct { it.types() == listOf(style, "I") }
    val id = ids.singleOrNull() ?: refuse("expected one id style in $where, found ${ids.size}")
    val taps = distinct { it.definingClass == id.definingClass && it.types() == listOf(style, FUNCTION1) }
    if (taps.size != 2) refuse("expected a tap and a long press style next to the id style in $where, found ${taps.size}")
    val tap = taps[0]

    listOf(sizes[0], sizes[1], margin, align, description, tap).forEach { requirePublic(it) }
    return IconStyles(
        ImmutableFieldReference(empty.definingClass, empty.name, empty.type),
        sizes[0], sizes[1], margin, dimen, slot, slots, align,
        ImmutableFieldReference(alignTo.definingClass, alignTo.name, alignTo.type), description, tap, image,
    )
}

/** Throws unless [method] and its class are public, so the extension may call it. */
private fun BytecodePatchContext.requirePublic(method: MethodReference) {
    val owner = classDefByOrNull(method.definingClass) ?: refuse("${method.definingClass} is missing")
    val declared = owner.methods.singleOrNull { it.name == method.name && it.types() == method.types() && it.returnType == method.returnType }
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags)) {
        refuse("$method isn't public")
    }
}

/** Whether [register] holds null at [at]: last set by a zero constant, or moved from a register that did. */
private fun holdsNull(code: List<Instruction>, at: Int, register: Int): Boolean {
    for (index in at - 1 downTo 0) {
        val instruction = code[index]
        if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != register) continue
        return when (instruction.opcode) {
            Opcode.CONST_4, Opcode.CONST_16 -> (instruction as NarrowLiteralInstruction).narrowLiteral == 0
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                holdsNull(code, index, (instruction as TwoRegisterInstruction).registerB)
            else -> false
        }
    }
    return false
}

/**
 * Right after Save's count joins the column, at each of [site]'s places, asks the extension whether
 * the button is on, and when it is hands it the column, the column's color filter and the size and
 * margin Save was handed, and adds the icon it answers to the column. The place over More asks
 * through lithoAboveMore, which makes up the bottom margin More takes back. A jump to the instruction
 * after the count's add skips it, as it skips Save. Then writes the bridges the extension reads the
 * reel and More's top margin and makes the icon through.
 */
internal fun BytecodePatchContext.addReelDownloadButton(site: ReelButtonSite) {
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.types() == site.render.types() && it.returnType == site.render.returnType
    }
    val tint = if (site.tintPrivate) "invoke-direct/range" else "invoke-virtual/range"
    site.sites.sortedByDescending { it.at }.forEach { place ->
        val w = place.window
        val s = place.first
        val litho = if (place.aboveMore) REEL_BUTTON_LITHO_ABOVE_MORE else REEL_BUTTON_LITHO
        render.addInstructionsWithLabels(
            place.at,
            """
                invoke-static {}, $REEL_BUTTON_ON
                move-result v$w
                if-eqz v$w, :skip
                $tint { v$s .. v${s + 1} }, ${site.tint}
                move-result-object v${w + 1}
                move-object/from16 v$w, v$s
                move-wide/from16 v${w + 2}, v${s + 2}
                move-wide/from16 v${w + 4}, v${s + 4}
                invoke-static/range { v$w .. v${w + 5} }, $litho
                move-result-object v${w + 1}
                if-eqz v${w + 1}, :skip
                move-object/from16 v$w, v${place.list}
                check-cast v${w + 1}, ${site.addType}
                invoke-virtual/range { v$w .. v${w + 1} }, ${site.add}
            """,
            ExternalLabel("skip", render.getInstruction(place.at)),
        )
    }

    val bridges = mutableClassDefBy(REEL_BUTTON_TYPE)
    fun stub(name: String, returns: String, vararg parameters: String) = bridges.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns && it.types() == parameters.toList()
    } ?: throw PatchException("$PATCH: $REEL_BUTTON_TYPE has no static $returns $name(${parameters.joinToString()})")
    val itemOf = stub("reelItem", OBJECT, OBJECT)
    val mediaOf = stub("itemMedia", OBJECT, OBJECT)
    val moreTopOf = stub("moreTop", "I", OBJECT)
    val iconOf = stub("lithoIcon", OBJECT, OBJECT, CHAR_SEQUENCE, OBJECT, "J", "J")

    rewriteStub(
        bridges, itemOf, 0,
        """
            check-cast p0, ${site.column}
            iget-object p0, p0, ${site.state}
            invoke-interface { p0 }, ${site.item}
            move-result-object p0
            return-object p0
        """,
    )
    rewriteStub(
        bridges, mediaOf, 0,
        """
            check-cast p0, ${site.itemType}
            iget-object p0, p0, ${site.media}
            return-object p0
        """,
    )
    // More's own check: no top margin while the share count shows, else the dimension it loads.
    rewriteStub(
        bridges, moreTopOf, 0,
        """
            check-cast p0, ${site.column}
            iget-object p0, p0, ${site.state}
            invoke-interface { p0 }, ${site.shareCount}
            move-result-object p0
            instance-of p0, p0, ${site.countType}
            if-eqz p0, :pulled
            const/4 p0, 0x0
            return p0
            :pulled
            const p0, ${site.moreTop}
            return p0
        """,
    )
    // v0 the style, v1..v<slots> the margin call's dimensions, v<slots + 1> the margin. Then v1..v8
    // the icon's constructor: the icon, no color list, the drawable, the scale type, the style and
    // three flags off, as Save's own icon has them.
    val slots = site.marginSlots
    val dimensions = (1..slots).joinToString("\n") { slot ->
        if (slot == site.marginSlot) "move-object/from16 v$slot, v${slots + 1}" else "const/4 v$slot, 0x0"
    }
    rewriteStub(
        bridges, iconOf, maxOf(9, slots + 2),
        """
            sget-object v0, ${site.empty}
            move-wide/from16 v1, p3
            invoke-static { v0, v1, v2 }, ${site.width}
            move-result-object v0
            invoke-static { v0, v1, v2 }, ${site.height}
            move-result-object v0
            move-wide/from16 v1, p5
            new-instance v3, ${site.dimen}
            invoke-direct { v3, v1, v2 }, ${site.dimen}-><init>(J)V
            move-object/from16 v${slots + 1}, v3
            $dimensions
            invoke-static/range { v0 .. v$slots }, ${site.margin}
            move-result-object v0
            sget-object v1, ${site.alignTo}
            invoke-static { v0, v1 }, ${site.align}
            move-result-object v0
            move-object/from16 v1, p1
            invoke-static { v0, v1 }, ${site.description}
            move-result-object v0
            move-object/from16 v1, p2
            check-cast v1, $FUNCTION1
            invoke-static { v0, v1 }, ${site.tap}
            move-result-object v0
            new-instance v1, ${site.image.definingClass}
            const/4 v2, 0x0
            move-object/from16 v3, p0
            check-cast v3, $DRAWABLE
            sget-object v4, $SCALE_TYPE->FIT_CENTER:$SCALE_TYPE
            move-object v5, v0
            const/4 v6, 0x0
            const/4 v7, 0x0
            const/4 v8, 0x0
            invoke-direct/range { v1 .. v8 }, ${site.image}
            return-object v1
        """,
    )
}
