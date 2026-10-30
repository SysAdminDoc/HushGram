/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.time

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.suggested.ClipsItemConverterFingerprint
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PATCH = "Show a reel's exact time"
internal const val REEL_POST_TIME = "$EXTENSION_PACKAGE/reels/ReelPostTime;"

/** The extension's state for the line, which the patch makes one of Instagram's subtitle states. */
internal const val REEL_TIME_LINE = "$EXTENSION_PACKAGE/reels/ReelPostTime\$Line;"
internal const val ADD_TIME_LINE = "$REEL_POST_TIME->line(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val TIME_LINE_TEXT = "$REEL_POST_TIME->lineText(Ljava/lang/Object;)Ljava/lang/CharSequence;"
internal const val NO_CLICK = "$REEL_POST_TIME->noClick()Landroid/view/View\$OnClickListener;"

/** The method the patch adds to the Reels title: its subtitle maker, taking the state to show. */
internal const val TIME_LINE_MAKER = "hushgramTimeLine"

/** The Media field holding when a post went up, in seconds, as the server sent it. */
internal const val TAKEN_AT_FIELD = "taken_at"

/**
 * The purge markers of what the patch reads and changes: the Reels viewer's title drawing its name
 * row and subtitle, the title making a subtitle, the subtitle drawing itself, the subtitle picking
 * what to draw for its state, and the plain subtitle text with an optional icon.
 */
internal const val TITLE_RENDER = "ClipsTitleAndSubtitleComponent_render"
internal const val SUBTITLE_MAKER = "ClipsTitleAndSubtitleComponent_getClipsAuthorSubtitleComponent"
internal const val SUBTITLE_RENDER = "ClipsAuthorSubtitleComponent_render"
internal const val SUBTITLE_PICK = "ClipsAuthorSubtitleComponent_getComponentFromClipsAuthorSubtitleType"
internal const val ICON_TEXT_RENDER = "CyclicSubtitleWithIconComponent_render"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val LONG = "Ljava/lang/Long;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val ON_CLICK = "Landroid/view/View\$OnClickListener;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"

/** The registers the hook in the subtitle's pick uses: the text it makes and its nine arguments. */
private const val PICK_REGISTERS = 10

/**
 * Shows when a reel was posted on a line of its own under the author's name in the Reels viewer,
 * with the seconds for those who want them. In the default selection with its switch off: the
 * viewer looks as Instagram made it until someone asks for the time. Asked for in #172.
 */
@Suppress("unused")
val showReelTimePatch = bytecodePatch(
    name = "Show a reel's exact time",
    description = "Adds the date and time a reel was posted under the author's name, like Oct 2, 3:45 PM, with " +
        "the seconds or how long ago (1d 2h 5m ago) if you want them. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelPostTime")
        val sites = findReelTime()
        showReelTime(sites)
        enableStatus("reelPostTime")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What the patch changes and what it fills the extension's stubs from.
 *
 * The title's [render] makes its column at instruction [columnAt], with the column's list of parts
 * in register [list] and the local [scratch] free for the hook. The title's subtitle [maker] loads
 * its marker at [markerAt] and reads the state to show at [stateAt], into [stateRegister]. The
 * [subtitle] it makes keeps the viewer's config in [config] and the session in [session], and its
 * [pick] takes the [state] as parameter [stateParameter] and the colors as [colorsParameter].
 * [iconText] is the plain subtitle text, made by a constructor taking [iconTextTakes]. The reel's
 * post is the title's [reel] field, that reel's [item] field and that item's [media] field, and
 * [takenAt] is the post's getter for [TAKEN_AT_FIELD].
 */
internal class ReelTimeSites(
    val title: String,
    val render: Method,
    val columnAt: Int,
    val list: Int,
    val scratch: Int,
    val maker: Method,
    val markerAt: Int,
    val stateAt: Int,
    val stateRegister: Int,
    val state: String,
    val subtitle: String,
    val config: Field,
    val session: Field,
    val pick: Method,
    val stateParameter: Int,
    val colorsParameter: Int,
    val iconText: String,
    val iconTextTakes: List<String>,
    val reel: Field,
    val item: Field,
    val media: Field,
    val takenAt: Method,
)

