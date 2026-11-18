/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.captionBridges
import app.morphe.patches.instagram.download.carouselBridge
import app.morphe.patches.instagram.download.coverBridges
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.download.playerQueriesPatch
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.reel.ROW_ICON
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.reel.downloadCheck
import app.morphe.patches.instagram.download.reel.newOption
import app.morphe.patches.instagram.download.reel.optionIcon
import app.morphe.patches.instagram.download.reel.typesLoadingDownload
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.media.quality.target
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Download any video"

private const val VIDEO_DOWNLOAD = "$EXTENSION_PACKAGE/download/VideoDownload;"
internal const val OFFER_VIDEO = "$VIDEO_DOWNLOAD->offer(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val SAVE_VIDEO = "$VIDEO_DOWNLOAD->save(Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)Z"
internal const val ALLOW_VIDEO = "$VIDEO_DOWNLOAD->allow(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"
internal const val OFFER_ALL = "$VIDEO_DOWNLOAD->offerAll(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val SAVE_ALL = "$VIDEO_DOWNLOAD->saveAll(Ljava/lang/Object;Landroid/app/Activity;)V"
internal const val ALL_OPTION = "$VIDEO_DOWNLOAD->allOption()Ljava/lang/Object;"
internal const val OWN_POST = "$VIDEO_DOWNLOAD->ownPost(ILjava/lang/Object;)I"
internal const val OWN_POST_ROW = "$VIDEO_DOWNLOAD->ownPostRow(ILjava/lang/Object;)I"
internal const val OFFER_PLAYER = "$VIDEO_DOWNLOAD->offerPlayer(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val PLAYER_OPTION = "$VIDEO_DOWNLOAD->playerOption()Ljava/lang/Object;"
internal const val PLAY_VIDEO = "$VIDEO_DOWNLOAD->play(Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)V"
internal const val OFFER_COVER = "$VIDEO_DOWNLOAD->offerCover(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val COVER_OPTION = "$VIDEO_DOWNLOAD->coverOption()Ljava/lang/Object;"
internal const val SAVE_COVER = "$VIDEO_DOWNLOAD->saveCover(Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)V"
internal const val CHOOSE_OPTION = "$VIDEO_DOWNLOAD->chooseOption()Ljava/lang/Object;"
internal const val CHOOSE_PAGES = "$VIDEO_DOWNLOAD->choosePages(Ljava/lang/Object;Landroid/app/Activity;)V"
internal const val OFFER_LOOP = "$VIDEO_DOWNLOAD->offerLoop(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val LOOP_OPTION = "$VIDEO_DOWNLOAD->loopOption()Ljava/lang/Object;"
internal const val TOGGLE_LOOP = "$VIDEO_DOWNLOAD->loop(Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)V"

private const val POST_INFO = "$EXTENSION_PACKAGE/download/PostInfo;"
internal const val OFFER_DETAILS = "$POST_INFO->offer(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val DETAILS_OPTION = "$POST_INFO->option()Ljava/lang/Object;"
internal const val SHOW_DETAILS = "$POST_INFO->show(Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)V"

/** The options the short feed menu's list of kept options reads first and last: "Why you're seeing this" and Report. */
internal const val WHY_OPTION = "$OPTION->WHY_AM_I_SEEING_THIS:$OPTION"
internal const val REPORT_OPTION = "$OPTION->REPORT:$OPTION"
private const val LIST = "Ljava/util/List;"

/** The name Instagram's build keeps, in a static field, for the class that runs a feed post's menu. */
internal const val FEED_HELPER_NAME = "MediaOptionsOverflowHelper"
private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val CONTEXT = "Landroid/content/Context;"
private const val GET_RESOURCES = "Landroid/content/Context;->getResources()Landroid/content/res/Resources;"
private const val GET_STRING = "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;"
private const val RESOURCES = "Landroid/content/res/Resources;"
private const val STRING = "Ljava/lang/String;"

/**
 * The bridges this patch writes: the post a menu is for, Instagram's Download row, the post's feed
 * state and the carousel page that state says is on screen. A carousel's pages are written by
 * [carouselBridge], which Download on reels shares.
 */
private const val FEED_MENU_MEDIA = "feedMenuMedia"
private const val ADD_DOWNLOAD_ROW = "addDownloadRow"
private const val FEED_MENU_ITEM_STATE = "feedMenuItemState"
private const val CAROUSEL_INDEX = "carouselIndex"

/** Instagram's helpers on a Media, a class that keeps its name, among them the one answering a carousel's page. */
internal const val MEDIA_EXT = "Lcom/instagram/feed/media/MediaExtKt;"
private const val CAROUSEL_FIELD = "carousel_media"

/**
 * Download in the menu of anyone's feed post with a video, saving through HushGram's own pipeline.
 *
 * Instagram 449's feed menu builder splits on whose post it is. Your own post goes past the
 * download check, and a flag of the menu's state with a server flag, to Instagram's own Download
 * row, and a tap on it saves a copy with a watermark. With both flags on, Instagram offers its
 * download in the share sheet instead. Anyone else's post jumps past that row to rows of its own,
 * so it never gets Download. On your own post this patch asks the extension at the check and at
 * the state's flag, which keep the row whenever a tap would save (see [OwnPost]). Where anyone
 * else's rows start it asks the extension, which adds the same row, built the way Instagram
 * builds it, to a post with a video. A tap on Download saves the video from the addresses its Media already holds,
 * the way Hushfacebook's Download any video does.
 *
 * A carousel's Media has no video of its own, only its pages. The post's feed state keeps which
 * page is on screen, and the menu holds that state, so the extension reads the page there and
 * offers, and saves, the page when it's a video.
 *
 * With its second switch on, a post or carousel page without a video gets the same row, and a tap
 * saves its picture at the largest size, through the picture bridges Download stories uses.
 *
 * Most of the feed now opens a short menu instead ("Why you're seeing this", Interested, Not
 * interested, Report, under an "About this reel" summary on a reel). It shows only the rows whose
 * option is on a fixed list, in that list's order, so it dropped the Download row. The list goes
 * through the extension before it's returned, which puts Download first while the switch is on.
 *
 * Download cover, with its own switch under the video one, is a row of the same kind as Save all,
 * offered right after it and made with its own option, which the handler hands to the extension
 * with the post and its feed state. A tap saves the still picture a video shows before it plays,
 * as Download cover on a reel does. Open in another player is one more row of that kind, offered
 * after it, and Details, with its own switch, comes last. Choose pages, with its own switch, is
 * offered by the extension with Save all, and its tap is checked after the current page's Download,
 * so the checks before it keep their places; it hands the post and the activity over as Save all does.
 *
 * Hold to download, with its own switch, hands every touch on a feed post's media frame and on a
 * reel to the extension first ([addHoldToDownload]), which saves through the same Download when two
 * fingers (or three) stay still on the post for the chosen time.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val downloadVideoPatch = bytecodePatch(
    name = "Download any video",
    description = "Adds Download to video posts in your feed, saving the video without Instagram's watermark. " +
        "Other switches add Download for photos, Download cover, a Details row with the post's time and " +
        "poster, a download icon beside Save under each post, and Hold to download, which saves a post or reel " +
        "you hold two fingers on. Download all waits its turn, with up to ten posts queued, so two posts' pages " +
        "never mix in your gallery. Starts off. Turn it on in HushGram settings > Downloads.",
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch, playerQueriesPatch)

    execute {
        requireStatusMethod("videoDownload")
        val page = offerDownloadOnEveryVideo()
        val button = findFeedButtonSite(page)
        val litho = findLithoSaveSite(button, page)
        val hold = findHoldSites(page.index.definingClass)
        addFeedDownloadButton(button)
        addLithoDownloadButton(litho)
        addHoldToDownload(hold)
        addFeedPostRows(page)
        enableStatus("videoDownload")
    }
}

/**
 * What the patch found of the feed menu's builder. Anyone else's rows start at [at], where [rows]
 * holds the list of rows. Instagram adds its own Download row with [adder], a static method of the
 * builder's state, of [stateType], passing the row kind [kind] and a label read by resource id
 * [label] through the state's [context].
 */
