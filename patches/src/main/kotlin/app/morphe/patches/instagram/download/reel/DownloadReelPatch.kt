/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

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
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.carouselBridge
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.download.musicBridges
import app.morphe.patches.instagram.download.playerQueriesPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Download any reel"

/** The options of a post's or a reel's more menu, which keep their names, Download among them. */
internal const val OPTION = "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"
internal const val DOWNLOAD = "$OPTION->DOWNLOAD:$OPTION"

/** The types of the classes outside the extension whose code reads [DOWNLOAD], by the class index. */
internal fun BytecodePatchContext.typesLoadingDownload(): Set<String> =
    classesAccessing(OPTION, DOWNLOAD.substringAfter("->").substringBefore(":"), Opcode.SGET_OBJECT).mapTo(HashSet()) { it.type }
private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"

private const val REEL_DOWNLOAD = "$EXTENSION_PACKAGE/download/ReelDownload;"
internal const val OFFER = "$REEL_DOWNLOAD->offer(I)Z"
internal const val WITHHOLD = "$REEL_DOWNLOAD->withhold(I)Z"

/**
 * The filters of a builder that hands Download straight to the menu's adder of one row, which also
 * let it in for Open in another player alone: the adder then puts the player row in its place. The
 * first is handed the reel the download check was asked about too, so a reel the player row can't go
 * on is never let in, and its menu stays Instagram's, dividers and all.
 */
internal const val OFFER_ROW = "$REEL_DOWNLOAD->offerRow(ILjava/lang/Object;)Z"
internal const val WITHHOLD_ROW = "$REEL_DOWNLOAD->withholdRow(I)Z"
internal const val SAVE = "$REEL_DOWNLOAD->save(Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)Z"
internal const val OURS = "$REEL_DOWNLOAD->ours(Ljava/lang/Object;)Z"
internal const val ROWS = "$REEL_DOWNLOAD->rows(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val ADD_TO = "$REEL_DOWNLOAD->addTo(Ljava/util/List;Ljava/lang/Object;)V"

/** The reel menu's handler of a tapped option, in the class that runs the menu. */
internal const val HANDLER_MARKER = "ClipsOrganicMoreOptionsHelper_handleOptionSelected"

/**
 * The reel menu's adder of one row: it takes the option, the sheet and the row's state, and an
 * icon and a label that replace the option's own when they aren't null.
 */
internal const val ROW_MARKER = "ClipsOrganicMoreOptionsHelper_addBottomSheetRowItem"
private const val CONTEXT = "Landroid/content/Context;"

/** Instagram's check of whether a reel's owner lets other people download it. */
internal const val ELIGIBLE_MARKER = "ClipsDownloadUtil_isMediaEligibleForThirdPartyDownloads"

/** The options of the reduced reel menu, a short list that never had a Download row. */
internal const val REDUCED_MARKER = "ClipsOrganicMediaItemViewMoreOptionsController_getReducedMenuOptions"

/**
 * Download in every reel's more menu, saving through HushGram's own pipeline.
 *
 * Instagram 449 already has a Download row in that menu. It shows the row only when the reel's
 * owner lets other people download it, and the redesigned menus also hold it back while a server
 * flag says so. A tap asks Instagram's server, then saves a copy with a watermark. This patch
 * shows the row on every reel and has a tap save the reel from the addresses its Media already
 * holds, at the Download quality, the way Hushfacebook's reel download does.
 *
 * Some accounts get a reduced menu instead: a short list of options (Playback, Interested, Report
 * and a few more) that never includes Download. Its list gets Download added before it's shown,
 * and its rows go through the same handler.
 *
 * The Reels viewer also shows photo posts that come with music, which have no video at all. A tap
 * on Download there saves the picture at its largest size, through the picture bridges Download
 * any story uses, which this patch writes too (#71). When the post's music has a track to fetch,
 * the menu's adder of one row puts Download as video and Download as photo in Download's place,
 * two options made the way Download is, and the handler hands a tap on either to the extension.
 *
 * A builder that hands Download straight to that adder also lets it in when only Open in another
 * player is on and the reel has a video file for it, and the adder then puts the player row alone in
 * its place, so a reel Instagram keeps Download off still gets the player row. Any other reel isn't
 * let in, so its menu, divider included, stays Instagram's. Builders that put Download anywhere else
 * never let it in for the player.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val downloadReelPatch = bytecodePatch(
    name = "Download any reel",
    description = "Adds Download to every reel's more menu. Reels save at the Download quality you set, best by " +
        "default, without Instagram's watermark. On to start. Download cover, off until you turn it on, adds a row " +
        "that saves the still picture shown before the reel plays. Switches: HushGram settings > Reels.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch, playerQueriesPatch)

    execute {
        requireStatusMethod("reelDownload")
        offerDownloadOnEveryReel()
        enableStatus("reelDownload")
    }
}

/**
 * Where a builder of the reel menu decides on the Download row: after the move-result at [at] - 1,
 * which leaves Instagram's answer in [register], [hook] filters it, handed [media] too when it isn't
 * null, the register still holding the reel the download check was asked about.
 */
internal class Gate(val at: Int, val register: Int, val hook: String, val media: Int? = null)

internal fun BytecodePatchContext.offerDownloadOnEveryReel() {
    val handlers = mutableListOf<Method>()
    val eligibles = mutableListOf<Method>()
    val loaders = mutableListOf<Method>()
    val reducedLists = mutableListOf<Method>()
    val rowAdders = mutableListOf<Method>()
    val marked = typesMarked(HANDLER_MARKER, ELIGIBLE_MARKER, REDUCED_MARKER, ROW_MARKER)
    val loading = typesLoadingDownload()
    classDefForEach { classDef ->
        val holdsMarker = classDef.type in marked
        val readsDownload = classDef.type in loading
        if (!holdsMarker && !readsDownload) return@classDefForEach
        classDef.methods.forEach { method ->
            val markers = if (holdsMarker) method.markers() else emptyList()
            if (HANDLER_MARKER in markers) handlers += method
            if (ELIGIBLE_MARKER in markers) eligibles += method
            if (REDUCED_MARKER in markers) reducedLists += method
            if (ROW_MARKER in markers) rowAdders += method
            if (readsDownload && method.code().any { it.opcode == Opcode.SGET_OBJECT && it.referenceText() == DOWNLOAD }) loaders += method
        }
    }
    val handler = one(handlers, HANDLER_MARKER)
    val eligible = one(eligibles, ELIGIBLE_MARKER)
    if (AccessFlags.STATIC.isSet(handler.accessFlags) || handler.parameterTypes.map(Any::toString) != listOf(OPTION) ||
        handler.returnType != "V"
    ) {
        throw PatchException("$PATCH: ${handler.definingClass}->${handler.name}, the menu's handler, doesn't take one option")
    }
    if (eligible.returnType != "Z") {
        throw PatchException("$PATCH: ${eligible.definingClass}->${eligible.name}, the download check, doesn't answer a boolean")
    }
    val helper = handler.definingClass
    val adder = one(rowAdders, ROW_MARKER)
    val adderTypes = adder.parameterTypes.map(Any::toString)
    if (adder.definingClass != helper || AccessFlags.STATIC.isSet(adder.accessFlags) || adder.returnType != "V" ||
        adderTypes.size != 6 || adderTypes[0] != CONTEXT || adderTypes[1] != OPTION || !adderTypes[2].startsWith("L") ||
        !adderTypes[3].startsWith("L") || adderTypes[4] != "Ljava/lang/Integer;" || adderTypes[5] != "Ljava/lang/String;"
    ) {
        throw PatchException("$PATCH: ${adder.definingClass}->${adder.name}, the adder of one row, takes ${adderTypes.joinToString("")}")
    }
    val builders = loaders.filter { it.calls(eligible) && (it.definingClass == helper || it.uses(helper)) }
    if (builders.isEmpty()) throw PatchException("$PATCH: no builder of the reel menu adds Download after the download check")
    val gates = builders.associateWith { it.downloadGates(eligible, handsToRows(it, adder)) }
    val reduced = one(reducedLists, REDUCED_MARKER)
    val reducedReturns = reduced.optionListReturns()
    val media = instanceField(helper, MEDIA)
    val activity = instanceField(helper, FRAGMENT_ACTIVITY)
    val icon = optionIcon(PATCH)
    val writeBridges = mediaBridges(PATCH)
    val writeImageBridges = imageBridges(PATCH)
    val writeMusicBridges = musicBridges(PATCH)
    // Only a carousel's Download reads its pages, so a build where they can't be told keeps
    // Download on reels, which then saves a carousel's first page as before.
    val writeCarouselBridge = try {
        carouselBridge(PATCH)
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $PATCH goes in saving only a carousel's first page.")
        null
    }
    val writeRowBridges = rowBridges(icon, adder)
    val menu = mutable(handler)
    menu.requireLocals(PATCH, 3)
    val rows = mutable(adder)
    rows.requireLocals(PATCH, 5)

    gates.forEach { (builder, found) ->
        val method = mutable(builder)
        found.sortedByDescending { it.at }.forEach { gate ->
            val register = "v${gate.register}"
            val call = when {
                gate.media != null -> "invoke-static { $register, v${gate.media} }"
                gate.register > 15 -> "invoke-static/range { $register .. $register }"
                else -> "invoke-static { $register }"
            }
            method.addInstructions(
                gate.at,
                """
                    $call, ${gate.hook}
                    move-result $register
                """,
            )
        }
    }

    // Each return of the reduced list hands the list through the extension first. The return is
    // replaced rather than preceded, because a jump to the return lands on what replaces it and
    // would skip anything put in front of it.
    val list = mutable(reduced)
    reducedReturns.sortedDescending().forEach { index ->
        val register = (list.getInstruction(index) as OneRegisterInstruction).registerA
        val scratch = if (register == 0) 1 else 0
        list.replaceInstruction(index, "sget-object v$scratch, $DOWNLOAD")
        list.addInstructions(
            index + 1,
            """
                invoke-static { v$register, v$scratch }, $ADD_TO
                return-object v$register
            """,
        )
    }

    // Download, and the two rows a photo with music gets in its place, go to save() first.
    menu.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            sget-object v1, $DOWNLOAD
            if-eq v0, v1, :save
            invoke-static { v0 }, $OURS
            move-result v1
            if-eqz v1, :handle
            :save
            move-object/from16 v0, p0
            iget-object v1, v0, $media
            iget-object v2, v0, $activity
            move-object/from16 v0, p1
            invoke-static { v0, v1, v2 }, $SAVE
            move-result v0
            if-eqz v0, :handle
            return-void
        """,
        ExternalLabel("handle", menu.getInstruction(0)),
    )
    // Download's row asks rows() first, which may add the two in its place.
    rows.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p2
            sget-object v1, $DOWNLOAD
            if-ne v0, v1, :add
            move-object/from16 v0, p0
            iget-object v1, v0, $media
            move-object/from16 v2, p1
            move-object/from16 v3, p3
            move-object/from16 v4, p4
            invoke-static/range { v0 .. v4 }, $ROWS
            move-result v0
            if-eqz v0, :add
            return-void
        """,
        ExternalLabel("add", rows.getInstruction(0)),
    )
    writeBridges()
    writeImageBridges()
    writeMusicBridges()
    writeCarouselBridge?.invoke()
    writeRowBridges()
}