/**
 * Finds where the Reels viewer builds the lines under a reel's author, and proves what the line
 * with the time relies on, before anything changes.
 *
 * The title ([TITLE_RENDER]) answers a column it has just made from one list of parts: the name
 * row and, for a reel that has one, the subtitle its maker ([SUBTITLE_MAKER]) answers. The maker
 * runs straight through, reads the state to show from the title's reel and hands it to the
 * subtitle it makes. That state is an interface with nothing to implement, so the extension's line
 * can be one. The subtitle keeps the state in one field, which only its own render
 * ([SUBTITLE_RENDER]) reads, to hand it to the pick ([SUBTITLE_PICK]) that decides what to draw.
 * So a state Instagram doesn't know is seen nowhere but in that pick, where the hook answers it
 * first.
 *
 * What the pick draws for the line is Instagram's own subtitle text ([ICON_TEXT_RENDER]), which
 * the pick makes itself for other states: a public class with one public constructor, taking a
 * listener, the viewer's config, the session, the colors the pick was handed, the text, a width,
 * one more object, an icon and a flag. It leaves the icon out when the icon is zero, and it is
 * what the pick answers. The time comes from the reel the title belongs to: the title's one field
 * of the type the maker reads the state through, that type's one field of the Reels item the
 * converter ([TO_CLIPS_ITEM]) makes, and the item's one post, all public, and the post's getter for
 * [TAKEN_AT_FIELD], found by the field's own name so no download patch has to be in the build.
 *
 * Fails naming what it found when any of it isn't there or isn't shaped that way, since that's an
 * update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelTime(): ReelTimeSites {
    val held = marked(TITLE_RENDER, SUBTITLE_MAKER, SUBTITLE_RENDER, SUBTITLE_PICK, ICON_TEXT_RENDER)
    fun one(marker: String): Method {
        val found = held[marker].orEmpty()
        return found.singleOrNull() ?: refuse(
            "expected one method holding the $marker marker, found " +
                if (found.isEmpty()) "none" else found.joinToString { it.describe() },
        )
    }

    val render = one(TITLE_RENDER)
    val title = render.definingClass
    val titleClass = classDefBy(title)
    if (AccessFlags.STATIC.isSet(render.accessFlags)) refuse("${render.describe()} is static")
    val (columnAt, list) = column(render)
    render.requireThisIntact("$PATCH: the Reels title", listOf(columnAt))
    val scratch = render.freeLocalsAt("$PATCH: the Reels title", columnAt, 1, except = listOf(list)).single()

    val maker = one(SUBTITLE_MAKER)
    if (maker.definingClass != title) refuse("the $SUBTITLE_MAKER marker is in ${maker.definingClass}, not the Reels title $title")
    if (AccessFlags.STATIC.isSet(maker.accessFlags) || maker.parameters() != listOf("Z")) {
        refuse("expected ${maker.describe()} to be an instance method taking one flag")
    }
    val makerCode = maker.code()
    if (maker.jumpTargets().isNotEmpty() || maker.implementation!!.tryBlocks.isNotEmpty() || makerCode.any { it is OffsetInstruction }) {
        refuse("${maker.describe()} branches, so a copy of it can't be made a line at a time")
    }
    if (titleClass.methods.any { it.name == TIME_LINE_MAKER }) refuse("$title already has a $TIME_LINE_MAKER")
    if (render.code().none { it.call()?.matches(maker) == true }) refuse("${render.describe()} doesn't make a subtitle")

    val markerAt = makerCode.indices.singleOrNull { at ->
        makerCode[at].string()?.let { PURGE_MARKER.find(it)?.groupValues?.get(1) } == SUBTITLE_MAKER
    } ?: refuse("${maker.describe()} doesn't load its marker exactly once")
    val markerRegister = (makerCode[markerAt] as OneRegisterInstruction).registerA
    val tracked = makerCode.getOrNull(markerAt + 1)
    val tracker = tracked?.call()
    if (tracked == null || tracked.opcode != Opcode.INVOKE_STATIC || tracker == null || tracker.returnType != "V" ||
        tracker.parameterTypes.map(Any::toString) != listOf(STRING) || tracked.registers() != listOf(markerRegister) ||
        markerRegister in RegisterLiveness.of(maker).liveInto(markerAt + 2)
    ) {
        refuse("${maker.describe()} doesn't hand its marker to the tracker and leave it at that")
    }

    val reads = makerCode.indices.filter { at ->
        makerCode[at].opcode == Opcode.IGET_OBJECT && makerCode[at].field()?.definingClass != title
    }
    val stateAt = reads.singleOrNull()
        ?: refuse("expected ${maker.describe()} to read one object through the title's reel, found ${reads.size}")
    val read = makerCode[stateAt] as TwoRegisterInstruction
    val stateRegister = read.registerA
    val state = makerCode[stateAt].field()!!.type
    val holder = makerCode[stateAt].field()!!.definingClass
    val reel = titleClass.only(holder, "reel")
    val loaded = (stateAt - 1 downTo 0).firstOrNull { makerCode[it].writes(read.registerB) }
    if (loaded == null || makerCode[loaded].opcode != Opcode.IGET_OBJECT ||
        makerCode[loaded].field()?.let { it.definingClass == title && it.name == reel.name } != true
    ) {
        refuse("${maker.describe()} doesn't read its state from the title's reel ${reel.name}")
    }
    val stateClass = classDefByOrNull(state) ?: refuse("the subtitle's state $state isn't a class of the app")
    if (!AccessFlags.INTERFACE.isSet(stateClass.accessFlags) || !AccessFlags.PUBLIC.isSet(stateClass.accessFlags) ||
        stateClass.methods.any() || stateClass.interfaces.isNotEmpty()
    ) {
        refuse("expected the subtitle's state $state to be a public interface with nothing to implement")
    }

    val subtitle = maker.returnType
    val subtitleClass = classDefByOrNull(subtitle) ?: refuse("the subtitle $subtitle isn't a class of the app")
    val made = makerCode.indices.filter { at -> makerCode[at].call()?.let { it.definingClass == subtitle && it.name == "<init>" } == true }
    val makes = made.singleOrNull() ?: refuse("expected ${maker.describe()} to make one $subtitle, found ${made.size}")
    val taken = makerCode[makes].call()!!.parameterTypes.map(Any::toString)
    val slot = taken.indices.singleOrNull { taken[it] == state }
        ?: refuse("expected $subtitle's constructor to take the state $state once, found $taken")
    if (makes < stateAt || makerCode[makes].registers().getOrNull(1 + taken.take(slot).sumOf { it.width() }) != stateRegister ||
        (stateAt + 1 until makes).any { makerCode[it].writes(stateRegister) }
    ) {
        refuse("${maker.describe()} doesn't hand the state it read to the $subtitle it makes")
    }

    val subtitleRender = one(SUBTITLE_RENDER)
    if (subtitleRender.definingClass != subtitle) {
        refuse("the $SUBTITLE_RENDER marker is in ${subtitleRender.definingClass}, not the subtitle $subtitle the title makes")
    }
    val kept = subtitleClass.only(state, "state")
    val readers = classesAccessing(subtitle, kept.name, Opcode.IGET_OBJECT).flatMap { reader ->
        reader.methods.filter { method ->
            method.implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.IGET_OBJECT &&
                    instruction.field()?.let { it.definingClass == subtitle && it.name == kept.name } == true
            } == true
        }
    }.map { it.describe() }
    if (readers != listOf(subtitleRender.describe())) {
        refuse("expected only ${subtitleRender.describe()} to read the subtitle's state, found $readers")
    }

    val pick = one(SUBTITLE_PICK)
    if (pick.definingClass != subtitle || AccessFlags.STATIC.isSet(pick.accessFlags)) {
        refuse("expected ${pick.describe()} to be an instance method of the subtitle $subtitle")
    }
    val handed = pick.parameters()
    val stateParameter = handed.indices.singleOrNull { handed[it] == state }
        ?: refuse("expected ${pick.describe()} to take the state $state once")
    if (subtitleRender.code().none { it.call()?.matches(pick) == true }) {
        refuse("${subtitleRender.describe()} doesn't pick what it draws through ${pick.describe()}")
    }
    if (pick.code().isEmpty() || 0 in pick.jumpTargets()) refuse("a jump or exception handler enters ${pick.describe()} at its first instruction")
    if (pick.localRegisterCount() < PICK_REGISTERS) {
        refuse("${pick.describe()} has ${pick.localRegisterCount()} local register(s), and the hook needs $PICK_REGISTERS")
    }

    val iconRender = one(ICON_TEXT_RENDER)
    val iconText = iconRender.definingClass
    val iconClass = classDefBy(iconText)
    val constructors = iconClass.methods.filter { it.name == "<init>" }
    val constructor = constructors.singleOrNull() ?: refuse("expected $iconText to have one constructor, found ${constructors.size}")
    val iconTextTakes = constructor.parameters()
    val shaped = iconTextTakes.size == 9 && iconTextTakes[0] == ON_CLICK && iconTextTakes[1] == VIEWER_CONFIG &&
        iconTextTakes[2] == USER_SESSION && iconTextTakes[4] == CHAR_SEQUENCE && iconTextTakes[5] == INTEGER &&
        iconTextTakes[6].startsWith("L") && iconTextTakes[7] == "I" && iconTextTakes[8] == "Z"
    if (!shaped) {
        refuse(
            "expected $iconText to be made from a listener, the viewer's config, the session, colors, the text, a width, " +
                "an object, an icon and a flag, found $iconTextTakes",
        )
    }
    if (!AccessFlags.PUBLIC.isSet(iconClass.accessFlags) || !AccessFlags.PUBLIC.isSet(constructor.accessFlags)) {
        refuse("$iconText or its constructor isn't public, so the subtitle can't make one")
    }
    val colorsParameter = handed.indices.singleOrNull { handed[it] == iconTextTakes[3] }
        ?: refuse("expected ${pick.describe()} to take the colors ${iconTextTakes[3]} once")
    if (pick.code().none { it.opcode == Opcode.NEW_INSTANCE && it.type() == iconText }) {
        refuse("${pick.describe()} doesn't make $iconText itself")
    }
    if (pick.returnType !in ancestors(iconText)) refuse("$iconText isn't a ${pick.returnType}, which ${pick.describe()} answers")
    val icon = iconClass.only("I", "icon")
    if (!iconRender.branchesOnZero(iconText, icon.name)) {
        refuse("${iconRender.describe()} doesn't leave the icon out when it is zero")
    }
    val config = subtitleClass.only(VIEWER_CONFIG, "viewer config")
    val session = subtitleClass.only(USER_SESSION, "session")

    val converter = uniqueMethod(PATCH, "Reels item converter holding \"$TO_CLIPS_ITEM\"", ClipsItemConverterFingerprint)
    val itemClass = classDefByOrNull(converter.returnType)
        ?: refuse("the Reels item converter answers ${converter.returnType}, which isn't a class of the app")
    val holderClass = classDefByOrNull(holder) ?: refuse("the title's reel $holder isn't a class of the app")
    val item = holderClass.only(itemClass.type, "Reels item")
    val media = itemClass.only(MEDIA, "post")
    for ((owner, field) in listOf(titleClass to reel, holderClass to item, itemClass to media)) {
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(field.accessFlags)) {
            refuse("${owner.type}->${field.name} isn't public, so the extension can't reach it")
        }
    }
    val takenAt = pandoGetter(PATCH, MEDIA, TAKEN_AT_FIELD, LONG)
    if (!AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) || !AccessFlags.PUBLIC.isSet(takenAt.accessFlags)) {
        refuse("$MEDIA->${takenAt.name} isn't public, so the extension can't reach it")
    }

    val extension = classDefByOrNull(REEL_POST_TIME) ?: refuse("the extension has no $REEL_POST_TIME")
    for (hook in listOf(ADD_TIME_LINE, TIME_LINE_TEXT, NO_CLICK)) {
        if (extension.methods.none { it.describe() == hook && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }) {
            refuse("the extension has no public static ${hook.substringAfter("->")}")
        }
    }
    for ((stub, takes, returns) in STUBS) {
        if (extension.methods.none { it.isStatic(stub, takes, returns) }) refuse("the extension has no static $stub to fill")
    }
    val line = classDefByOrNull(REEL_TIME_LINE) ?: refuse("the extension has no $REEL_TIME_LINE")
    if (AccessFlags.INTERFACE.isSet(line.accessFlags) || AccessFlags.ABSTRACT.isSet(line.accessFlags)) {
        refuse("$REEL_TIME_LINE isn't a class the extension can make")
    }

    return ReelTimeSites(
        title, render, columnAt, list, scratch, maker, markerAt, stateAt, stateRegister, state, subtitle, config, session,
        pick, stateParameter, colorsParameter, iconText, iconTextTakes, reel, item, media, takenAt,
    )
}

/** The extension's stubs the patch fills: name, parameters and what each answers. */
private val STUBS = listOf(
    Triple("media", listOf(OBJECT), OBJECT),
    Triple("takenAt", listOf(OBJECT), LONG),
    Triple("component", listOf(OBJECT, OBJECT), OBJECT),
)

