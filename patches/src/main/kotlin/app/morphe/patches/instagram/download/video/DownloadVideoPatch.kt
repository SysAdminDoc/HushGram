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
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.download.playerQueriesPatch
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.ELIGIBLE_MARKER
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.reel.newOption
import app.morphe.patches.instagram.download.reel.optionIcon
import app.morphe.patches.instagram.download.reel.typesLoadingDownload
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.media.quality.target
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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
 * Open in another player is a row of the same kind as Save all, offered beside it and made with
 * its own option, which the handler hands to the extension with the post and its feed state.
 * Details, with its own switch, is one more row of that kind, offered after it.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val downloadVideoPatch = bytecodePatch(
    name = "Download any video",
    description = "Adds Download to the menu of a post in your feed with a video, and of a carousel showing a video. " +
        "Videos save at the Download quality you set, without Instagram's watermark. " +
        "A second switch does the same for photo posts. " +
        "Another adds Details, with the post's time, who posted it, its media ID and buttons that copy its direct link, the username and the caption.",
    default = false,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch, playerQueriesPatch)

    execute {
        requireStatusMethod("videoDownload")
        offerDownloadOnEveryVideo()
        enableStatus("videoDownload")
    }
}

/**
 * What the patch found of the feed menu's builder. Anyone else's rows start at [at], where
 * [state] holds the builder's state, of [stateType], and [rows] the list of rows. Instagram adds
 * its own Download row with [adder], passing the row kind [kind] and a label read by resource id
 * [label] through the state's [context]. The state keeps the post in [media].
 */
internal class OthersRow(
    val at: Int,
    val state: Int,
    val rows: Int,
    val stateType: String,
    val adder: MethodReference,
    val kind: FieldReference,
    val context: FieldReference,
    val label: Int,
    val media: FieldReference,
)