internal class OthersRow(
    val at: Int,
    val rows: Int,
    val stateType: String,
    val adder: MethodReference,
    val kind: FieldReference,
    val context: FieldReference,
    val label: Int,
)

/**
 * How [OthersRow.adder] makes a row, which the patch's own rows copy without calling it: a [type]
 * made with [constructor], its [option], [label], [kind] and [flag] fields set, the static [fence]
 * Redex writes [fenceValue] to after them when the adder does, and the row put on the list with
 * [add], called by [addCall].
 */
internal class RowShape(
    val type: String,
    val constructor: MethodReference,
    val option: FieldReference,
    val label: FieldReference,
    val kind: FieldReference,
    val flag: FieldReference,
    val fence: FieldReference?,
    val fenceValue: Int,
    val add: MethodReference,
    val addCall: String,
)

/**
 * The feed menu's pieces every row added to it needs: its helper, Instagram's download check, the
 * builder that adds rows, where anyone else's rows start in it, the page reads and the lists of
 * kept options for the short menu. Hide posts from this account finds them the same way.
 *
 * The extension is handed the helper as the menu. From 455 the builder's state holds no post, but
 * the helper answers it with [media] and keeps the state in [state]. The builder reads the helper
 * off its lambda, which comes in [lambda] and keeps it in the final [lambdaField], so a hook reads
 * it there again ([helperAt]).
 */
internal class FeedMenu(
    val helper: ClassDef,
    val eligible: Method,
    val builder: Method,
    val others: OthersRow,
    val outlined: (MethodReference) -> Method?,
    val pageReads: List<PageRead>,
    val shortLists: List<Method>,
    val lambda: Int,
    val lambdaField: FieldReference,
    val state: FieldReference,
    val media: Method,
    val row: RowShape,
)

/** Finds [FeedMenu] without changing anything. */
internal fun BytecodePatchContext.findFeedMenu(): FeedMenu {
    val helpers = mutableListOf<ClassDef>()
    val loaders = mutableListOf<Method>()
    val shortLists = mutableListOf<Method>()
    val pageReads = mutableListOf<PageRead>()
    val loading = typesLoadingDownload()
    val paging = classesCallingInto(MEDIA_EXT).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.originalName() == FEED_HELPER_NAME) helpers += classDef
        val type = classDef.type
        classDef.methods.forEach { method ->
            if (type in loading && method.code().any { it.opcode == Opcode.SGET_OBJECT && it.referenceText() == DOWNLOAD }) loaders += method
            if (method.isShortMenuList()) shortLists += method
            if (type in paging) pageReads += method.pageReads()
        }
    }
    val helper = helpers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class named $FEED_HELPER_NAME, found " + if (helpers.isEmpty()) "none" else helpers.joinToString { it.type },
    )
    val eligible = downloadCheck(PATCH)
    // The builder adds Download with a static method of its state, which the menu keeps a field of.
    // R8 merges the builder into a different method in each build, and whether that method also
    // touches the menu itself varies (450's 385611395 build never does), so the state is what counts.
    val states = helper.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.mapTo(HashSet()) { it.type }
    val builders = loaders.filter { it.calls(eligible) && it.addsRowOfAStateIn(states) }
    val builder = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one builder of the feed menu adding Download after the download check, found " +
            if (builders.isEmpty()) "none" else builders.joinToString { "${it.definingClass}->${it.name}" },
    )
    val outlined = { call: MethodReference -> classDefByOrNull(call.definingClass)?.methods?.firstOrNull { it.isTargetOf(call) } }
    val others = builder.othersRow(eligible, outlined)
    // The extension's bridges cast the menu to the helper and read it from outside its package.
    if (!AccessFlags.PUBLIC.isSet(helper.accessFlags)) throw PatchException("$PATCH: ${helper.type}, the feed menu's helper, isn't public")
    val (lambda, lambdaField) = helperField(builder, helper)
    val stateFields = helper.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == others.stateType }
    val state = stateFields.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one ${others.stateType} field in ${helper.type}, the builder's state, found ${stateFields.size}",
    )
    if (!AccessFlags.PUBLIC.isSet(state.accessFlags)) throw PatchException("$PATCH: ${helper.type}->${state.name}, the builder's state, isn't public")
    val media = postGetter(helper)
    if (!AccessFlags.PUBLIC.isSet(media.accessFlags)) throw PatchException("$PATCH: ${helper.type}->${media.name}, the post's getter, isn't public")
    val row = rowShape(others)
    if (!publicField(others.context, static = false)) {
        throw PatchException("$PATCH: ${others.context}, the builder's context, isn't a public field of a public class")
    }
    if (!publicField(others.kind, static = true)) {
        throw PatchException("$PATCH: ${others.kind}, the Download row's kind, isn't a public static of a public class")
    }
    return FeedMenu(
        helper, eligible, builder, others, outlined, pageReads, shortLists, lambda, lambdaField,
        ImmutableFieldReference(state.definingClass, state.name, state.type), media, row,
    )
}

/**
 * The register [builder] gets its lambda in, its own first parameter, and the one field of that
 * lambda it reads [helper] from and casts it to straight after. R8 merged the builder into the
 * lambda, so the field is the lambda's and final, and every read of it answers the helper the
 * menu is built for.
 */
private fun BytecodePatchContext.helperField(builder: Method, helper: ClassDef): Pair<Int, FieldReference> {
    val where = "${builder.definingClass}->${builder.name}"
    val static = AccessFlags.STATIC.isSet(builder.accessFlags)
    val lambdaType = if (static) builder.parameterTypes.firstOrNull()?.toString() else builder.definingClass
    val code = builder.code()
    val fields = code.indices.mapNotNull { index ->
        val read = code.getOrNull(index - 1) ?: return@mapNotNull null
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
        field.takeIf {
            code[index].opcode == Opcode.CHECK_CAST && code[index].referenceText() == helper.type && read.opcode == Opcode.IGET_OBJECT &&
                field.definingClass == lambdaType && (read as TwoRegisterInstruction).registerA == (code[index] as OneRegisterInstruction).registerA
        }
    }.distinctBy { it.toString() }
    val field = fields.singleOrNull() ?: throw PatchException(
        "$PATCH: $where reads ${helper.type} out of ${fields.size} fields of its lambda $lambdaType, not one",
    )
    val declared = classDefByOrNull(field.definingClass)?.fields?.firstOrNull { it.name == field.name && it.type == field.type }
    if (declared == null || AccessFlags.STATIC.isSet(declared.accessFlags) || !AccessFlags.FINAL.isSet(declared.accessFlags)) {
        throw PatchException("$PATCH: $field, where $where finds the menu's helper, isn't a final field of its lambda")
    }
    val registers = builder.implementation?.registerCount ?: 0
    val words = builder.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } + if (static) 0 else 1
    return registers - words to field
}

/**
 * Smali that puts the menu's helper in a free register in front of instruction [index] of [builder]
 * as its code is now, and that register: one no path from there reads before writing, under v16,
 * and none of [keep] or the lambda's. No write of the lambda's register may reach [index], so the
 * read there is of the lambda the builder was called with.
 */
internal fun FeedMenu.helperAt(builder: Method, index: Int, keep: Collection<Int> = emptyList()): Pair<Int, String> {
    val where = "${builder.definingClass}->${builder.name}"
    val flow = try {
        ControlFlow.of(builder)
    } catch (unreadable: IllegalArgumentException) {
        throw PatchException("$PATCH: $where has control flow this patch can't follow: ${unreadable.message}")
    }
    val code = flow.instructions
    val reached = BooleanArray(code.size)
    val queue = ArrayDeque<Int>()
    code.indices.filter { code[it].writes(lambda) }.forEach { queue += flow.normal[it] + flow.exceptional[it] }
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (reached[next]) continue
        reached[next] = true
        queue += flow.normal[next] + flow.exceptional[next]
    }
    if (reached[index]) throw PatchException("$PATCH: in $where a write of the lambda's v$lambda reaches instruction $index")
    val live = RegisterLiveness.of(builder).liveInto(index)
    val free = (0..15).firstOrNull { it !in live && it !in keep && it != lambda }
        ?: throw PatchException("$PATCH: in $where no register under v16 is free at instruction $index")
    return free to "move-object/from16 v$free, v$lambda\niget-object v$free, v$free, $lambdaField"
}

/** Writes [bridge], an extension stub taking the menu and answering its post, to ask the helper for the post. */
internal fun FeedMenu.postBridge(bridge: MutableMethod) {
    val type = helper.type
    bridge.addInstructions(
        0,
        """
            check-cast p0, $type
            invoke-static { p0 }, $type->${media.name}($type)$MEDIA
            move-result-object p0
            return-object p0
        """,
    )
}

/**
 * How the adder makes its row ([RowShape]): the instance its first add call puts on the list, made
 * with a constructor taking nothing, has its four fields set from the option, the label, the kind
 * and the flag it's handed. The row class and its fields are public, and so is any static Redex
 * writes before the add, since the patch's own rows are made the same way from another class.
 */
private fun BytecodePatchContext.rowShape(others: OthersRow): RowShape {
    val adder = others.adder
    val where = "${adder.definingClass}->${adder.name}"
    val body = classDefByOrNull(adder.definingClass)?.methods?.firstOrNull { it.isTargetOf(adder) }?.code()
        ?: throw PatchException("$PATCH: $where, the Download row's adder, isn't in the app")
    // 455's adder makes other objects too, so the row is the one the add call is handed.
    val add = body.indices.firstOrNull { body[it].opcode in ADD_CALLS && body[it].methodReference()?.name == "add" }
        ?: throw PatchException("$PATCH: $where never adds its row to the list")
    val addReference = body[add].methodReference()!!
    val row = body[add].argumentRegisters().getOrNull(1)
    if (row == null || addReference.parameterTypes.map(Any::toString) != listOf("Ljava/lang/Object;")) {
        throw PatchException("$PATCH: $where adds something other than one row to the list")
    }
    val made = (add - 1 downTo 0).firstOrNull { body[it].writes(row) }?.takeIf { body[it].opcode == Opcode.NEW_INSTANCE }
        ?: throw PatchException("$PATCH: $where adds a row it didn't make right before")
    val type = body[made].referenceText()!!
    val constructor = body.getOrNull(made + 1)?.takeIf {
        it.opcode == Opcode.INVOKE_DIRECT && it.argumentRegisters() == listOf(row) && it.methodReference()?.name == "<init>" &&
            it.methodReference()?.parameterTypes?.isEmpty() == true
    }?.methodReference() ?: throw PatchException("$PATCH: $where doesn't make its row $type with a constructor taking nothing")
    val rowClass = classDefByOrNull(type) ?: throw PatchException("$PATCH: $type, the feed menu's row, isn't in the app")
    val fields = rowClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
    if (!AccessFlags.PUBLIC.isSet(rowClass.accessFlags) || fields.any { !AccessFlags.PUBLIC.isSet(it.accessFlags) } ||
        (constructor.definingClass != type && constructor.definingClass != rowClass.superclass)
    ) {
        throw PatchException("$PATCH: $type, the feed menu's row, isn't public with public fields and its constructor")
    }
    val sets = (made + 2 until add).mapNotNull { index ->
        val field = (body[index].takeIf { it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.IPUT_BOOLEAN } as? ReferenceInstruction)
            ?.reference as? FieldReference
        field?.takeIf { (body[index] as TwoRegisterInstruction).registerB == row && it.definingClass == type }
    }
    fun set(fieldType: String) = sets.singleOrNull { it.type == fieldType } ?: throw PatchException(
        "$PATCH: $where doesn't set one $fieldType field of its row",
    )
    val option = set(OPTION)
    val label = set(CHAR_SEQUENCE)
    val kind = set(others.kind.type)
    val flag = set("Z")
    if (fields.size != 4 || sets.size != 4) throw PatchException("$PATCH: $type, the feed menu's row, has fields this patch doesn't know")
    val fences = (made + 2 until add).filter { body[it].opcode == Opcode.SPUT }
    if (fences.size > 1) throw PatchException("$PATCH: $where writes ${fences.size} statics before it adds its row")
    val fence = fences.singleOrNull()
    val fenceValue = fence?.let { at ->
        val register = (body[at] as OneRegisterInstruction).registerA
        (at - 1 downTo made).firstOrNull { body[it].writes(register) }?.let { body[it] as? NarrowLiteralInstruction }?.narrowLiteral
            ?: throw PatchException("$PATCH: $where writes a static that isn't a constant before it adds its row")
    } ?: 0
    val fenceField = fence?.let { (body[it] as ReferenceInstruction).reference as FieldReference }
    if (fenceField != null && (fenceField.type != "I" || !publicField(fenceField, static = true))) {
        throw PatchException("$PATCH: $fenceField, the static $where writes before it adds its row, isn't a public int of a public class")
    }
    return RowShape(
        type, constructor, option, label, kind, flag, fenceField,
        fenceValue, addReference, if (body[add].opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual",
    )
}

private val ADD_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)

/** Whether [field] is declared, public, in a public class, so a bridge in the extension may read or write it. */
private fun BytecodePatchContext.publicField(field: FieldReference, static: Boolean): Boolean {
    val owner = classDefByOrNull(field.definingClass) ?: return false
    return AccessFlags.PUBLIC.isSet(owner.accessFlags) && owner.fields.any {
        it.name == field.name && it.type == field.type && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            AccessFlags.STATIC.isSet(it.accessFlags) == static
    }
}

/** The feed menu's handler of a tapped option, the getter of the post it is for, and the field of its activity. */
internal class MenuHandler(val handler: Method, val media: Method, val activity: Field)

/** Finds [MenuHandler] in [helper], the class that runs a feed post's menu. */
internal fun findMenuHandler(helper: ClassDef): MenuHandler {
    val type = helper.type
    val handlers = helper.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(OPTION)
    }
    val handler = handlers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one handler of a tapped option in $type, found ${handlers.size}")
    val media = postGetter(helper)
    val activities = helper.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == FRAGMENT_ACTIVITY }
    val activity = activities.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $FRAGMENT_ACTIVITY field in $type, found ${activities.size}")
    return MenuHandler(handler, media, activity)
}

/** The static getter of [helper] answering the post its menu is for. */
internal fun postGetter(helper: ClassDef): Method {
    val type = helper.type
    // Two static getters answer the post: one reads it, the other calls that one and throws on null.
    val getters = helper.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == MEDIA &&
            method.parameterTypes.map(Any::toString) == listOf(type) &&
            method.code().any { (it as? ReferenceInstruction)?.reference.let { field -> field is FieldReference && field.definingClass == type } }
    }
    return getters.singleOrNull()
        ?: throw PatchException("$PATCH: expected one getter reading the post in $type, found ${getters.size}")
}

internal fun BytecodePatchContext.offerDownloadOnEveryVideo(): PageIndex {
    val menuFound = findFeedMenu()
    val helper = menuFound.helper
    val eligible = menuFound.eligible
    val builder = menuFound.builder
    val outlined = menuFound.outlined
    val others = menuFound.others
    val pageReads = menuFound.pageReads
    val shortLists = menuFound.shortLists
    val batchAt = builder.batchRow(others, outlined)
    val own = builder.ownPost(eligible, others, outlined)
    // Each hook in the builder reads the menu's helper into a register free where it goes. The
    // hooks only add reads of registers already live there, so the builder as it is now settles it.
    val hooked = mutable(builder)
    val keep = listOf(others.rows, own.flag, own.answer)
    listOf(others.at, own.gate + 1, own.result + 1, batchAt).forEach { menuFound.helperAt(hooked, it, keep) }
    val icon = optionIcon(PATCH)
    val type = helper.type
    val tap = findMenuHandler(helper)
    val handler = tap.handler
    val media = tap.media
    val activity = tap.activity
    val carousel = pandoGetter(PATCH, MEDIA, CAROUSEL_FIELD, LIST)
    val page = pageIndex(pageReads, carousel, helper)
    val menu = mutable(handler)
    menu.requireLocals(PATCH, 3)
    val bridges = mutableClassDefBy(INSTAGRAM_MEDIA)
    fun bridge(name: String, returns: String) = bridges.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no static $returns $name(Object)")
    val postOf = bridge(FEED_MENU_MEDIA, "Ljava/lang/Object;")
    val itemStateOf = bridge(FEED_MENU_ITEM_STATE, "Ljava/lang/Object;")
    val indexOf = bridge(CAROUSEL_INDEX, "I")
    val rowStub = bridges.methods.singleOrNull {
        it.name == ADD_DOWNLOAD_ROW && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;", ARRAY_LIST)
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no static $ADD_DOWNLOAD_ROW(Object, ArrayList)")
    val allRowStub = bridges.methods.singleOrNull {
        it.name == "addSaveAllRow" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;", ARRAY_LIST, "Ljava/lang/Object;", CHAR_SEQUENCE)
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no Save all row stub")
    val optionStub = bridges.methods.singleOrNull {
        it.name == "saveAllOption" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.returnType == "Ljava/lang/Object;" && it.parameterTypes.isEmpty()
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no Save all option stub")
    val feedOptionStub = bridges.methods.singleOrNull {
        it.name == "feedOption" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.returnType == "Ljava/lang/Object;" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;")
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no static feedOption(String)")
    val shortList = shortLists.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one list of the options the short feed menu keeps, a static method taking a flag that reads " +
            "$WHY_OPTION and $REPORT_OPTION, found " +
            if (shortLists.isEmpty()) "none" else shortLists.joinToString { "${it.definingClass}->${it.name}" },
    )
    val kept = mutable(shortList)
    kept.requireLocals(PATCH, 2)
    val keptCode = kept.code()
    val returns = keptCode.indices.filter { keptCode[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) throw PatchException("$PATCH: ${kept.definingClass}->${kept.name} never returns its list")
    returns.map { (keptCode[it] as OneRegisterInstruction).registerA }.firstOrNull { it > 15 }?.let {
        throw PatchException("$PATCH: ${kept.definingClass}->${kept.name} returns its list in v$it, out of an invoke's reach")
    }
    val writeBridges = mediaBridges(PATCH)
    val writeImageBridges = imageBridges(PATCH)
    val writeCoverBridges = coverBridges(PATCH)
    val writeCarouselBridge = carouselBridge(PATCH)
    val writeCaptionBridges = captionBridges(PATCH)

    // Last return first, so the indices before it stay where they were.
    for (index in returns.reversed()) {
        val list = (keptCode[index] as OneRegisterInstruction).registerA
        val spare = if (list == 0) 1 else 0
        kept.addInstructionsAtControlFlowLabel(
            index,
            """
                sget-object v$spare, $DOWNLOAD
                invoke-static { v$list, v$spare }, $ALLOW_VIDEO
                move-result-object v$list
            """,
        )
    }

    // Highest index first, so the ones before it stay where they were.
    val rows = others.rows
    menuFound.helperAt(hooked, others.at, keep).let { (held, load) ->
        hooked.addInstructionsAtControlFlowLabel(others.at, "$load\ninvoke-static { v$held, v$rows }, $OFFER_VIDEO")
    }
    menuFound.helperAt(hooked, own.gate + 1, keep).let { (held, load) ->
        hooked.addInstructions(own.gate + 1, "$load\ninvoke-static { v${own.flag}, v$held }, $OWN_POST_ROW\nmove-result v${own.flag}")
    }
    menuFound.helperAt(hooked, own.result + 1, keep).let { (held, load) ->
        hooked.addInstructions(own.result + 1, "$load\ninvoke-static { v${own.answer}, v$held }, $OWN_POST\nmove-result v${own.answer}")
    }
    menuFound.helperAt(hooked, batchAt, keep).let { (held, load) ->
        val offers = listOf(OFFER_ALL, OFFER_COVER, OFFER_PLAYER, OFFER_DETAILS, OFFER_LOOP, OFFER_POST_ROWS)
        hooked.addInstructions(batchAt, load + offers.joinToString("") { "\ninvoke-static { v$held, v$rows }, $it" })
    }

    menu.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            invoke-static {}, $ALL_OPTION
            move-result-object v1
            if-eqz v1, :player
            if-ne v0, v1, :player
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            invoke-static { v1, v2 }, $SAVE_ALL
            return-void
            :player
            invoke-static {}, $PLAYER_OPTION
            move-result-object v1
            if-eqz v1, :details
            if-ne v0, v1, :details
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $PLAY_VIDEO
            return-void
            :details
            invoke-static {}, $DETAILS_OPTION
            move-result-object v1
            if-eqz v1, :cover
            if-ne v0, v1, :cover
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $SHOW_DETAILS
            return-void
            :cover
            invoke-static {}, $COVER_OPTION
            move-result-object v1
            if-eqz v1, :loop
            if-ne v0, v1, :loop
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $SAVE_COVER
            return-void
            :loop
            invoke-static {}, $LOOP_OPTION
            move-result-object v1
            if-eqz v1, :current
            if-ne v0, v1, :current
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $TOGGLE_LOOP
            return-void
            :current
            move-object/from16 v0, p1
            sget-object v1, $DOWNLOAD
            if-ne v0, v1, :choose
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $SAVE_VIDEO
            move-result v0
            if-eqz v0, :handle
            return-void
            :choose
            invoke-static {}, $CHOOSE_OPTION
            move-result-object v1
            if-eqz v1, :handle
            if-ne v0, v1, :handle
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            invoke-static { v1, v2 }, $CHOOSE_PAGES
            return-void
        """,
        ExternalLabel("handle", menu.getInstruction(0)),
    )

    menuFound.postBridge(postOf)
    itemStateOf.addInstructions(
        0,
        """
            check-cast p0, $type
            iget-object p0, p0, ${page.menuState}
            return-object p0
        """,
    )
    indexOf.addInstructions(
        0,
        """
            check-cast p0, ${page.index.definingClass}
            iget p0, p0, ${page.index}
            return p0
        """,
    )
    bridges.methods.remove(rowStub)
    bridges.methods.add(downloadRow(rowStub, menuFound))
    bridges.methods.remove(allRowStub)
    bridges.methods.add(downloadRow(allRowStub, menuFound, all = true))
    bridges.methods.remove(optionStub)
    bridges.methods.add(ImmutableMethod(
        optionStub.definingClass, optionStub.name, optionStub.parameters, optionStub.returnType,
        optionStub.accessFlags, optionStub.annotations, optionStub.hiddenApiRestrictions,
        ImmutableMethodImplementation(4, emptyList(), null, null),
    ).toMutable().apply {
        addInstructions(0, newOption(icon, "const-string v1, \"HUSHGRAM_SAVE_ALL\""))
    })
    bridges.methods.remove(feedOptionStub)
    bridges.methods.add(ImmutableMethod(
        feedOptionStub.definingClass, feedOptionStub.name, feedOptionStub.parameters, feedOptionStub.returnType,
        feedOptionStub.accessFlags, feedOptionStub.annotations, feedOptionStub.hiddenApiRestrictions,
        ImmutableMethodImplementation(5, emptyList(), null, null),
    ).toMutable().apply {
        addInstructions(0, newOption(icon, "move-object v1, p0", iconByName = ROW_ICON))
    })
    writeBridges()
    writeImageBridges()
    writeCoverBridges?.invoke()
    writeCarouselBridge()
    writeCaptionBridges?.invoke()
    return page
}

/**
 * [stub] with a body that adds Instagram's Download row to its list the way the builder's adder
 * makes it ([RowShape]), without the adder's logging: the row with Download as its option, the
 * label the builder reads through its state's context, the builder's row kind and a false flag,
 * as the builder passes it for your own posts. The menu it's handed is the helper, which keeps the
 * builder's state. The batch bridge instead takes its separate option and localized title from the
 * extension.
 */
internal fun downloadRow(stub: Method, menu: FeedMenu, all: Boolean = false): MutableMethod {
    val row = menu.row
    val others = menu.others
    val option = if (all) {
        "check-cast p2, $OPTION\niput-object p2, v1, ${row.option}"
    } else {
        "sget-object v2, $DOWNLOAD\niput-object v2, v1, ${row.option}"
    }
    val label = if (all) {
        "iput-object p3, v1, ${row.label}"
    } else {
        listOf(
            "check-cast p0, ${menu.helper.type}",
            "iget-object v0, p0, ${menu.state}",
            "iget-object v2, v0, ${others.context}",
            "invoke-virtual { v2 }, $GET_RESOURCES",
            "move-result-object v2",
            "const v3, ${others.label}",
            "invoke-virtual { v2, v3 }, $GET_STRING",
            "move-result-object v2",
            "iput-object v2, v1, ${row.label}",
        ).joinToString("\n")
    }
    val fence = row.fence?.let { "const v2, ${row.fenceValue}\nsput v2, $it" }
    val body = listOfNotNull(
        "new-instance v1, ${row.type}",
        "invoke-direct { v1 }, ${row.constructor}",
        option,
        label,
        "sget-object v2, ${others.kind}",
        "iput-object v2, v1, ${row.kind}",
        "const/4 v2, 0x0",
        "iput-boolean v2, v1, ${row.flag}",
        fence,
        "${row.addCall} { p1, v1 }, ${row.add}",
        "return-void",
    )
    return ImmutableMethod(
        stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, stub.annotations,
        stub.hiddenApiRestrictions, ImmutableMethodImplementation(4 + if (all) 4 else 2, emptyList(), null, null),
    ).toMutable().apply {
        addInstructions(0, body.joinToString("\n"))
    }
}

/**
 * Where the batch rows go in [this], the feed menu's builder: right after the row list is made and
 * before the split by whose post it is, the first jump to anyone else's rows, so every post gets
 * them. The list is made in place, or by a static method R8 outlined that making into, which
 * [outlined] looks up, and nothing branches between it and the split.
 */
internal fun Method.batchRow(found: OthersRow, outlined: (MethodReference) -> Method? = { null }): Int {
    val code = code()
    val where = "$definingClass->$name"
    val split = code.indices.firstOrNull { code[it].jumps() && code.target(it) == found.at }
        ?: throw PatchException("$PATCH: $where never jumps to anyone else's rows")
    val list = (split - 1 downTo 0).firstOrNull { code[it].writes(found.rows) }
        ?: throw PatchException("$PATCH: $where makes no row list before it splits by whose post it is")
    val init = (list + 1 until split).firstOrNull {
        code[it].referenceText() == "$ARRAY_LIST-><init>()V" && code[it].argumentRegisters() == listOf(found.rows)
    }?.takeIf { code[list].opcode == Opcode.NEW_INSTANCE && code[list].referenceText() == ARRAY_LIST }
    val madeByOutline = code[list].opcode == Opcode.MOVE_RESULT_OBJECT && code.getOrNull(list - 1)?.let { call ->
        val reference = call.methodReference()
        call.opcode in STATIC_CALLS && reference != null && reference.returnType == ARRAY_LIST && reference.parameterTypes.isEmpty() &&
            outlined(reference)?.makesAnEmptyList() == true
    } == true
    val site = init?.plus(1) ?: (list + 1).takeIf { madeByOutline }
        ?: throw PatchException("$PATCH: in $where v${found.rows} isn't a new row list before the split by whose post it is")
    if ((list + 1 until split).any { code[it] is OffsetInstruction }) {
        throw PatchException("$PATCH: in $where something branches between the row list and the split by whose post it is")
    }
    return site
}

/** Whether [this] only makes an empty ArrayList and answers it, the way R8 outlines `new ArrayList()`. */
private fun Method.makesAnEmptyList(): Boolean {
    val code = code().filter { it.opcode != Opcode.NOP }
    if (!AccessFlags.STATIC.isSet(accessFlags) || code.size != 3) return false
    if (code[0].opcode != Opcode.NEW_INSTANCE || code[0].referenceText() != ARRAY_LIST) return false
    val list = (code[0] as OneRegisterInstruction).registerA
    return code[1].referenceText() == "$ARRAY_LIST-><init>()V" && code[1].argumentRegisters() == listOf(list) &&
        code[2].opcode == Opcode.RETURN_OBJECT && (code[2] as OneRegisterInstruction).registerA == list
}

/**
 * Whether [this] reads a string resource: Resources.getString, or a static (Resources, int) method
 * R8 outlined that read into, which [outlined] looks up and which hands its two parameters to
 * getString and answers what it gets back.
 */
private fun Instruction.readsLabel(outlined: (MethodReference) -> Method?): Boolean {
    val call = methodReference() ?: return false
    if (call.toString() == GET_STRING) return true
    if (opcode !in STATIC_CALLS || call.returnType != STRING || call.parameterTypes.map(Any::toString) != listOf(RESOURCES, "I")) return false
    val body = outlined(call) ?: return false
    val registers = body.implementation?.registerCount ?: return false
    val code = body.code().filter { it.opcode != Opcode.NOP }
    val read = code.indices.singleOrNull { code[it].referenceText() == GET_STRING } ?: return false
    val result = (code.getOrNull(read + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA ?: return false
    val last = code.last()
    return AccessFlags.STATIC.isSet(body.accessFlags) && code[read].argumentRegisters() == listOf(registers - 2, registers - 1) &&
        code.none { it is OffsetInstruction } && (read + 2 until code.size - 1).none { code[it].writes(result) } &&
        last.opcode == Opcode.RETURN_OBJECT && (last as OneRegisterInstruction).registerA == result
}

/**
 * Where anyone else's rows start in [this], the feed menu's builder, and how Instagram adds its
 * own Download row. The row is loaded once, and the first call after it that takes a Download
 * option, a label and the row list, declared by the state it also takes, adds it. Up to 455 a jump
 * right after ends the row, and the instruction after that jump starts anyone else's rows. From 456
 * the row runs straight on, and anyone else's rows start where the last jump before the download
 * check that goes past the row lands. Every jump there comes before the download check, so only
 * anyone else's posts reach it. The list there is
 * the one the first call after it that takes the state and a list is handed, and it doesn't change
 * on the way. The row's label may be read through a method R8 outlined getString into, which
 * [outlined] looks up.
 */
internal fun Method.othersRow(eligible: Method, outlined: (MethodReference) -> Method? = { null }): OthersRow {
    val code = code()
    val where = "$definingClass->$name"
    val row = code.indices.singleOrNull { code[it].opcode == Opcode.SGET_OBJECT && code[it].referenceText() == DOWNLOAD }
        ?: throw PatchException("$PATCH: $where doesn't load Download once")
    val check = code.indices.singleOrNull { code[it].calls(eligible) }
        ?: throw PatchException("$PATCH: $where doesn't call the download check once")
    val add = (row + 1 until code.size).firstOrNull { index ->
        val call = code[index].methodReference() ?: return@firstOrNull false
        val types = call.parameterTypes.map(Any::toString)
        code[index].opcode in STATIC_CALLS && call.returnType == "V" &&
            OPTION in types && CHAR_SEQUENCE in types && ARRAY_LIST in types && call.definingClass in types
    } ?: throw PatchException("$PATCH: $where never adds the Download row it loads")
    val adder = code[add].methodReference()!!
    val stateType = adder.definingClass
    val parameters = adder.parameterTypes.map(Any::toString)
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    fun target(index: Int) = address.indexOf(address[index] + (code[index] as OffsetInstruction).codeOffset)
    val at = if (code.getOrNull(add + 1)?.opcode in GOTOS) {
        add + 2
    } else {
        // From 456 the row runs straight on into the rest of your own post's rows, and anyone
        // else's start where the owner check skips to, past the row: the last jump before the
        // download check that goes there. In a merged lambda the other cases' jumps come first.
        val owner = (check - 1 downTo 0).firstOrNull { code[it].jumps() && target(it) > add } ?: throw PatchException(
            "$PATCH: in $where the Download row doesn't end in a jump, and nothing before the download check jumps past it",
        )
        target(owner)
    }

    val into = code.indices.filter { code[it].jumps() }.filter { target(it) == at }
    if (into.isEmpty() || into.any { it > check }) {
        throw PatchException("$PATCH: in $where the rows after Download aren't a branch taken before the download check")
    }

    val block = row..add
    val kinds = block.mapNotNull { index ->
        (code[index].takeIf { it.opcode == Opcode.SGET_OBJECT } as? ReferenceInstruction)?.reference as? FieldReference
    }.filter { it.type in parameters && it.type != OPTION }
    val kind = kinds.singleOrNull() ?: throw PatchException("$PATCH: in $where expected one row kind handed to the Download row, found ${kinds.size}")
    val contexts = block.mapNotNull { index ->
        (code[index].takeIf { it.opcode == Opcode.IGET_OBJECT } as? ReferenceInstruction)?.reference as? FieldReference
    }.filter { it.definingClass == stateType && it.type == CONTEXT }
    val context = contexts.singleOrNull() ?: throw PatchException("$PATCH: in $where expected one Context the Download row's label is read with")
    val read = block.singleOrNull { code[it].readsLabel(outlined) }
        ?: throw PatchException("$PATCH: in $where the Download row's label isn't read once")
    val id = code[read].argumentRegisters()[1]
    val label = (read - 1 downTo row).firstOrNull { code[it].writes(id) }?.let { code[it] as? NarrowLiteralInstruction }?.narrowLiteral
        ?: throw PatchException("$PATCH: in $where the Download row's label isn't a resource")
    // 455's adder also takes an object its logging reads after the row is added (LX/0YL1 on 386113969).
    val known = listOf(kind.type, OPTION, stateType, CHAR_SEQUENCE, ARRAY_LIST)
    val others = parameters.filter { it !in known && it != "Z" }
    if (known.any { type -> parameters.count { it == type } != 1 } || others.size > 1 || others.any { it[0] != 'L' && it[0] != '[' } ||
        parameters.count { it == "Z" } > 1
    ) {
        throw PatchException("$PATCH: in $where the Download row's adder takes $parameters, which this patch doesn't know")
    }

    val use = (at until code.size).firstOrNull { index ->
        val types = code[index].methodReference()?.parameterTypes?.map(Any::toString) ?: return@firstOrNull false
        code[index].opcode in STATIC_CALLS && stateType in types && ARRAY_LIST in types
    } ?: throw PatchException("$PATCH: in $where no row after Download takes the state and the list")
    val types = code[use].methodReference()!!.parameterTypes.map(Any::toString)
    val rows = code[use].argumentRegisters()[types.indexOf(ARRAY_LIST)]
    if (rows > 15 || (at until use).any { code[it].writes(rows) }) {
        throw PatchException("$PATCH: in $where the list v$rows doesn't reach anyone else's rows as it is")
    }
    return OthersRow(at, rows, stateType, adder, kind, context, label)
}

/** Whether [this] is an if or a goto, as opposed to a switch or an array fill, which point at a payload. */
private fun Instruction.jumps(): Boolean = this is OffsetInstruction && opcode !in PAYLOAD_READS

private val PAYLOAD_READS = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA)

/**
 * Where your own post's rows ask Instagram whether the post may be downloaded: [result], the
 * move-result of the builder's one call to the download check, holding the answer in [answer].
 * Instagram adds its Download row to your own post only on a yes, which a photo, and a video it
 * doesn't allow downloads of, never get (#57). Past a yes, [gate] reads a flag of the builder's
 * state into [flag], and a 0 there jumps to the row. With the flag and a server flag on, Instagram
 * offers its download in the share sheet and leaves the row out, which is what an account with
 * downloads allowed gets (#57 again).
 */
internal class OwnPost(val result: Int, val answer: Int, val gate: Int, val flag: Int)

/**
 * Finds [OwnPost] in [this], the feed menu's builder. Only your own posts reach the check (see
 * [othersRow]). A no jumps away at once. On a yes, the first branch is on a boolean of the
 * builder's state read without a branch in between, and it jumps ahead to the Download row on a 0
 * (from 456 that boolean goes through a check first, see [rowPastTheSheetCheck]). The hooks there
 * are handed the menu's helper, which answers the post: from 455 the check is handed a wrapper the
 * builder makes for it, and the builder's state holds no post.
 */
internal fun Method.ownPost(eligible: Method, found: OthersRow, outlined: (MethodReference) -> Method? = { null }): OwnPost {
    val code = code()
    val where = "$definingClass->$name"
    val check = code.indices.singleOrNull { code[it].calls(eligible) }
        ?: throw PatchException("$PATCH: $where doesn't call the download check once")
    val result = check + 1
    if (code.getOrNull(result)?.opcode != Opcode.MOVE_RESULT) throw PatchException("$PATCH: $where drops the download check's answer")
    val answer = (code[result] as OneRegisterInstruction).registerA
    if (code.getOrNull(result + 1)?.opcode != Opcode.IF_EQZ || (code[result + 1] as OneRegisterInstruction).registerA != answer) {
        throw PatchException("$PATCH: $where doesn't branch on the download check's answer right away")
    }
    val gate = (result + 2 until code.size).firstOrNull { code[it] is OffsetInstruction || code[it].readsFlagOf(found.stateType) }
        ?.takeIf { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: throw PatchException("$PATCH: past the download check's yes, $where reads no flag of the builder's state before it branches")
    val flag = (code[gate] as TwoRegisterInstruction).registerA
    val branch = (gate + 1 until code.size).firstOrNull { code[it] is OffsetInstruction || code[it].writes(flag) }
    val row = when {
        branch == null -> -1
        code[branch].opcode == Opcode.IF_EQZ && (code[branch] as OneRegisterInstruction).registerA == flag ->
            code.target(branch).takeIf { it > branch } ?: -1
        else -> rowPastTheSheetCheck(code, gate, flag, outlined)
    }
    if (row < 0 || row >= found.at || code[row].opcode != Opcode.SGET_OBJECT || code[row].referenceText() != DOWNLOAD) {
        throw PatchException("$PATCH: in $where the builder state's flag past the download check doesn't lead to the Download row on a 0")
    }
    if (answer > 15 || flag > 15) throw PatchException("$PATCH: in $where v$answer and v$flag are out of an invoke's reach")
    return OwnPost(result, answer, gate, flag)
}

/**
 * From 456 the flag isn't branched on. Right after [gate] reads it into [flag], it's the last thing
 * handed to a check answering whether the share sheet takes Download, whose yes jumps past the row
 * and whose no runs on into it. That check, which [outlined] looks up, answers no on a 0 flag before
 * asking anything else, so a 0 still keeps the row. Answers where the row starts, or -1.
 */
private fun rowPastTheSheetCheck(code: List<Instruction>, gate: Int, flag: Int, outlined: (MethodReference) -> Method?): Int {
    val asked = code.getOrNull(gate + 1)?.methodReference() ?: return -1
    if (asked.returnType != "Z" || asked.parameterTypes.lastOrNull()?.toString() != "Z" || code[gate + 1].argumentRegisters().lastOrNull() != flag) return -1
    val answer = (code.getOrNull(gate + 2)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction)?.registerA ?: return -1
    val skip = code.getOrNull(gate + 3)
    if (skip?.opcode != Opcode.IF_NEZ || (skip as OneRegisterInstruction).registerA != answer || code.target(gate + 3) <= gate + 4) return -1
    return if (outlined(asked)?.answersNoOnANo() == true) gate + 4 else -1
}

/**
 * Whether [this], which nothing overrides, answers 0 at once when its last parameter, a boolean, is
 * 0: its first branch is on that parameter and lands on a return of a 0 set before it.
 */
private fun Method.answersNoOnANo(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) && !AccessFlags.PRIVATE.isSet(accessFlags) && !AccessFlags.FINAL.isSet(accessFlags)) return false
    val registers = implementation?.registerCount ?: return false
    val code = code()
    val first = code.indexOfFirst { it is OffsetInstruction }
    if (first < 0 || code[first].opcode != Opcode.IF_EQZ || (code[first] as OneRegisterInstruction).registerA != registers - 1) return false
    val landed = code[code.target(first)]
    if (landed.opcode != Opcode.RETURN) return false
    val answer = (landed as OneRegisterInstruction).registerA
    val set = (first - 1 downTo 0).firstOrNull { code[it].writes(answer) } ?: return false
    return code[set].opcode in ZERO_LOADS && (code[set] as NarrowLiteralInstruction).narrowLiteral == 0
}

private val ZERO_LOADS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/** Whether [this] reads a boolean field of [stateType]. */
private fun Instruction.readsFlagOf(stateType: String): Boolean =
    opcode == Opcode.IGET_BOOLEAN && ((this as ReferenceInstruction).reference as FieldReference).definingClass == stateType

/** A call to [lookup], one of [MEDIA_EXT]'s (Media, int) methods answering a Media, handed an int read from [field], or null when it isn't one. */
internal class PageRead(val lookup: String, val field: FieldReference?)

/**
 * Every call in [this] to a static method of [MEDIA_EXT] taking a Media and an int and answering a
 * Media, with the int field read into that int just before, if that's where it came from.
 */
internal fun Method.pageReads(): List<PageRead> {
    val code = code()
    return code.indices.mapNotNull { at ->
        val call = code[at].methodReference() ?: return@mapNotNull null
        if (code[at].opcode !in STATIC_CALLS || call.definingClass != MEDIA_EXT || call.returnType != MEDIA ||
            call.parameterTypes.map(Any::toString) != listOf(MEDIA, "I")
        ) {
            return@mapNotNull null
        }
        val index = code[at].argumentRegisters()[1]
        val read = (at - 1 downTo maxOf(0, at - 4)).firstOrNull { code[it].writes(index) }
            ?.let { code[it].takeIf { instruction -> instruction.opcode == Opcode.IGET } as? ReferenceInstruction }
        PageRead(call.name, read?.reference as? FieldReference)
    }
}

/**
 * Where the menu finds the carousel page on screen: the int field [index] of the post's feed state,
 * which the menu holds in [menuState].
 */
internal class PageIndex(val index: FieldReference, val menuState: FieldReference)

/**
 * Finds which carousel page a feed post is showing. [MEDIA_EXT] has one static method taking a
 * Media and an int that reads [carousel], the carousel's pages, and answers the page. Instagram
 * hands it the page its feed state keeps nearly everywhere it's called, as an int field read just
 * before, among [reads]. Of those fields, only one on a class the menu, [helper], keeps one public
 * field of counts. It has to be read at least twice as often as the next such field, so a build
 * where it's unclear stops the patch rather than guess. The extension reads the feed state off the
 * menu, so that field has to be public.
 */
internal fun BytecodePatchContext.pageIndex(
    reads: List<PageRead>,
    carousel: Method,
    helper: ClassDef,
): PageIndex {
    val lookups = classDefBy(MEDIA_EXT).methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == MEDIA &&
            it.parameterTypes.map(Any::toString) == listOf(MEDIA, "I") && it.calls(carousel)
    }
    val lookup = lookups.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method on $MEDIA_EXT answering a carousel's page, found " +
            if (lookups.isEmpty()) "none" else lookups.joinToString { it.name },
    )
    fun menuState(type: String) = helper.fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
    val fields = reads.filter { it.lookup == lookup.name }.mapNotNull { it.field }
        .filter { it.type == "I" && menuState(it.definingClass) != null }
    val ranked = fields.groupBy { it.toString() }.values.sortedByDescending { it.size }
    val top = ranked.firstOrNull() ?: throw PatchException(
        "$PATCH: no call to ${lookup.definingClass}->${lookup.name} reads its page from a feed state the menu holds",
    )
    val next = ranked.getOrNull(1)
    if (next != null && top.size < 2 * next.size) {
        throw PatchException("$PATCH: the carousel page is either ${top.first()} (${top.size} reads) or ${next.first()} (${next.size} reads)")
    }
    val index = top.first()
    val state = menuState(index.definingClass)!!
    if (!AccessFlags.PUBLIC.isSet(state.accessFlags)) {
        throw PatchException("$PATCH: ${helper.type}->${state.name}, the post's feed state, isn't public")
    }
    return PageIndex(index, ImmutableFieldReference(state.definingClass, state.name, state.type))
}

/**
 * Whether [this] makes the list of options the short feed menu keeps: a static method taking a flag
 * and answering a list, reading "Why you're seeing this" and Report. The flag picks one of two
 * such lists, both made here, so every return gets the hook.
 */
internal fun Method.isShortMenuList(): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == LIST && parameterTypes.map(Any::toString) == listOf("Z") &&
        code().mapNotNull { it.referenceText() }.let { WHY_OPTION in it && REPORT_OPTION in it }

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is Instruction35c -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.calls(method: Method): Boolean {
    val reference = methodReference() ?: return false
    return reference.definingClass == method.definingClass && reference.name == method.name &&
        reference.returnType == method.returnType &&
        reference.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
}

private fun Method.calls(method: Method): Boolean = code().any { it.calls(method) }

/** Whether [this] is the method [call] names. */
private fun Method.isTargetOf(call: MethodReference): Boolean =
    definingClass == call.definingClass && name == call.name && returnType == call.returnType &&
        parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)

/**
 * Whether [this] adds a row the way the feed menu's builder adds Download: a static call returning
 * nothing, declared by one of [states] and taking it, with an option, a label and the row list.
 */
private fun Method.addsRowOfAStateIn(states: Set<String>): Boolean = code().any { instruction ->
    val call = instruction.methodReference() ?: return@any false
    val types = call.parameterTypes.map(Any::toString)
    instruction.opcode in STATIC_CALLS && call.returnType == "V" && call.definingClass in states && call.definingClass in types &&
        OPTION in types && CHAR_SEQUENCE in types && ARRAY_LIST in types
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

internal fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }
