/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.MUSIC_CONSUMPTION
import app.morphe.patches.instagram.download.MUSIC_INFO
import app.morphe.patches.instagram.download.PANDO_MUSIC_CONSUMPTION
import app.morphe.patches.instagram.download.PANDO_MUSIC_INFO
import app.morphe.patches.instagram.download.PANDO_TRACK_DATA
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import app.morphe.patches.instagram.download.PANDO_VIDEO_VERSION
import app.morphe.patches.instagram.download.TRACK_DATA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.VIDEO_VERSION
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadReelHookTest {
    private val helper = "Lfixture/ClipsHelper;"
    private val controller = "Lfixture/MoreOptionsController;"
    private val feedSheet = "Lfixture/FeedSheet;"
    private val util = "Lfixture/DownloadUtil;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val activity = "Landroidx/fragment/app/FragmentActivity;"
    private val check = "$util->A08($session$MEDIA)Z"
    private val sheet = "Lfixture/Sheet;"
    private val rowState = "Lfixture/RowState;"
    private val musicMetadata = "Lfixture/MusicMetadata;"
    private val clipsMetadata = "Lfixture/ClipsMetadata;"
    private val rowTypes = listOf("Landroid/content/Context;", OPTION, sheet, rowState, "Ljava/lang/Integer;", "Ljava/lang/String;")

    // What the redesigned builder does with Download once it's let in, after its gates. Instagram's
    // hands it to the adder's shorthand. A0R takes an option and never reaches the adder.
    private val toShorthand = """
        sget-object v3, $DOWNLOAD
        invoke-virtual { v1, v2, v3, v2, v2 }, $helper->A0P(${rowTypes.take(4).joinToString("")})V
    """
    private val toAdder = """
        sget-object v6, $DOWNLOAD
        move-object v4, v1
        const/4 v5, 0x0
        const/4 v7, 0x0
        const/4 v8, 0x0
        const/4 v9, 0x0
        const/4 v10, 0x0
        invoke-virtual/range { v4 .. v10 }, $helper->A0Q(${rowTypes.joinToString("")})V
    """
    private val toAnotherMethod = """
        sget-object v3, $DOWNLOAD
        invoke-virtual { v1, v3 }, $helper->A0R($OPTION)V
    """
    private val afterAnotherCall = """
        sget-object v3, $DOWNLOAD
        invoke-virtual { v1 }, $helper->A0S()V
        invoke-virtual { v1, v2, v3, v2, v2 }, $helper->A0P(${rowTypes.take(4).joinToString("")})V
    """

    /** The hooks the patch writes are in the extension the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(OFFER, WITHHOLD, OFFER_ROW, WITHHOLD_ROW, SAVE, OURS, ROWS, ADD_TO)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * Every reel menu builder lets Download in with the switch on: the legacy one past the download
     * check, the redesigned ones past the check and the flag. The redesigned one that hands Download
     * to the adder of one row gets the filters that also let it in for the player row alone, and the
     * one with a menu of its own the plain ones. The feed's menu, which isn't the reel menu's, stays
     * Instagram's.
     */
    @Test
    fun theReelMenusOfferDownload() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryReel()

        val legacy = context.method(helper, "A06").code()
        assertFiltered(legacy, check, OFFER)
        val redesign = context.method(controller, "A08").code()
        assertFiltered(redesign, check, OFFER_ROW, media = 2)
        assertFiltered(redesign, "Lfixture/MobileConfig;->A1A(Ljava/lang/Object;J)Z", WITHHOLD_ROW)
        val ownMenu = context.method(controller, "A0Y").code()
        assertFiltered(ownMenu, check, OFFER)
        assertFiltered(ownMenu, "Lfixture/MobileConfig;->A1B(Ljava/lang/Object;)Z", WITHHOLD)
        assertEquals("the feed's menu was touched", feedSheetCode().size, context.method(feedSheet, "invoke").code().size)
    }

    /**
     * Only a builder whose first call after loading Download hands it to the adder, or to a method
     * of the adder's class that calls the adder, gets the filters that let it in for the player row.
     * Download handed to another method of that class, or after another call, gets the plain ones,
     * since nothing would put the player row in its place there.
     */
    @Test
    fun onlyABuilderHandingDownloadToTheAdderLetsItInForThePlayer() {
        val flag = "Lfixture/MobileConfig;->A1A(Ljava/lang/Object;J)Z"
        for ((handOff, rows) in listOf(toShorthand to true, toAdder to true, toAnotherMethod to false, afterAnotherCall to false)) {
            val context = PatchContexts.of(classes(handOff = handOff))

            context.offerDownloadOnEveryReel()

            val redesign = context.method(controller, "A08").code()
            if (rows) assertFiltered(redesign, check, OFFER_ROW, media = 2) else assertFiltered(redesign, check, OFFER)
            assertFiltered(redesign, flag, if (rows) WITHHOLD_ROW else WITHHOLD)
        }
    }

    /**
     * The filter that lets Download in for the player row is handed the reel the check was asked
     * about, so a reel with no video file for the player is never let in, and the builder's divider
     * after Download stays out with it. A check whose answer lands in the register that held the reel
     * leaves nothing to hand over, and the builder gets the plain filters.
     */
    @Test
    fun theFilterForThePlayerRowIsHandedTheReel() {
        val context = PatchContexts.of(classes(checkCall = "invoke-virtual { v2, v2, v3 }, $check"))

        context.offerDownloadOnEveryReel()

        val redesign = context.method(controller, "A08").code()
        assertFiltered(redesign, check, OFFER)
        assertFiltered(redesign, "Lfixture/MobileConfig;->A1A(Ljava/lang/Object;J)Z", WITHHOLD)
    }

    /**
     * The reduced menu's list goes through addTo() with Download where it returns. The return is
     * replaced, so the jump that used to land on it runs the call too.
     */
    @Test
    fun theReducedMenuListsDownload() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryReel()

        val code = context.method(controller, "A03").code()
        val end = code.size - 3
        assertEquals(listOf(Opcode.SGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.RETURN_OBJECT), code.drop(end).map { it.opcode })
        assertEquals(DOWNLOAD, code[end].referenceText())
        assertEquals("Download's register", 0, (code[end] as OneRegisterInstruction).registerA)
        assertEquals(ADD_TO, code[end + 1].referenceText())
        val call = code[end + 1] as Instruction35c
        assertEquals("addTo()'s arguments", listOf(1, 0), listOf(call.registerC, call.registerD))
        assertEquals("the list returned", 1, (code[end + 2] as OneRegisterInstruction).registerA)
        assertEquals("the jump to the return", end, code.target(code.indexOfFirst { it.opcode == Opcode.IF_EQZ }))
    }

    @Test
    fun aMissingReducedMenuFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(reducedMarker = "ClipsOrganicMediaItemViewMoreOptionsController_somethingElse"))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertTrue(failure.message!!, failure.message!!.contains(REDUCED_MARKER))
        assertUntouched(context)
    }

    /**
     * A tap on Download, or on a row a photo with music gets in its place, asks save() first with
     * the option, the menu's media and its activity; any other option goes on.
     */
    @Test
    fun theHandlerAsksSaveFirst() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryReel()

        val code = context.method(helper, "A0T").code()
        assertEquals(
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.SGET_OBJECT, Opcode.IF_EQ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                Opcode.IF_EQZ, Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
            ),
            code.take(14).map { it.opcode },
        )
        assertEquals(DOWNLOAD, code[1].referenceText())
        assertEquals(OURS, code[3].referenceText())
        assertEquals("$helper->media:$MEDIA", code[7].referenceText())
        assertEquals("$helper->activity:$activity", code[8].referenceText())
        assertEquals(SAVE, code[10].referenceText())
        val save = code[10] as Instruction35c
        assertEquals("save()'s arguments", listOf(0, 1, 2), listOf(save.registerC, save.registerD, save.registerE))
        // p1 is v4 in a method of five registers taking two.
        assertEquals("the option", 4, (code[9] as TwoRegisterInstruction).registerB)
        assertEquals("the original code moved", Opcode.CONST_STRING, code[14].opcode)
        assertEquals("Download's branch", 6, code.target(2))
        for (branch in listOf(5, 12)) assertEquals("the branch at $branch", 14, code.target(branch))
    }

    /**
     * Download's row asks rows() first, with the menu, its media, and the context, sheet and row
     * state the adder was handed. Any other option's row, the two of a photo with music among
     * them, goes on as Instagram's.
     */
    @Test
    fun theRowAdderAsksRowsFirst() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryReel()

        val code = context.method(helper, "A0Q").code()
        assertEquals(
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.SGET_OBJECT, Opcode.IF_NE, Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT,
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC_RANGE,
                Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
            ),
            code.take(12).map { it.opcode },
        )
        assertEquals(DOWNLOAD, code[1].referenceText())
        assertEquals("$helper->media:$MEDIA", code[4].referenceText())
        assertEquals(ROWS, code[8].referenceText())
        val call = code[8] as RegisterRangeInstruction
        assertEquals("rows()'s arguments", listOf(0, 5), listOf(call.startRegister, call.registerCount))
        // p0 is v5 in a method of 12 registers taking seven: the option p2, then the context p1, sheet p3, row state p4.
        val moved = listOf(0, 3, 5, 6, 7).map { (code[it] as TwoRegisterInstruction).registerB }
        assertEquals("what rows() is handed", listOf(7, 5, 6, 8, 9), moved)
        assertEquals("the original code moved", Opcode.CONST_STRING, code[12].opcode)
        for (branch in listOf(2, 10)) assertEquals("the branch at $branch", 12, code.target(branch))
    }

    /** A row adder that takes no label is one this patch can't hand a row's name to, and nothing changes. */
    @Test
    fun aRowAdderWithoutALabelFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(rowParameters = rowTypes.dropLast(1)))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertTrue(failure.message!!, failure.message!!.contains("the adder of one row"))
        assertUntouched(context)
    }

    @Test
    fun aMissingMusicGetterFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(leaveOutField = "overlap_duration_in_ms"))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertTrue(failure.message!!, failure.message!!.contains("overlap_duration_in_ms"))
        assertUntouched(context)
    }

    /**
     * reelOption() makes an option the way Instagram makes Download, named by its argument, and
     * addReelRow() hands the menu's adder the option and its label with no icon of its own.
     */
    @Test
    fun theRowBridgesMakeAnOptionAndAddItsRow() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryReel()

        val option = context.method(INSTAGRAM_MEDIA, "reelOption").code()
        assertEquals(DOWNLOAD, option[0].referenceText())
        assertEquals(Opcode.MOVE_OBJECT, option[5].opcode)
        // p0 is v4 in a method of five registers.
        assertEquals("the name is the argument", 4, (option[5] as TwoRegisterInstruction).registerB)
        assertEquals("$OPTION-><init>(Ljava/lang/String;II)V", option[7].referenceText())
        assertEquals(Opcode.RETURN_OBJECT, option.last().opcode)
        val row = context.method(INSTAGRAM_MEDIA, "addReelRow").code()
        val call = row.single { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        assertEquals("$helper->A0Q(${rowTypes.joinToString("")})V", call.referenceText())
        assertEquals("the adder's arguments", listOf(0, 7),
            (call as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("the casts", listOf(helper) + rowTypes.take(4),
            row.filter { it.opcode == Opcode.CHECK_CAST }.map { it.referenceText() })
        assertEquals("no icon of its own", Opcode.CONST_4, row[10].opcode)
        // p5 is v12 in a method of 13 registers taking six.
        assertEquals("the label is the last argument", 12, (row[11] as TwoRegisterInstruction).registerB)
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN), row.takeLast(2).map { it.opcode })
    }

    /**
     * Each bridge casts its argument and calls the getter that reads its field. The picture's
     * bridges are written too, for a photo the Reels viewer shows with its music (#71).
     */
    @Test
    fun theBridgesCallTheGetters() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryReel()

        val expected = mapOf(
            "videoVersions" to "$MEDIA->AAh()Ljava/util/List;",
            "dashManifest" to "$MEDIA->A8P()Ljava/lang/String;",
            "mediaId" to "$MEDIA->getId()Ljava/lang/String;",
            "owner" to "$MEDIA->A3Q()$USER",
            "takenAt" to "$MEDIA->A6v()Ljava/lang/Long;",
            "username" to "$USER->A89()Ljava/lang/String;",
            "versionUrl" to "$VIDEO_VERSION->getUrl()Ljava/lang/String;",
            "versionWidth" to "$VIDEO_VERSION->DvO()Ljava/lang/Integer;",
            "versionHeight" to "$VIDEO_VERSION->CK7()Ljava/lang/Integer;",
            "imageVersions" to "$MEDIA->A3F()$IMAGE_INFO",
            "imageCandidates" to "$IMAGE_INFO->Bd1()Ljava/util/List;",
            "candidateUrl" to "$IMAGE_URL->getUrl()Ljava/lang/String;",
            "candidateWidth" to "$IMAGE_URL->getWidth()I",
            "candidateHeight" to "$IMAGE_URL->getHeight()I",
            "musicMetadata" to "$MEDIA->A2H()$musicMetadata",
            "metadataMusic" to "$musicMetadata->Cmh()$MUSIC_INFO",
            "clipsMetadata" to "$MEDIA->A33()$clipsMetadata",
            "clipsMusic" to "$clipsMetadata->Cmh()$MUSIC_INFO",
            "musicTrack" to "$MUSIC_INFO->CmW()$TRACK_DATA",
            "musicConsumption" to "$MUSIC_INFO->Cme()$MUSIC_CONSUMPTION",
            "trackUrl" to "$TRACK_DATA->BRg()Ljava/lang/String;",
            "trackFastStartUrl" to "$TRACK_DATA->BTF()Ljava/lang/String;",
            "musicStartMs" to "$MUSIC_CONSUMPTION->BTI()Ljava/lang/Integer;",
            "musicLengthMs" to "$MUSIC_CONSUMPTION->BwK()Ljava/lang/Integer;",
            "carouselMedia" to "$MEDIA->A8k()Ljava/util/List;",
        )
        expected.forEach { (bridge, getter) ->
            val code = context.method(INSTAGRAM_MEDIA, bridge).code()
            val call = if (getter.startsWith(MEDIA) || getter.startsWith(USER)) Opcode.INVOKE_VIRTUAL else Opcode.INVOKE_INTERFACE
            val answer = if (getter.endsWith(")I")) listOf(Opcode.MOVE_RESULT, Opcode.RETURN) else listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT)
            assertEquals(bridge, listOf(Opcode.CHECK_CAST, call) + answer, code.take(4).map { it.opcode })
            assertEquals(bridge, getter, code[1].referenceText())
        }
    }

    /**
     * A carousel in the Reels viewer saved only its first page (#78), since nothing here read its
     * pages. A build where they can't be told keeps the rest of the patch, with the bridge a stub.
     */
    @Test
    fun aCarouselsPagesAreOptional() {
        val context = PatchContexts.of(classes(leaveOutField = "carousel_media"))

        context.offerDownloadOnEveryReel()

        assertEquals("the carousel bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "carouselMedia").code().first().opcode)
        assertEquals("a bridge was left out", Opcode.CHECK_CAST, context.method(INSTAGRAM_MEDIA, "videoVersions").code().first().opcode)
        assertEquals("the handler was left out", Opcode.MOVE_OBJECT_FROM16, context.method(helper, "A0T").code().first().opcode)
    }

    /** A build whose picture has no candidates getter stops the patch before anything changes. */
    @Test
    fun aMissingPictureGetterFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(leaveOutCandidates = true))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertTrue(failure.message!!, failure.message!!.contains("candidates"))
        assertUntouched(context)
    }

    /** A flag that lets Download in only when it's on is a gate this patch doesn't know, and nothing changes. */
    @Test
    fun anUnknownGateFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(flagSkipsWhen = "if-eqz"))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertUntouched(context)
    }

    @Test
    fun aMissingGetterFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(leaveOutField = "taken_at"))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertTrue(failure.message!!, failure.message!!.contains("taken_at"))
        assertUntouched(context)
    }

    @Test
    fun aSecondMediaFieldFailsThePatch() {
        val context = PatchContexts.of(classes(secondMedia = true))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertUntouched(context)
    }

    @Test
    fun aMissingHandlerFailsThePatch() {
        val context = PatchContexts.of(classes(handlerMarker = "ClipsOrganicMoreOptionsHelper_somethingElse"))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryReel() }
        assertTrue(failure.message!!, failure.message!!.contains(HANDLER_MARKER))
    }

    /**
     * In each declared build, every builder of the reel menu gets the download check's filter once
     * and the flag's at most once, at least one has the flag, the reduced menu's list passes through
     * addTo() at each return and no jump skips that, the handler asks save() first, and every
     * bridge is written, the picture's among them.
     */
    @Test
    fun eachDeclaredBuildOffersDownloadOnEveryReel() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val types = setOf(
            MEDIA, USER, VIDEO_VERSION, PANDO_VIDEO_VERSION, IMAGE_INFO, PANDO_IMAGE_INFO, IMAGE_URL, OPTION,
            MUSIC_INFO, PANDO_MUSIC_INFO, TRACK_DATA, PANDO_TRACK_DATA, MUSIC_CONSUMPTION, PANDO_MUSIC_CONSUMPTION,
        )
        val markers = setOf(HANDLER_MARKER, ELIGIBLE_MARKER, REDUCED_MARKER, ROW_MARKER)
        // The types a post keeps its music in: interfaces whose getter answers the music.
        fun holdsMusic(classDef: ClassDef) = AccessFlags.INTERFACE.isSet(classDef.accessFlags) &&
            classDef.methods.any { it.parameterTypes.isEmpty() && it.returnType == MUSIC_INFO }
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>(ExtensionDex.classDef(INSTAGRAM_MEDIA))
                FixtureDex.forEach(bundle) { dex ->
                    val marked = dex.stringSection.any { it.startsWith("android_purge_") && PURGE_MARKER.find(it)?.groupValues?.get(1) in markers }
                    val loads = dex.fieldSection.any { it.toString() == DOWNLOAD }
                    if (!marked && !loads && dex.classes.none { it.type in types || holdsMusic(it) }) return@forEach
                    for (classDef in dex.classes) {
                        val wanted = classDef.type in types || holdsMusic(classDef) || classDef.methods.any { method ->
                            method.markers().any { it in markers } || method.code().any { it.referenceText() == DOWNLOAD }
                        }
                        if (wanted) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(classes)

                context.offerDownloadOnEveryReel()

                val all = classes.flatMap { it.methods }
                val handler = all.single { HANDLER_MARKER in it.markers() }
                val menu = context.method(handler.definingClass, handler.name).code()
                assertEquals("${bundle.name}: the handler's calls", listOf(OURS, SAVE),
                    menu.filter { it.opcode == Opcode.INVOKE_STATIC }.take(2).map { it.referenceText() })
                val adder = all.single { ROW_MARKER in it.markers() }
                val rows = context.method(adder.definingClass, adder.name, adder.parameterTypes.map(Any::toString)).code()
                assertEquals("${bundle.name}: the row adder's first call", ROWS,
                    rows.first { it.opcode == Opcode.INVOKE_STATIC_RANGE }.referenceText())
                val eligible = all.single { ELIGIBLE_MARKER in it.markers() }
                val check = "${eligible.definingClass}->${eligible.name}(${eligible.parameterTypes.joinToString("")})${eligible.returnType}"
                val builders = all.filter { method ->
                    val code = method.code()
                    code.any { it.referenceText() == DOWNLOAD } && code.any { it.referenceText() == check } &&
                        (method.definingClass == handler.definingClass ||
                            code.any { it.referenceText()?.startsWith("${handler.definingClass}->") == true })
                }
                assertTrue("${bundle.name}: no reel menu builder", builders.isNotEmpty())
                var flags = 0
                var toRows = 0
                builders.forEach { builder ->
                    val code = context.method(builder.definingClass, builder.name, builder.parameterTypes.map(Any::toString)).code()
                    val where = "${bundle.name}: ${builder.definingClass}->${builder.name}"
                    val rows = code.any { it.referenceText() == OFFER_ROW }
                    val (offer, withhold) = if (rows) OFFER_ROW to WITHHOLD_ROW else OFFER to WITHHOLD
                    assertEquals("$where offers", 1, code.count { it.referenceText() == offer })
                    assertTrue("$where mixes the two kinds of filter",
                        code.none { it.referenceText() in setOf(OFFER, WITHHOLD, OFFER_ROW, WITHHOLD_ROW) - setOf(offer, withhold) })
                    val withheld = code.count { it.referenceText() == withhold }
                    assertTrue("$where withholds $withheld times", withheld <= 1)
                    flags += withheld
                    if (rows) {
                        toRows++
                        // The player row's filter sits right after the check's move-result and is
                        // handed the Media register the check was called with, so a reel with no
                        // video file for the player is never let in, nor the divider after it.
                        val at = code.indexOfFirst { it.referenceText() == OFFER_ROW }
                        val call = code[at - 2]
                        assertEquals("$where: the check before the filter", check, call.referenceText())
                        val hook = code[at] as Instruction35c
                        val (answer, reel) = hook.registerC to hook.registerD
                        assertEquals("$where: the filter's arguments", 2, hook.registerCount)
                        assertEquals("$where: the check's answer", (code[at - 1] as OneRegisterInstruction).registerA, answer)
                        val types = eligible.parameterTypes.map(Any::toString)
                        val index = (if (AccessFlags.STATIC.isSet(eligible.accessFlags)) 0 else 1) + types.indexOf(MEDIA)
                        val handed = when (call) {
                            is RegisterRangeInstruction -> call.startRegister + index
                            else -> (call as Instruction35c).let { listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG)[index] }
                        }
                        assertEquals("$where: the reel handed to the filter", handed, reel)
                    }
                }
                assertTrue("${bundle.name}: no builder read the flag", flags > 0)
                assertTrue("${bundle.name}: no builder hands Download to the adder of one row", toRows > 0)
                val reduced = all.single { REDUCED_MARKER in it.markers() }
                val list = context.method(reduced.definingClass, reduced.name, reduced.parameterTypes.map(Any::toString)).code()
                val returns = list.indices.filter { list[it].opcode == Opcode.RETURN_OBJECT }
                assertTrue("${bundle.name}: the reduced menu doesn't return", returns.isNotEmpty())
                returns.forEach { at ->
                    assertEquals("${bundle.name}: before the reduced menu's return at $at", listOf(DOWNLOAD, ADD_TO),
                        listOf(list[at - 2].referenceText(), list[at - 1].referenceText()))
                }
                // Opcode.name is the smali name, "if-eqz" or "goto/16".
                list.indices.filter { index -> list[index] is OffsetInstruction && list[index].opcode.name.let { it.startsWith("if-") || it.startsWith("goto") } }
                    .forEach { jump -> assertTrue("${bundle.name}: the jump at $jump skips addTo()", list.target(jump) !in returns) }
                val bridges = context.classDefBy(INSTAGRAM_MEDIA).methods.filter { it.name in reelBridges }
                assertEquals("${bundle.name}: the reel's bridges", reelBridges.size, bridges.size)
                bridges.forEach { assertEquals("${bundle.name}: ${it.name}", Opcode.CHECK_CAST, it.code().first().opcode) }
                assertEquals("${bundle.name}: reelOption", DOWNLOAD, context.method(INSTAGRAM_MEDIA, "reelOption").code().first().referenceText())
                assertTrue("${bundle.name}: addReelRow", context.method(INSTAGRAM_MEDIA, "addReelRow").code().any { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The bridges this patch writes: the video's, and the picture's, which Download any story writes
     * too, the music's, and a carousel's pages, which Download any video writes too.
     */
    private val reelBridges = setOf(
        "videoVersions", "dashManifest", "mediaId", "owner", "takenAt", "username", "versionUrl", "versionWidth", "versionHeight",
        "imageVersions", "imageCandidates", "candidateUrl", "candidateWidth", "candidateHeight",
        "musicMetadata", "metadataMusic", "clipsMetadata", "clipsMusic", "musicTrack", "musicConsumption", "trackUrl",
        "trackFastStartUrl", "musicStartMs", "musicLengthMs", "carouselMedia",
    )

    private fun assertFiltered(code: List<Instruction>, call: String, hook: String, media: Int? = null) {
        val at = code.indexOfFirst { it.referenceText() == call }
        assertTrue("$call is not called", at >= 0)
        val register = (code[at + 1] as OneRegisterInstruction).registerA
        assertEquals("$call: after the move-result", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$call: the filter", hook, code[at + 2].referenceText())
        assertEquals("$call: the filter's argument", register, (code[at + 2] as Instruction35c).registerC)
        assertEquals("$call: the filter's arguments", if (media == null) 1 else 2, (code[at + 2] as Instruction35c).registerCount)
        if (media != null) assertEquals("$call: the reel handed over", media, (code[at + 2] as Instruction35c).registerD)
        assertEquals("$call: the filtered answer", register, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_RESULT, code[at + 3].opcode)
    }

    private fun assertUntouched(context: BytecodePatchContext) {
        assertTrue("a builder changed", context.method(helper, "A06").code().none { it.referenceText() == OFFER })
        assertTrue("a builder changed", context.method(controller, "A08").code().none { it.referenceText() == OFFER_ROW })
        assertTrue("a builder changed", context.method(controller, "A0Y").code().none { it.referenceText() == OFFER })
        assertTrue("the reduced menu changed", context.method(controller, "A03").code().none { it.referenceText() == ADD_TO })
        assertEquals("the handler changed", Opcode.CONST_STRING, context.method(helper, "A0T").code().first().opcode)
        assertEquals("a bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "videoVersions").code().first().opcode)
        assertEquals("a picture bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "imageVersions").code().first().opcode)
        assertEquals("a music bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "musicMetadata").code().first().opcode)
        assertEquals("the option bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "reelOption").code().first().opcode)
        assertTrue("the row adder changed", context.method(helper, "A0Q").code().none { it.referenceText() == ROWS })
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.single { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The index [branch] at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private fun classes(
        flagSkipsWhen: String = "if-nez",
        leaveOutField: String? = null,
        secondMedia: Boolean = false,
        handlerMarker: String = HANDLER_MARKER,
        reducedMarker: String = REDUCED_MARKER,
        leaveOutCandidates: Boolean = false,
        rowParameters: List<String> = rowTypes,
        handOff: String = toShorthand,
        checkCall: String = "invoke-virtual { v2, v2, v2 }, $check",
    ): List<ClassDef> {
        val helperFields = listOfNotNull(
            field(helper, "media", MEDIA),
            field(helper, "activity", activity),
            if (secondMedia) field(helper, "previous", MEDIA) else null,
        )
        val helperClass = classDef(
            helper,
            listOf(
                method(helper, "A0T", listOf(OPTION), "V", 5, static = false, body = """
                    const-string v0, "android_purge_26_q3_$handlerMarker"
                    return-void
                """),
                method(helper, "A06", listOf("Lfixture/Tree;", helper, "Ljava/util/List;", "I"), "V", 8, static = true, body = """
                    const-string v0, "android_purge_26_q3_ClipsDownloadUtil_shouldShowProducerDownloadControls"
                    iget-object v1, p1, $helper->media:$MEDIA
                    const/4 v2, 0x0
                    invoke-virtual { v2, v2, v1 }, $check
                    move-result v1
                    if-eqz v1, :skip
                    sget-object v1, $DOWNLOAD
                    invoke-interface { p2, v1 }, Ljava/util/List;->add(Ljava/lang/Object;)Z
                    :skip
                    return-void
                """),
                // Instagram's shorthand for one row: the adder with no icon and no label of its own.
                method(helper, "A0P", rowTypes.take(4), "V", 12, static = false, body = """
                    const/4 v5, 0x0
                    move-object v0, p0
                    move-object v1, p1
                    move-object v2, p2
                    move-object v3, p3
                    move-object v4, p4
                    move-object v6, v5
                    invoke-virtual/range { v0 .. v6 }, $helper->A0Q(${rowTypes.joinToString("")})V
                    return-void
                """),
                // A method of the helper's that takes an option and never reaches the adder.
                method(helper, "A0R", listOf(OPTION), "V", 2, static = false, body = "return-void"),
                method(helper, "A0S", emptyList(), "V", 1, static = false, body = "return-void"),
                // The adder of one row: 5 locals, then this and six parameters.
                method(helper, "A0Q", rowParameters, "V", 6 + rowParameters.size, static = false, body = """
                    const-string v0, "android_purge_26_q3_$ROW_MARKER"
                    return-void
                """),
            ),
            helperFields,
        )
        val controllerClass = classDef(
            controller,
            listOf(
                method(controller, "A08", emptyList(), "V", 12, static = false, body = """
                    const-string v0, "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_showRedesignBottomSheet_2"
                    iget-object v1, p0, $controller->helper:$helper
                    const/4 v2, 0x0
                    $checkCall
                    move-result v3
                    if-eqz v3, :skip
                    const-wide v4, 0x81034200060c62L
                    invoke-static { v2, v4, v5 }, Lfixture/MobileConfig;->A1A(Ljava/lang/Object;J)Z
                    move-result v3
                    $flagSkipsWhen v3, :skip
                    $handOff
                    :skip
                    return-void
                """),
                // The older sheet's builder, which hands Download to a menu of its own, never the adder.
                method(controller, "A0Y", emptyList(), "V", 7, static = false, body = """
                    const-string v0, "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_addNonAuthorSpecificRows"
                    iget-object v1, p0, $controller->helper:$helper
                    invoke-virtual { v1 }, $helper->A0S()V
                    const/4 v2, 0x0
                    invoke-virtual { v2, v2, v2 }, $check
                    move-result v3
                    if-eqz v3, :skip
                    invoke-static { v2 }, Lfixture/MobileConfig;->A1B(Ljava/lang/Object;)Z
                    move-result v3
                    if-nez v3, :skip
                    sget-object v3, $DOWNLOAD
                    invoke-direct { p0, v3 }, $controller->A0C($OPTION)V
                    :skip
                    return-void
                """),
                method(controller, "A0C", listOf(OPTION), "V", 2, static = false, body = "return-void"),
                // The reduced menu's list, which never had Download. Its return is also a jump's target.
                method(controller, "A03", listOf("Lfixture/ClipsItem;", MEDIA), "Ljava/util/ArrayList;", 6, static = false, body = """
                    const-string v0, "android_purge_26_q3_$reducedMarker"
                    new-instance v1, Ljava/util/ArrayList;
                    invoke-direct { v1 }, Ljava/util/ArrayList;-><init>()V
                    sget-object v0, $OPTION->PLAYBACK_CONTROLS:$OPTION
                    invoke-virtual { v1, v0 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                    const/4 v2, 0x0
                    if-eqz v2, :done
                    sget-object v0, $OPTION->REPORT:$OPTION
                    invoke-virtual { v1, v0 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                    :done
                    return-object v1
                """),
            ),
            listOf(field(controller, "helper", helper)),
        )
        val feed = classDef(feedSheet, listOf(feedSheetMethod()))
        val eligible = classDef(util, listOf(method(util, "A08", listOf(session, MEDIA), "Z", 4, static = false, body = """
            const-string v0, "android_purge_26_q3_$ELIGIBLE_MARKER"
            const/4 v0, 0x0
            return v0
        """)))
        val mediaGetters = listOf(
            "AAh" to ("video_versions" to "Ljava/util/List;"),
            "A8P" to ("video_dash_manifest" to "Ljava/lang/String;"),
            "A3Q" to ("user" to USER),
            "A6v" to ("taken_at" to "Ljava/lang/Long;"),
            "A3F" to ("image_versions2" to IMAGE_INFO),
            // Another getter of the user field, answering whether it's there: the answer's type tells them apart.
            "ALu" to ("user" to "Z"),
            "A2H" to ("music_metadata" to musicMetadata),
            "A33" to ("clips_metadata" to clipsMetadata),
            "ALU" to ("clips_metadata" to "Z"),
            "A8k" to ("carousel_media" to "Ljava/util/List;"),
        ).filter { it.second.first != leaveOutField }.map { (name, field) -> getter(MEDIA, name, field.first, field.second) } +
            method(MEDIA, "getId", emptyList(), "Ljava/lang/String;", 1, static = false, body = """
                const/4 v0, 0x0
                return-object v0
            """)
        val versionGetters = listOf("getUrl" to ("url" to "Ljava/lang/String;"), "DvO" to ("width" to "Ljava/lang/Integer;"),
            "CK7" to ("height" to "Ljava/lang/Integer;"))
        val imageGetters = if (leaveOutCandidates) emptyList() else listOf("Bd1" to ("candidates" to "Ljava/util/List;"))
        val musicGetters = listOf("CmW" to ("music_asset_info" to TRACK_DATA), "Cme" to ("music_consumption_info" to MUSIC_CONSUMPTION))
        // The title is another String the track keeps, so the field's key has to pick the address.
        val trackGetters = listOf("BRg" to ("progressive_download_url" to "Ljava/lang/String;"),
            "BTF" to ("fast_start_progressive_download_url" to "Ljava/lang/String;"), "getTitle" to ("title" to "Ljava/lang/String;"))
        val partGetters = listOf("BTI" to ("audio_asset_start_time_in_ms" to "Ljava/lang/Integer;"),
            "BwK" to ("overlap_duration_in_ms" to "Ljava/lang/Integer;")).filter { it.second.first != leaveOutField }
        return listOf(
            helperClass, controllerClass, feed, eligible,
            classDef(MEDIA, mediaGetters),
            classDef(USER, listOf(getter(USER, "A89", "username", "Ljava/lang/String;"))),
            anInterface(VIDEO_VERSION, versionGetters.map { it.first to it.second.second }),
            classDef(PANDO_VIDEO_VERSION, versionGetters.map { (name, field) -> getter(PANDO_VIDEO_VERSION, name, field.first, field.second) }),
            anInterface(IMAGE_INFO, imageGetters.map { it.first to it.second.second }),
            classDef(PANDO_IMAGE_INFO, imageGetters.map { (name, field) -> getter(PANDO_IMAGE_INFO, name, field.first, field.second) }),
            anInterface(IMAGE_URL, listOf("getUrl" to "Ljava/lang/String;", "getWidth" to "I", "getHeight" to "I")),
            anInterface(musicMetadata, listOf("Cmh" to MUSIC_INFO, "Apg" to "Lfixture/Other;")),
            anInterface(clipsMetadata, listOf("Cmh" to MUSIC_INFO, "CuR" to "Lfixture/OriginalSound;")),
            anInterface(MUSIC_INFO, musicGetters.map { it.first to it.second.second }),
            classDef(PANDO_MUSIC_INFO, musicGetters.map { (name, field) -> getter(PANDO_MUSIC_INFO, name, field.first, field.second) }),
            anInterface(TRACK_DATA, trackGetters.map { it.first to it.second.second }),
            classDef(PANDO_TRACK_DATA, trackGetters.map { (name, field) -> getter(PANDO_TRACK_DATA, name, field.first, field.second) }),
            anInterface(MUSIC_CONSUMPTION, partGetters.map { it.first to it.second.second }),
            classDef(PANDO_MUSIC_CONSUMPTION, partGetters.map { (name, field) -> getter(PANDO_MUSIC_CONSUMPTION, name, field.first, field.second) }),
            optionClass(),
            ExtensionDex.classDef(INSTAGRAM_MEDIA),
        )
    }

    /** Instagram's menu option: an enum whose constructor keeps the icon its getter answers. */
    private fun optionClass(): ClassDef = ImmutableClassDef(
        OPTION, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value,
        "Ljava/lang/Enum;", null, null, null, emptyList(), listOf(
            method(OPTION, "<init>", listOf("Ljava/lang/String;", "I", "I"), "V", 4, static = false, body = """
                invoke-direct { p0, p1, p2 }, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
                iput p3, p0, $OPTION->iconDrawable:I
                return-void
            """),
            method(OPTION, "getIconDrawable", emptyList(), "I", 2, static = false, body = """
                iget v0, p0, $OPTION->iconDrawable:I
                return v0
            """),
        ),
    )

    private fun anInterface(type: String, methods: List<Pair<String, String>>): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
        "Ljava/lang/Object;", null, null, null, null,
        methods.map { (name, returns) ->
            ImmutableMethod(type, name, emptyList(), returns, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
        },
    )

    /** The feed's menu: Download after the same check, built through a class that isn't the reel menu's. */
    private fun feedSheetMethod() = method(feedSheet, "invoke", emptyList(), "Ljava/lang/Object;", 4, static = false, body = """
        const/4 v0, 0x0
        invoke-virtual { v0, v0, v0 }, $check
        move-result v1
        if-eqz v1, :skip
        sget-object v1, $DOWNLOAD
        :skip
        return-object v0
    """)

    private fun feedSheetCode() = feedSheetMethod().code()

    private fun getter(owner: String, name: String, field: String, returns: String) =
        method(owner, name, emptyList(), returns, 2, static = false, body = """
            const v0, ${field.hashCode()}
            const/4 v0, 0x0
            ${if (returns == "Z") "return v0" else "return-object v0"}
        """)

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)
}