/**
 * Where [render] makes the column it answers, and the register holding the column's list of parts
 * there. The title has one return, of the object the instruction before it has just constructed,
 * and that constructor takes one List, the parts. Nothing may jump to the constructor's call, or
 * code put in front of it would be skipped, and the list has to be in a register a call can name.
 */
private fun column(render: Method): Pair<Int, Int> {
    val where = render.describe()
    val code = render.code()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    val last = returns.singleOrNull() ?: refuse("expected $where to answer its column in one place, found ${returns.size}")
    val made = last - 1
    val constructor = code.getOrNull(made)?.call()
    val registers = code.getOrNull(made)?.registers().orEmpty()
    if (constructor == null || constructor.name != "<init>" || registers.isEmpty() ||
        registers.first() != (code[last] as OneRegisterInstruction).registerA
    ) {
        refuse("$where doesn't answer the column it has just made")
    }
    val taken = constructor.parameterTypes.map(Any::toString)
    val slot = taken.indices.singleOrNull { taken[it] == LIST }
        ?: refuse("expected the column $where makes to take one list of parts, found $taken")
    val list = registers.getOrNull(1 + taken.take(slot).sumOf { it.width() })
        ?: refuse("$where makes its column with fewer registers than its constructor takes")
    if (list > 15) refuse("$where keeps the column's parts in v$list, past what the hook's call can name")
    if (made in render.jumpTargets()) refuse("a jump enters $where where it makes its column, so the hook there would be skipped")
    return made to list
}