/**
 * Finds the extension's two bridges to the reel menu, and answers the step that writes them:
 * `reelOption`, which makes an option named by its argument with Download's ordinal and [icon],
 * and `addReelRow`, which hands an option and a label to [adder], the menu's adder of one row,
 * with no icon of its own, and answers true.
 */
private fun BytecodePatchContext.rowBridges(icon: String, adder: Method): () -> Unit {
    val bridges = mutableClassDefBy(INSTAGRAM_MEDIA)
    val optionStub = bridges.methods.singleOrNull {
        it.name == "reelOption" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;")
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no static reelOption(String)")
    val rowStub = bridges.methods.singleOrNull {
        it.name == "addReelRow" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(Any::toString) == List(5) { "Ljava/lang/Object;" } + "Ljava/lang/String;"
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no static addReelRow(Object, Object, Object, Object, Object, String)")
    val types = adder.parameterTypes.map(Any::toString)
    val call = "${adder.definingClass}->${adder.name}(${types.joinToString("")})V"
    return {
        bridges.methods.remove(optionStub)
        bridges.methods.add(replaced(optionStub, 5).apply {
            addInstructions(0, newOption(icon, "move-object v1, p0"))
        })
        bridges.methods.remove(rowStub)
        bridges.methods.add(replaced(rowStub, 13).apply {
            addInstructions(
                0,
                """
                    move-object v0, p0
                    check-cast v0, ${adder.definingClass}
                    move-object v1, p1
                    check-cast v1, ${types[0]}
                    move-object v2, p2
                    check-cast v2, ${types[1]}
                    move-object v3, p3
                    check-cast v3, ${types[2]}
                    move-object v4, p4
                    check-cast v4, ${types[3]}
                    const/4 v5, 0x0
                    move-object v6, p5
                    invoke-virtual/range { v0 .. v6 }, $call
                    const/4 v0, 0x1
                    return v0
                """,
            )
        })
    }
}

/** [stub] with an empty body of [registers] registers, its parameters among them. */
private fun replaced(stub: Method, registers: Int): MutableMethod = ImmutableMethod(
    stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, stub.annotations,
    stub.hiddenApiRestrictions, ImmutableMethodImplementation(registers, emptyList(), null, null),
).toMutable()

private fun one(methods: List<Method>, marker: String): Method = methods.singleOrNull() ?: throw PatchException(
    "$PATCH: expected one method holding the $marker marker, found " +
        if (methods.isEmpty()) "none" else methods.joinToString { "${it.definingClass}->${it.name}" },
)

/** The one instance field of [type] in [owner], the reel menu's class, as a field reference. */
private fun BytecodePatchContext.instanceField(owner: String, type: String): String {
    val fields = classDefBy(owner).fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
    val field = fields.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $type field in $owner, the reel menu's class, found ${fields.size}")
    return "$owner->${field.name}:$type"
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

/**
 * Every branch of [this] builder that keeps the Download row out, each with the filter that lets it
 * in: after the download check, the one that skips the row when the check says no, and after that,
 * each that skips it when a flag says yes. The builder must load the row once, call the check once
 * before it, and have only those two kinds of branch between them jumping past the row. A builder
 * that hands the row straight to the menu's adder of one row ([rows]) gets the filters that also let
 * it in for Open in another player alone, when the check's Media argument is in a register the first
 * filter can be handed, apart from the check's answer. Otherwise it gets the plain ones.
 */
internal fun Method.downloadGates(eligible: Method, rows: Boolean = false): List<Gate> {
    val code = code()
    val where = "$definingClass->$name"
    val row = code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT && code[it].referenceText() == DOWNLOAD }.singleOrNull()
        ?: throw PatchException("$PATCH: $where doesn't load Download once")
    val check = code.indices.filter { code[it].calls(eligible) }.singleOrNull()
        ?: throw PatchException("$PATCH: $where doesn't call the download check once")
    if (check > row) throw PatchException("$PATCH: $where calls the download check after adding Download")
    val media = if (!rows) null else code.mediaArgument(check, eligible)?.takeIf { register ->
        val answer = (code.getOrNull(check + 1) as? OneRegisterInstruction)?.registerA
        register <= 15 && answer != null && answer <= 15 && answer != register
    }
    val toRows = media != null

    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val skips = (check + 1 until row).filter { index ->
        val jump = code[index]
        (jump.opcode in BRANCHES || jump.opcode in GOTOS) &&
            address.indexOf(address[index] + (jump as OffsetInstruction).codeOffset) > row
    }
    if (skips.none { code[it].opcode == Opcode.IF_EQZ }) {
        throw PatchException("$PATCH: in $where no branch after the download check skips Download")
    }
    return skips.map { index ->
        val branch = code[index]
        if (branch.opcode in GOTOS) throw PatchException("$PATCH: in $where a jump at $index goes around Download")
        val register = (branch as OneRegisterInstruction).registerA
        val set = (index - 1 downTo check + 1).firstOrNull { code[it].writes(register) }
            ?: throw PatchException("$PATCH: in $where the branch at $index tests a register set before the download check")
        val call = code.getOrNull(set - 1)
        val answers = (call as? ReferenceInstruction)?.reference as? MethodReference
        if (code[set].opcode != Opcode.MOVE_RESULT || answers?.returnType != "Z") {
            throw PatchException("$PATCH: in $where the branch at $index doesn't test the answer of a call")
        }
        val fromCheck = call.calls(eligible)
        when {
            fromCheck && branch.opcode == Opcode.IF_EQZ ->
                if (toRows) Gate(set + 1, register, OFFER_ROW, media) else Gate(set + 1, register, OFFER)
            !fromCheck && branch.opcode == Opcode.IF_NEZ -> Gate(set + 1, register, if (toRows) WITHHOLD_ROW else WITHHOLD)
            else -> throw PatchException("$PATCH: in $where the branch at $index keeps Download out in a way this patch doesn't know")
        }
    }
}

/**
 * Whether [builder] hands Download straight to [adder], the menu's adder of one row: the first call
 * after it loads Download takes Download, and is the adder or a method of the adder's class that
 * calls it. Any other builder puts Download in a list, or in a menu of its own that never reaches the
 * adder, so it never gets the player row and must not be let in for it.
 */
private fun BytecodePatchContext.handsToRows(builder: Method, adder: Method): Boolean {
    val code = builder.code()
    val row = code.indexOfFirst { it.opcode == Opcode.SGET_OBJECT && it.referenceText() == DOWNLOAD }
    if (row < 0) return false
    val download = (code[row] as OneRegisterInstruction).registerA
    val call = code.drop(row + 1).firstOrNull { (it as? ReferenceInstruction)?.reference is MethodReference } ?: return false
    if (!call.passes(download)) return false
    if (call.calls(adder)) return true
    val called = (call as ReferenceInstruction).reference as MethodReference
    if (called.definingClass != adder.definingClass) return false
    return classDefBy(adder.definingClass).methods.any { method ->
        method.name == called.name && method.returnType == called.returnType &&
            method.parameterTypes.map(Any::toString) == called.parameterTypes.map(Any::toString) && method.calls(adder)
    }
}

/**
 * The register the call at [at] hands [eligible], the download check, its one Media in, or null when
 * the check takes none, or more than one.
 */
private fun List<Instruction>.mediaArgument(at: Int, eligible: Method): Int? {
    val types = eligible.parameterTypes.map(Any::toString)
    if (types.count { it == MEDIA } != 1) return null
    val before = types.takeWhile { it != MEDIA }.sumOf { if (it == "J" || it == "D") 2 else 1 }
    val index = (if (AccessFlags.STATIC.isSet(eligible.accessFlags)) 0 else 1) + before
    return when (val call = this[at]) {
        is RegisterRangeInstruction -> (call.startRegister + index).takeIf { index < call.registerCount }
        is FiveRegisterInstruction ->
            listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount).getOrNull(index)
        else -> null
    }
}

/** Whether [this], a call, hands [register] to the method it calls. */
private fun Instruction.passes(register: Int): Boolean = when (this) {
    is RegisterRangeInstruction -> register in startRegister until startRegister + registerCount
    is FiveRegisterInstruction -> register in listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> false
}

/**
 * The returns of [this], the reduced reel menu's list of options. It must answer a list, and each
 * return's register must fit a plain call, next to one more register the hook loads Download into.
 */
internal fun Method.optionListReturns(): List<Int> {
    val where = "$definingClass->$name"
    if (returnType != "Ljava/util/ArrayList;" && returnType != "Ljava/util/List;") {
        throw PatchException("$PATCH: $where, the reduced menu's options, doesn't answer a list")
    }
    val code = code()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) throw PatchException("$PATCH: $where, the reduced menu's options, never returns")
    if ((implementation?.registerCount ?: 0) < 2) throw PatchException("$PATCH: $where has no register to spare")
    returns.forEach { index ->
        if ((code[index] as OneRegisterInstruction).registerA > 15) {
            throw PatchException("$PATCH: $where returns its options from a register above v15")
        }
    }
    return returns
}

internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

/** The conditional branches, and the plain jumps. Opcode.name is the smali name, so they're listed. */
private val BRANCHES = setOf(
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
)
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

private fun Instruction.calls(method: Method): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
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