internal fun BytecodePatchContext.offerDownloadOnEveryVideo() {
    val helpers = mutableListOf<ClassDef>()
    val eligibles = mutableListOf<Method>()
    val loaders = mutableListOf<Method>()
    val shortLists = mutableListOf<Method>()
    val pageReads = mutableListOf<PageRead>()
    val marked = typesMarked(ELIGIBLE_MARKER)
    val loading = typesLoadingDownload()
    val paging = classesCallingInto(MEDIA_EXT).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.originalName() == FEED_HELPER_NAME) helpers += classDef
        val type = classDef.type
        classDef.methods.forEach { method ->
            if (type in marked && ELIGIBLE_MARKER in method.markers()) eligibles += method
            if (type in loading && method.code().any { it.opcode == Opcode.SGET_OBJECT && it.referenceText() == DOWNLOAD }) loaders += method
            if (method.isShortMenuList()) shortLists += method
            if (type in paging) pageReads += method.pageReads()
        }
    }
    val helper = helpers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class named $FEED_HELPER_NAME, found " + if (helpers.isEmpty()) "none" else helpers.joinToString { it.type },
    )
    val eligible = eligibles.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method holding the $ELIGIBLE_MARKER marker, found ${eligibles.size}",
    )
    if (eligible.returnType != "Z" || MEDIA !in eligible.parameterTypes.map(Any::toString)) {
        throw PatchException("$PATCH: ${eligible.definingClass}->${eligible.name}, the download check, doesn't answer a boolean for a Media")
    }
    val type = helper.type
    val builders = loaders.filter { it.calls(eligible) && it.uses(type) }
    val builder = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one builder of the feed menu adding Download after the download check, found " +
            if (builders.isEmpty()) "none" else builders.joinToString { "${it.definingClass}->${it.name}" },
    )
    val others = builder.othersRow(eligible)
    val batchAt = builder.batchRow(others)
    val own = builder.ownPost(eligible, others)
    val icon = optionIcon(PATCH)
    val handlers = helper.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(OPTION)
    }
    val handler = handlers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one handler of a tapped option in $type, found ${handlers.size}")
    // Two static getters answer the post: one reads it, the other calls that one and throws on null.
    val getters = helper.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == MEDIA &&
            method.parameterTypes.map(Any::toString) == listOf(type) &&
            method.code().any { (it as? ReferenceInstruction)?.reference.let { field -> field is FieldReference && field.definingClass == type } }
    }
    val media = getters.singleOrNull()
        ?: throw PatchException("$PATCH: expected one getter reading the post in $type, found ${getters.size}")
    val activities = helper.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == FRAGMENT_ACTIVITY }
    val activity = activities.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $FRAGMENT_ACTIVITY field in $type, found ${activities.size}")
    val carousel = pandoGetter(PATCH, MEDIA, CAROUSEL_FIELD, LIST)
    val page = pageIndex(pageReads, carousel, builder, others.stateType, helper)
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
    mutable(builder).addInstructionsAtControlFlowLabel(
        others.at,
        "invoke-static { v${others.state}, v${others.rows} }, $OFFER_VIDEO",
    )
    mutable(builder).addInstructions(
        own.gate + 1,
        """
            invoke-static { v${own.flag}, v${own.state} }, $OWN_POST_ROW
            move-result v${own.flag}
        """,
    )
    mutable(builder).addInstructions(
        own.result + 1,
        """
            invoke-static { v${own.answer}, v${own.state} }, $OWN_POST
            move-result v${own.answer}
        """,
    )
    mutable(builder).addInstructions(
        batchAt,
        """
            invoke-static { v${others.state}, v${others.rows} }, $OFFER_ALL
            invoke-static { v${others.state}, v${others.rows} }, $OFFER_PLAYER
            invoke-static { v${others.state}, v${others.rows} }, $OFFER_DETAILS
        """,
    )

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
            if-eqz v1, :current
            if-ne v0, v1, :current
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $SHOW_DETAILS
            return-void
            :current
            move-object/from16 v0, p1
            sget-object v1, $DOWNLOAD
            if-ne v0, v1, :handle
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $SAVE_VIDEO
            move-result v0
            if-eqz v0, :handle
            return-void
        """,
        ExternalLabel("handle", menu.getInstruction(0)),
    )

    postOf.addInstructions(
        0,
        """
            check-cast p0, ${others.stateType}
            iget-object p0, p0, ${others.media}
            return-object p0
        """,
    )
    itemStateOf.addInstructions(
        0,
        """
            check-cast p0, ${others.stateType}
            iget-object p0, p0, ${page.builderState}
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
    bridges.methods.add(downloadRow(rowStub, others))
    bridges.methods.remove(allRowStub)
    bridges.methods.add(downloadRow(allRowStub, others, all = true))
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
        addInstructions(0, newOption(icon, "move-object v1, p0"))
    })
    writeBridges()
    writeImageBridges()
    writeCarouselBridge()
    writeCaptionBridges?.invoke()
}

/**
 * [stub] with a body that adds Instagram's Download row to its list the way the builder does:
 * each of the adder's arguments is loaded into the register of its position, so one range call
 * passes them all, with the label read in a spare register after them. The flag the adder takes
 * last is false, as the builder passes it for your own posts.
 * The batch bridge instead takes its separate option and localized title from the extension.
 */