/**
 * Makes the extension's line a subtitle state, fills the extension's three stubs, adds the
 * subtitle maker that takes its state, and puts in the two hooks.
 *
 * The added maker is the title's own with its marker left out, so that marker still names one
 * method, and with the read of the reel's state replaced by a move of the new last parameter into
 * the same register. It has one more register than the original, which is where that parameter
 * lands, so every other register keeps its number.
 *
 * In the pick, in front of everything, the state goes to [TIME_LINE_TEXT]. Null is a state of
 * Instagram's, and the pick runs as it did. Words are the line's, and the pick answers Instagram's
 * subtitle text made from them: the listener from [NO_CLICK], the subtitle's own config and
 * session, the colors the pick was handed, no width, no extra object, no icon and the flag off.
 * The hook uses the first ten locals, which hold nothing yet at the first instruction.
 *
 * In the title, in front of the column's constructor, the title and the list of parts go to
 * [ADD_TIME_LINE], which adds the line to the list or leaves it alone.
 */
internal fun BytecodePatchContext.showReelTime(sites: ReelTimeSites) {
    val title = sites.title
    val state = sites.state

    // The class keeps its interfaces in a list of its own, which is the one its dex is written from.
    val implemented = mutableClassDefBy(REEL_TIME_LINE).interfaces as MutableList<String>
    if (state !in implemented) implemented.add(state)

    val extension = mutableClassDefBy(REEL_POST_TIME)
    fun fill(name: String, registers: Int, body: String) {
        val (_, takes, returns) = STUBS.single { it.first == name }
        val stub = extension.methods.single { it.isStatic(name, takes, returns) }
        extension.methods.remove(stub)
        extension.methods.add(replaced(stub, registers, body))
    }
    val reelType = sites.reel.type
    val itemType = sites.item.type
    // Answers an Object, so its ways out may meet at one return: null for anything that isn't the
    // title, and for a title with no reel, item or post.
    fill(
        "media", 2,
        """
            instance-of v0, p0, $title
            if-nez v0, :title
            const/4 v0, 0x0
            return-object v0
            :title
            check-cast p0, $title
            iget-object v0, p0, $title->${sites.reel.name}:$reelType
            if-eqz v0, :none
            iget-object v0, v0, $reelType->${sites.item.name}:$itemType
            if-eqz v0, :none
            iget-object v0, v0, $itemType->${sites.media.name}:$MEDIA
            :none
            return-object v0
        """,
    )
    fill(
        "takenAt", 1,
        """
            check-cast p0, $MEDIA
            invoke-virtual { p0 }, $MEDIA->${sites.takenAt.name}()$LONG
            move-result-object p0
            return-object p0
        """,
    )
    fill(
        "component", 3,
        """
            check-cast p0, $title
            check-cast p1, $state
            const/4 v0, 0x0
            invoke-virtual { p0, v0, p1 }, $title->$TIME_LINE_MAKER(Z$state)${sites.subtitle}
            move-result-object v0
            return-object v0
        """,
    )

    val maker = sites.maker
    val copied = maker.code().filterIndexed { at, _ -> at != sites.markerAt && at != sites.markerAt + 1 }
    val stateAt = sites.stateAt - if (sites.stateAt > sites.markerAt) 2 else 0
    mutableClassDefBy(title).methods.add(
        ImmutableMethod(
            title, TIME_LINE_MAKER,
            listOf(ImmutableMethodParameter("Z", null, null), ImmutableMethodParameter(state, null, null)),
            maker.returnType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, maker.hiddenApiRestrictions,
            ImmutableMethodImplementation(maker.implementation!!.registerCount + 1, copied, null, null),
        ).toMutable().apply { replaceInstruction(stateAt, "move-object/from16 v${sites.stateRegister}, p2") },
    )

    val pick = mutable(sites.pick)
    val init = "${sites.iconText}-><init>(${sites.iconTextTakes.joinToString("")})V"
    pick.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${sites.pick.parameterRegister(sites.stateParameter)}
            invoke-static { v0 }, $TIME_LINE_TEXT
            move-result-object v5
            if-eqz v5, :instagram
            invoke-static {}, $NO_CLICK
            move-result-object v1
            move-object/from16 v3, p0
            iget-object v2, v3, ${sites.subtitle}->${sites.config.name}:$VIEWER_CONFIG
            iget-object v3, v3, ${sites.subtitle}->${sites.session.name}:$USER_SESSION
            move-object/from16 v4, ${sites.pick.parameterRegister(sites.colorsParameter)}
            const/4 v6, 0x0
            const/4 v7, 0x0
            const/4 v8, 0x0
            const/4 v9, 0x0
            new-instance v0, ${sites.iconText}
            invoke-direct/range { v0 .. v9 }, $init
            return-object v0
        """,
        ExternalLabel("instagram", pick.getInstruction(0)),
    )

    mutable(sites.render).addInstructions(
        sites.columnAt,
        """
            move-object/from16 v${sites.scratch}, p0
            invoke-static { v${sites.scratch}, v${sites.list} }, $ADD_TIME_LINE
        """,
    )
}

/** The methods holding each of [names] as a marker, read from the classes the index says hold one. */
private fun BytecodePatchContext.marked(vararg names: String): Map<String, List<Method>> {
    val wanted = names.toSet()
    val found = mutableMapOf<String, MutableList<Method>>()
    for (type in typesMarked(*names).sorted()) {
        val classDef = classDefByOrNull(type) ?: continue
        classDef.methods.forEach { method ->
            method.markers().filter { it in wanted }.distinct().forEach { found.getOrPut(it) { mutableListOf() } += method }
        }
    }
    return found
}

/** [type], its superclasses as far as this APK defines them, and the interfaces of each. */
private fun BytecodePatchContext.ancestors(type: String): Set<String> {
    val found = linkedSetOf<String>()
    var next: String? = type
    while (next != null && found.add(next)) {
        val classDef = classDefByOrNull(next) ?: break
        found += classDef.interfaces
        next = classDef.superclass
    }
    return found
}

private fun BytecodePatchContext.mutable(found: Method): MutableMethod =
    mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.returnType == found.returnType && it.parameters() == found.parameters()
    }

/** This class's one instance field of [type], which the patch calls its [what]. */
private fun ClassDef.only(type: String, what: String): Field {
    val found = fields.filter { it.type == type && !AccessFlags.STATIC.isSet(it.accessFlags) }
    return found.singleOrNull() ?: refuse("expected ${this.type} to keep one $what of type $type, found ${found.map { it.name }}")
}

/**
 * Whether this method reads [type]'s int [field] and branches on it being zero before doing
 * anything else with it, which is how the subtitle text draws no icon for no icon.
 */
private fun Method.branchesOnZero(type: String, field: String): Boolean {
    val code = code()
    return code.indices.any { at ->
        val read = code[at].field()
        if (code[at].opcode != Opcode.IGET || read == null || read.definingClass != type || read.name != field) return@any false
        val register = (code[at] as TwoRegisterInstruction).registerA
        for (next in at + 1 until minOf(at + 4, code.size)) {
            val instruction = code[next]
            if (instruction.opcode == Opcode.IF_EQZ || instruction.opcode == Opcode.IF_NEZ) {
                return@any (instruction as OneRegisterInstruction).registerA == register
            }
            if (instruction.writes(register)) return@any false
        }
        false
    }
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: refuse("${describe()} has no body")

private fun Method.parameters(): List<String> = parameterTypes.map(Any::toString)

private fun Method.isStatic(name: String, parameters: List<String>, returns: String) =
    this.name == name && AccessFlags.STATIC.isSet(accessFlags) && returnType == returns && parameters() == parameters

private fun Method.describe() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Whether this call is one to [method], by its class, name, parameters and what it answers. */
private fun MethodReference.matches(method: Method) = definingClass == method.definingClass && name == method.name &&
    returnType == method.returnType && parameterTypes.map(Any::toString) == method.parameters()

private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.type() = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

/** Whether this instruction writes [register], a wide write's second half included. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/** The registers an invoke passes, the receiver first. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** How many registers a parameter of this type takes. */
private fun String.width() = if (this == "J" || this == "D") 2 else 1

/** [stub] with its body replaced by [body], in [registers] registers. */
private fun replaced(stub: Method, registers: Int, body: String): MutableMethod =
    ImmutableMethod(
        stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, null,
        stub.hiddenApiRestrictions, ImmutableMethodImplementation(registers, emptyList(), null, null),
    ).toMutable().apply { addInstructionsWithLabels(0, body) }