private fun downloadRow(stub: Method, found: OthersRow, all: Boolean = false): MutableMethod {
    val parameters = found.adder.parameterTypes.map(Any::toString)
    val spare = parameters.size
    val loads = parameters.mapIndexed { index, type ->
        when (type) {
            found.kind.type -> "sget-object v$index, ${found.kind}"
            OPTION -> if (all) "move-object v$index, p2" else "sget-object v$index, $DOWNLOAD"
            found.stateType -> "move-object v$index, p0"
            CHAR_SEQUENCE -> if (all) "move-object v$index, p3" else
                """
                    iget-object v$index, p0, ${found.context}
                    invoke-virtual { v$index }, $GET_RESOURCES
                    move-result-object v$index
                    const v$spare, ${found.label}
                    invoke-virtual { v$index, v$spare }, $GET_STRING
                    move-result-object v$index
                """.trimIndent()
            ARRAY_LIST -> "move-object v$index, p1"
            else -> "const/4 v$index, 0x0"
        }
    }
    return ImmutableMethod(
        stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, stub.annotations,
        stub.hiddenApiRestrictions, ImmutableMethodImplementation(spare + if (all) 5 else 3, emptyList(), null, null),
    ).toMutable().apply {
        addInstructions(
            0,
            "check-cast p0, ${found.stateType}\n" + (if (all) "check-cast p2, $OPTION\n" else "") + loads.joinToString("\n") +
                "\ninvoke-static/range { v0 .. v${spare - 1} }, ${found.adder}\nreturn-void",
        )
    }
}

/** Both the fresh row list and captured feed state are valid before the split by ownership. */
internal fun Method.batchRow(found: OthersRow): Int {
    val code = code()
    val cast = code.indices.singleOrNull {
        code[it].opcode == Opcode.CHECK_CAST && code[it].referenceText() == found.stateType &&
            (code[it] as OneRegisterInstruction).registerA == found.state
    } ?: throw PatchException("$PATCH: the feed builder has no unique initial state cast")
    val list = (cast - 1 downTo 0).firstOrNull { code[it].writes(found.rows) }
        ?: throw PatchException("$PATCH: the feed builder has no initial row list")
    if (code[list].opcode != Opcode.NEW_INSTANCE || code[list].referenceText() != ARRAY_LIST ||
        (list + 1 until cast).none { code[it].referenceText() == "$ARRAY_LIST-><init>()V" &&
            code[it].argumentRegisters() == listOf(found.rows) } || cast >= found.at ||
        (list + 1..cast).any { code[it] is OffsetInstruction }
    ) throw PatchException("$PATCH: the row list isn't initialized before the feed builder's state cast")
    return cast + 1
}

/**
 * Where anyone else's rows start in [this], the feed menu's builder, and how Instagram adds its
 * own Download row. The row is loaded once, and the first call after it that takes a Download
 * option, a label and the row list, declared by the state it also takes, adds it; a jump right
 * after ends the row. The instruction after that jump starts anyone else's rows, and every jump
 * there comes before the download check, so only anyone else's posts reach it. The state and the
 * list there are the ones the first call after it that takes both is handed, and neither changes
 * on the way.
 */
internal fun Method.othersRow(eligible: Method): OthersRow {
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
    if (code.getOrNull(add + 1)?.opcode !in GOTOS) throw PatchException("$PATCH: in $where the Download row doesn't end in a jump")
    val at = add + 2

    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    fun target(index: Int) = address.indexOf(address[index] + (code[index] as OffsetInstruction).codeOffset)
    val into = code.indices.filter { code[it] is OffsetInstruction && code[it].opcode.name.let { name -> name.startsWith("if-") || name.startsWith("goto") } }
        .filter { target(it) == at }
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
    val read = block.singleOrNull { code[it].referenceText() == GET_STRING }
        ?: throw PatchException("$PATCH: in $where the Download row's label isn't read once")
    val id = (code[read] as Instruction35c).registerD
    val label = (read - 1 downTo row).firstOrNull { code[it].writes(id) }?.let { code[it] as? NarrowLiteralInstruction }?.narrowLiteral
        ?: throw PatchException("$PATCH: in $where the Download row's label isn't a resource")
    val known = listOf(kind.type, OPTION, stateType, CHAR_SEQUENCE, ARRAY_LIST)
    if (known.any { type -> parameters.count { it == type } != 1 } || parameters.any { it !in known && it != "Z" } || parameters.count { it == "Z" } > 1) {
        throw PatchException("$PATCH: in $where the Download row's adder takes $parameters, which this patch doesn't know")
    }

    val use = (at until code.size).firstOrNull { index ->
        val types = code[index].methodReference()?.parameterTypes?.map(Any::toString) ?: return@firstOrNull false
        code[index].opcode in STATIC_CALLS && stateType in types && ARRAY_LIST in types
    } ?: throw PatchException("$PATCH: in $where no row after Download takes the state and the list")
    val types = code[use].methodReference()!!.parameterTypes.map(Any::toString)
    val arguments = code[use].argumentRegisters()
    val state = arguments[types.indexOf(stateType)]
    val rows = arguments[types.indexOf(ARRAY_LIST)]
    if (state > 15 || rows > 15 || (at until use).any { code[it].writes(state) || code[it].writes(rows) }) {
        throw PatchException("$PATCH: in $where the state v$state and the list v$rows don't reach anyone else's rows as they are")
    }
    val media = (at until code.size).firstNotNullOfOrNull { index ->
        ((code[index].takeIf { it.opcode == Opcode.IGET_OBJECT } as? ReferenceInstruction)?.reference as? FieldReference)
            ?.takeIf { it.definingClass == stateType && it.type == MEDIA }
    } ?: throw PatchException("$PATCH: in $where anyone else's rows never read the post")
    return OthersRow(at, state, rows, stateType, adder, kind, context, label, media)
}

/**
 * Where your own post's rows ask Instagram whether the post may be downloaded: [result], the
 * move-result of the builder's one call to the download check, holding the answer in [answer].
 * Instagram adds its Download row to your own post only on a yes, which a photo, and a video it
 * doesn't allow downloads of, never get (#57). [state] still holds the builder's state there.
 * Past a yes, [gate] reads a flag of the state into [flag], and a 0 there jumps to the row. With
 * the flag and a server flag on, Instagram offers its download in the share sheet and leaves the
 * row out, which is what an account with downloads allowed gets (#57 again).
 */
internal class OwnPost(val result: Int, val answer: Int, val state: Int, val gate: Int, val flag: Int)

/**
 * Finds [OwnPost] in [this], the feed menu's builder. Only your own posts reach the check (see
 * [othersRow]). The post the check is handed is read from the builder's state just before, and
 * nothing between that read and the answer writes the state's register. A no jumps away at once.
 * On a yes, the first branch is on a boolean of the state read without a branch in between, and
 * it jumps ahead to the Download row on a 0.
 */
internal fun Method.ownPost(eligible: Method, found: OthersRow): OwnPost {
    val code = code()
    val where = "$definingClass->$name"
    val check = code.indices.single { code[it].calls(eligible) }
    val result = check + 1
    if (code.getOrNull(result)?.opcode != Opcode.MOVE_RESULT) throw PatchException("$PATCH: $where drops the download check's answer")
    val answer = (code[result] as OneRegisterInstruction).registerA
    val offset = if (code[check].opcode in STATIC_CALLS) 0 else 1
    val post = code[check].argumentRegisters().getOrNull(offset + eligible.parameterTypes.map(Any::toString).indexOf(MEDIA))
        ?: throw PatchException("$PATCH: $where hands the download check no post")
    val read = (check - 1 downTo 0).firstOrNull { code[it].writes(post) }
    if (read == null || code[read].opcode != Opcode.IGET_OBJECT || code[read].referenceText() != found.media.toString()) {
        throw PatchException("$PATCH: $where doesn't read the post it checks from the menu's state")
    }
    val state = (code[read] as TwoRegisterInstruction).registerB
    if ((read + 1..result).any { code[it].writes(state) } || (read + 1 until check).any { code[it] is OffsetInstruction }) {
        throw PatchException("$PATCH: in $where the state doesn't reach the download check's answer as it was")
    }
    if (code.getOrNull(result + 1)?.opcode != Opcode.IF_EQZ || (code[result + 1] as OneRegisterInstruction).registerA != answer) {
        throw PatchException("$PATCH: $where doesn't branch on the download check's answer right away")
    }
    val gate = (result + 2 until code.size).firstOrNull { code[it] is OffsetInstruction || code[it].writes(state) || code[it].readsFlagOf(state, found.stateType) }
        ?.takeIf { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: throw PatchException("$PATCH: past the download check's yes, $where reads no flag of the menu's state before it branches")
    val flag = (code[gate] as TwoRegisterInstruction).registerA
    val branch = (gate + 1 until code.size).firstOrNull { code[it] is OffsetInstruction || code[it].writes(flag) || code[it].writes(state) }
    val row = branch?.let { code.target(it) } ?: -1
    if (branch == null || flag == state || code[branch].opcode != Opcode.IF_EQZ || (code[branch] as OneRegisterInstruction).registerA != flag ||
        row <= branch || row >= found.at || code[row].opcode != Opcode.SGET_OBJECT || code[row].referenceText() != DOWNLOAD
    ) {
        throw PatchException("$PATCH: in $where the menu state's flag past the download check doesn't jump ahead to the Download row on a 0")
    }
    if (state > 15 || answer > 15 || flag > 15) throw PatchException("$PATCH: in $where v$answer, v$flag and v$state are out of an invoke's reach")
    return OwnPost(result, answer, state, gate, flag)
}

/** Whether [this] reads a boolean field of [stateType] from [state]. */
private fun Instruction.readsFlagOf(state: Int, stateType: String): Boolean =
    opcode == Opcode.IGET_BOOLEAN && (this as TwoRegisterInstruction).registerB == state &&
        ((this as ReferenceInstruction).reference as FieldReference).definingClass == stateType

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
 * which the builder's state holds in [builderState] and the menu in [menuState].
 */
internal class PageIndex(val index: FieldReference, val builderState: FieldReference, val menuState: FieldReference)

/**
 * Finds which carousel page a feed post is showing. [MEDIA_EXT] has one static method taking a
 * Media and an int that reads [carousel], the carousel's pages, and answers the page. Instagram
 * hands it the page its feed state keeps nearly everywhere it's called, as an int field read just
 * before, among [reads]. Of those fields, only one on a class the menu, [helper], keeps one field
 * of and [builder] reads off its state [stateType] counts. It has to be read at least twice as often
 * as the next such field, so a build where it's unclear stops the patch rather than guess.
 */
internal fun BytecodePatchContext.pageIndex(
    reads: List<PageRead>,
    carousel: Method,
    builder: Method,
    stateType: String,
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
        ?.let { ImmutableFieldReference(it.definingClass, it.name, it.type) }
    val builderReads = builder.code().mapNotNull { instruction ->
        ((instruction.takeIf { it.opcode == Opcode.IGET_OBJECT } as? ReferenceInstruction)?.reference as? FieldReference)
            ?.takeIf { it.definingClass == stateType }
    }.distinctBy { it.toString() }
    fun builderState(type: String) = builderReads.singleOrNull { it.type == type }
    val fields = reads.filter { it.lookup == lookup.name }.mapNotNull { it.field }
        .filter { it.type == "I" && menuState(it.definingClass) != null && builderState(it.definingClass) != null }
    val ranked = fields.groupBy { it.toString() }.values.sortedByDescending { it.size }
    val top = ranked.firstOrNull() ?: throw PatchException(
        "$PATCH: no call to ${lookup.definingClass}->${lookup.name} reads its page from a feed state the menu and its builder hold",
    )
    val next = ranked.getOrNull(1)
    if (next != null && top.size < 2 * next.size) {
        throw PatchException("$PATCH: the carousel page is either ${top.first()} (${top.size} reads) or ${next.first()} (${next.size} reads)")
    }
    val index = top.first()
    return PageIndex(index, builderState(index.definingClass)!!, menuState(index.definingClass)!!)
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

/** Whether [this] reads or calls anything of [type]. */
private fun Method.uses(type: String): Boolean = code().any { instruction ->
    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        is FieldReference -> reference.definingClass == type
        is MethodReference -> reference.definingClass == type
        else -> false
    }
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }
