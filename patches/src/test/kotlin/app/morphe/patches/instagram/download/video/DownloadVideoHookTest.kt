/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

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
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import app.morphe.patches.instagram.download.PANDO_VIDEO_VERSION
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.VIDEO_VERSION
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.ELIGIBLE_MARKER
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.share.REPOSTS_FEED_UFI
import app.morphe.patches.instagram.share.REPOSTS_UFI_ICON_ID
import app.morphe.patches.instagram.share.findFeedUfiSite
import app.morphe.patches.instagram.share.hideFeedUfi
import app.morphe.patches.shared.compat.AppCompatibilities
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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadVideoHookTest {
    private val helper = "Lfixture/FeedMenu;"
    private val lambda = "Lfixture/MergedLambda;"
    private val util = "Lfixture/DownloadUtil;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val activity = "Landroidx/fragment/app/FragmentActivity;"
    private val check = "$util->A08($session$MEDIA)Z"
    private val flag = "Lfixture/MobileConfig;->A1A(Ljava/lang/Object;J)Z"
    private val state = "Lfixture/State;"
    private val kind = "Lfixture/Kind;"
    private val contextType = "Landroid/content/Context;"
    private val mine = "Lfixture/Owner;->mine(Ljava/lang/Object;)Z"
    private val adder = "$state->A00($kind$OPTION${state}Ljava/lang/CharSequence;Ljava/util/ArrayList;Z)V"
    private val shortMenu = "Lfixture/ShortMenu;"
    private val itemState = "Lfixture/ItemState;"
    private val pager = "Lfixture/Pager;"
    private val elsewhere = "Lfixture/Elsewhere;"
    private val pageLookup = "$MEDIA_EXT->A0Q($MEDIA" + "I)$MEDIA"
    private val outline = "Lfixture/Outlined;"

    /** The hooks the patch writes are in the extension the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(OFFER_VIDEO, SAVE_VIDEO, ALLOW_VIDEO, OFFER_ALL, SAVE_ALL, ALL_OPTION, OWN_POST, OFFER_PLAYER, PLAYER_OPTION, PLAY_VIDEO,
            OFFER_DETAILS, DETAILS_OPTION, SHOW_DETAILS, OFFER_COVER, COVER_OPTION, SAVE_COVER)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * Anyone else's rows start with offer(), handed the state and the row list, and the jump that
     * went to those rows goes to it. Your own rows are as Instagram builds them.
     */
    @Test
    fun anyoneElsesRowsStartWithOffer() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val code = context.method(lambda, "invoke").code()
        val offer = code.indexOfFirst { it.referenceText() == OFFER_VIDEO }
        val call = code[offer] as Instruction35c
        assertEquals("offer()'s arguments", listOf(0, 3), listOf(call.registerC, call.registerD))
        assertEquals("anyone else's first row follows", "$state->other:Ljava/lang/Object;", code[offer + 1].referenceText())
        val owner = code.indexOfFirst { it.referenceText() == mine }
        assertEquals("the owner check's jump", offer, code.target(owner + 2))
        assertEquals("seven separate actions in the builder", 7, code.count { it.referenceText()?.startsWith("Lapp/hushgram/") == true })
    }

    /**
     * Your own post's download check hands its answer and the menu's state to ownPost(), which
     * answers in the same register before the branch to the Download row reads it (#57).
     */
    @Test
    fun yourOwnPostsCheckAsksOwnPost() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val code = context.method(lambda, "invoke").code()
        val checked = code.indexOfFirst { it.referenceText() == check }
        assertEquals("the check's answer", Opcode.MOVE_RESULT, code[checked + 1].opcode)
        val own = code[checked + 2] as Instruction35c
        assertEquals(OWN_POST, own.referenceText())
        assertEquals("ownPost()'s arguments: the answer, then the state", listOf(1, 0), listOf(own.registerC, own.registerD))
        assertEquals(Opcode.MOVE_RESULT, code[checked + 3].opcode)
        assertEquals("the answer goes back where the branch reads it", 1, (code[checked + 3] as OneRegisterInstruction).registerA)
        assertEquals("the branch to the Download row", Opcode.IF_EQZ, code[checked + 4].opcode)
        assertEquals("one ownPost() call", 1, code.count { it.referenceText() == OWN_POST })
    }

    /**
     * Past the check's yes, the menu state's flag that sends Instagram's download to the share
     * sheet goes to ownPostRow() with the state, and its answer goes back where the branch to the
     * Download row reads it (#57).
     */
    @Test
    fun yourOwnPostsShareSheetFlagAsksOwnPostRow() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val code = context.method(lambda, "invoke").code()
        val flagged = code.indexOfFirst { it.referenceText() == "$state->flagged:Z" }
        val row = code[flagged + 1] as Instruction35c
        assertEquals(OWN_POST_ROW, row.referenceText())
        assertEquals("ownPostRow()'s arguments: the flag, then the state", listOf(1, 0), listOf(row.registerC, row.registerD))
        assertEquals(Opcode.MOVE_RESULT, code[flagged + 2].opcode)
        assertEquals("the answer goes back where the branch reads it", 1, (code[flagged + 2] as OneRegisterInstruction).registerA)
        val branch = flagged + 4
        assertEquals("the branch to the Download row", Opcode.IF_EQZ, code[branch].opcode)
        assertEquals(DOWNLOAD, code[code.target(branch)].referenceText())
        assertEquals("one ownPostRow() call", 1, code.count { it.referenceText() == OWN_POST_ROW })
    }

    /** A flag whose 0 doesn't lead to the Download row isn't the one that moves it, and nothing changes. */
    @Test
    fun aShareSheetFlagThatSkipsTheRowFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(flagSkipsRow = true))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertTrue(failure.message!!, failure.message!!.contains("doesn't jump ahead to the Download row"))
        assertUntouched(context)
    }

    /** A check handed a post that isn't the menu state's can't be answered for that post, and nothing changes. */
    @Test
    fun anOwnCheckOfAnotherPostFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(ownPostFromState = false))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertTrue(failure.message!!, failure.message!!.contains("doesn't read the post it checks"))
        assertUntouched(context)
    }

    /**
     * The row bridge adds Instagram's Download row the way the builder does: the row kind, the
     * option, the state, the label read by its resource id and the list, in the adder's order, and
     * false last. The post bridge reads the state's post.
     */
    @Test
    fun theBridgesAddInstagramsDownloadRow() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val bridge = context.method(INSTAGRAM_MEDIA, "addDownloadRow")
        assertEquals("registers", 9, bridge.implementation!!.registerCount)
        val code = bridge.code()
        assertEquals(Opcode.CHECK_CAST, code.first().opcode)
        assertEquals(listOf("$kind->A05:$kind", DOWNLOAD), code.filter { it.opcode == Opcode.SGET_OBJECT }.map { it.referenceText() })
        assertEquals("$state->context:$contextType", code.single { it.opcode == Opcode.IGET_OBJECT }.referenceText())
        assertEquals("the label", 0x7f131703, code.filterIsInstance<NarrowLiteralInstruction>().single { it.narrowLiteral != 0 }.narrowLiteral)
        val add = code.single { it.referenceText() == adder } as RegisterRangeInstruction
        assertEquals("the range", 0 to 6, add.startRegister to add.registerCount)
        assertEquals(Opcode.RETURN_VOID, code.last().opcode)

        val media = context.method(INSTAGRAM_MEDIA, "feedMenuMedia").code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), media.take(3).map { it.opcode })
        assertEquals("$state->media:$MEDIA", media[1].referenceText())
    }

    /**
     * The carousel bridges read the post's feed state off the builder's state, the page that state
     * keeps (the field most calls to the page lookup are handed) and a Media's pages.
     */
    @Test
    fun theCarouselBridgesReadThePageOnScreen() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val item = context.method(INSTAGRAM_MEDIA, "feedMenuItemState").code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), item.take(3).map { it.opcode })
        assertEquals("$state->item:$itemState", item[1].referenceText())
        val index = context.method(INSTAGRAM_MEDIA, "carouselIndex").code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET, Opcode.RETURN), index.take(3).map { it.opcode })
        assertEquals(itemState, index[0].referenceText())
        assertEquals("the page, not the field read less often", "$itemState->page:I", index[1].referenceText())
        val pages = context.method(INSTAGRAM_MEDIA, "carouselMedia").code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), pages.take(4).map { it.opcode })
        assertEquals("$MEDIA->A8k()Ljava/util/List;", pages[1].referenceText())
    }

    /** A photo post's picture is read through the same bridges Download stories writes. */
    @Test
    fun thePictureBridgesAreWritten() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        assertEquals("$MEDIA->A3F()$IMAGE_INFO", context.method(INSTAGRAM_MEDIA, "imageVersions").code()[1].referenceText())
        assertEquals("$IMAGE_INFO->Bd1()Ljava/util/List;", context.method(INSTAGRAM_MEDIA, "imageCandidates").code()[1].referenceText())
        assertEquals("$IMAGE_URL->getWidth()I", context.method(INSTAGRAM_MEDIA, "candidateWidth").code()[1].referenceText())
    }

    /** A build whose caption can't be told still gets Download and Details, without Copy caption's bridges. */
    @Test
    fun aCaptionItCantFindLeavesItsBridgesUnwritten() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        for (name in listOf("caption", "captionText")) {
            assertEquals(name, Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, name).code().first().opcode)
        }
        assertEquals(Opcode.CHECK_CAST, context.method(INSTAGRAM_MEDIA, "videoVersions").code().first().opcode)
    }

    /**
     * A tap on Download asks save() first, with the post, the post's feed state and the menu's
     * activity; any other option goes on.
     */
    @Test
    fun theHandlerAsksSaveFirst() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val code = context.method(helper, "A09").code().drop(44)
        assertEquals(
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.SGET_OBJECT, Opcode.IF_NE, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                Opcode.IF_EQZ, Opcode.RETURN_VOID,
            ),
            code.take(12).map { it.opcode },
        )
        assertEquals(DOWNLOAD, code[1].referenceText())
        assertEquals("the getter that reads the post", "$helper->A01($helper)$MEDIA", code[4].referenceText())
        assertEquals("$helper->activity:$activity", code[6].referenceText())
        assertEquals("the post's feed state", "$helper->item:$itemState", code[7].referenceText())
        val save = code[8] as Instruction35c
        assertEquals(SAVE_VIDEO, save.referenceText())
        assertEquals("save()'s arguments", listOf(1, 0, 2), listOf(save.registerC, save.registerD, save.registerE))
        assertEquals("the original code moved", Opcode.CONST_STRING, code[12].opcode)
        for (branch in listOf(2, 10)) assertEquals("the branch at $branch", 12, code.target(branch))
    }

    /** The separate batch action never changes the enum's native constants or Download's route. */
    @Test
    fun saveAllHasItsOwnIdentityAndIsOfferedBeforeTheOwnershipSplit() {
        val original = classes()
        val context = PatchContexts.of(original)
        val before = context.classDefBy(OPTION).methods.map { it.code().map { instruction -> instruction.referenceText() } }
        context.offerDownloadOnEveryVideo()
        assertEquals(before, context.classDefBy(OPTION).methods.map { it.code().map { instruction -> instruction.referenceText() } })
        val factory = context.method(INSTAGRAM_MEDIA, "saveAllOption").code()
        assertEquals(DOWNLOAD, factory.first().referenceText())
        assertTrue(factory.any { it.referenceText() == "Ljava/lang/Enum;->ordinal()I" })
        assertTrue(factory.any { it.opcode == Opcode.NEW_INSTANCE && it.referenceText() == OPTION })
        assertTrue(factory.any { it.opcode == Opcode.INVOKE_DIRECT && it.referenceText() == "$OPTION-><init>(Ljava/lang/String;II)V" })
        assertTrue("the enum arrays were edited", factory.none { it.opcode == Opcode.SPUT_OBJECT })
        val row = context.method(INSTAGRAM_MEDIA, "addSaveAllRow").code()
        assertTrue("the separate row reused Download's identity", row.none { it.referenceText() == DOWNLOAD })
        assertEquals("same native row adder", adder, row.single { it.opcode == Opcode.INVOKE_STATIC_RANGE }.referenceText())
        assertTrue("the native adder wasn't handed the localized title", row.any {
            it.opcode == Opcode.MOVE_OBJECT && (it as TwoRegisterInstruction).registerA == 3 && it.registerB == 10
        })
        val builder = context.method(lambda, "invoke").code()
        val all = builder.indexOfFirst { it.referenceText() == OFFER_ALL }
        val cast = builder.indexOfFirst { it.opcode == Opcode.CHECK_CAST && it.referenceText() == state }
        assertEquals("both ownership paths reach Save all", cast + 1, all)
        assertTrue(all < builder.indexOfFirst { it.referenceText() == mine })
        val menu = context.method(helper, "A09").code()
        assertEquals(ALL_OPTION, menu[1].referenceText())
        assertEquals(SAVE_ALL, menu[9].referenceText())
        assertEquals("the batch tap stops native dispatch", Opcode.RETURN_VOID, menu[10].opcode)
        for (branch in listOf(3, 4)) assertEquals("native options reach the player check", 11, menu.target(branch))
    }

    /**
     * Open in another player has an option of its own, offered right after Download cover on both
     * ownership paths, and a tap on it goes to play() with the post, its feed state and the activity.
     */
    @Test
    fun openInAnotherPlayerHasItsOwnOptionAndTap() {
        val context = PatchContexts.of(classes())
        context.offerDownloadOnEveryVideo()
        val factory = context.method(INSTAGRAM_MEDIA, "feedOption").code()
        assertEquals(DOWNLOAD, factory.first().referenceText())
        assertTrue(factory.any { it.opcode == Opcode.INVOKE_DIRECT && it.referenceText() == "$OPTION-><init>(Ljava/lang/String;II)V" })
        assertTrue("the name isn't the one handed over", factory.any {
            it.opcode == Opcode.MOVE_OBJECT && (it as TwoRegisterInstruction).registerA == 1 && it.registerB == 4
        })
        val builder = context.method(lambda, "invoke").code()
        val cover = builder.indexOfFirst { it.referenceText() == OFFER_COVER }
        assertEquals("offered right after Download cover", cover + 1, builder.indexOfFirst { it.referenceText() == OFFER_PLAYER })
        assertEquals(1, builder.count { it.referenceText() == OFFER_PLAYER })
        val offer = builder[cover + 1] as Instruction35c
        val offerCover = builder[cover] as Instruction35c
        assertEquals("the same state and rows", listOf(offerCover.registerC, offerCover.registerD), listOf(offer.registerC, offer.registerD))
        val menu = context.method(helper, "A09").code()
        assertEquals(PLAYER_OPTION, menu[11].referenceText())
        assertEquals("the post's feed state", "$helper->item:$itemState", menu[19].referenceText())
        val play = menu[20] as Instruction35c
        assertEquals(PLAY_VIDEO, play.referenceText())
        assertEquals("play()'s arguments", listOf(1, 0, 2), listOf(play.registerC, play.registerD, play.registerE))
        assertEquals("the player tap stops native dispatch", Opcode.RETURN_VOID, menu[21].opcode)
        for (branch in listOf(13, 14)) assertEquals("other options reach the Details check", 22, menu.target(branch))
    }

    /**
     * Details has an option of its own too, offered right after Open in another player on both
     * ownership paths, and a tap on it goes to show() with the post, its feed state and the
     * activity. Every other option goes on to the Download cover check.
     */
    @Test
    fun detailsHasItsOwnOptionAndTap() {
        val context = PatchContexts.of(classes())
        context.offerDownloadOnEveryVideo()
        val builder = context.method(lambda, "invoke").code()
        val player = builder.indexOfFirst { it.referenceText() == OFFER_PLAYER }
        assertEquals("offered right after the player row", player + 1, builder.indexOfFirst { it.referenceText() == OFFER_DETAILS })
        assertEquals(1, builder.count { it.referenceText() == OFFER_DETAILS })
        val offer = builder[player + 1] as Instruction35c
        val offerPlayer = builder[player] as Instruction35c
        assertEquals("the same state and rows", listOf(offerPlayer.registerC, offerPlayer.registerD), listOf(offer.registerC, offer.registerD))
        val menu = context.method(helper, "A09").code()
        assertEquals(DETAILS_OPTION, menu[22].referenceText())
        assertEquals("the post's feed state", "$helper->item:$itemState", menu[30].referenceText())
        val show = menu[31] as Instruction35c
        assertEquals(SHOW_DETAILS, show.referenceText())
        assertEquals("show()'s arguments", listOf(1, 0, 2), listOf(show.registerC, show.registerD, show.registerE))
        assertEquals("the Details tap stops native dispatch", Opcode.RETURN_VOID, menu[32].opcode)
        for (branch in listOf(24, 25)) assertEquals("other options reach the Download cover check", 33, menu.target(branch))
    }

    /**
     * Download cover has an option of its own (#94), offered right after Save all on both
     * ownership paths, and a tap on it goes to saveCover() with the post, its feed state and the
     * activity. Every other option goes on to the current page's Download.
     */
    @Test
    fun downloadCoverHasItsOwnOptionAndTap() {
        val context = PatchContexts.of(classes())
        context.offerDownloadOnEveryVideo()
        val builder = context.method(lambda, "invoke").code()
        val all = builder.indexOfFirst { it.referenceText() == OFFER_ALL }
        assertEquals("offered right after Save all", all + 1, builder.indexOfFirst { it.referenceText() == OFFER_COVER })
        assertEquals(1, builder.count { it.referenceText() == OFFER_COVER })
        val offer = builder[all + 1] as Instruction35c
        val offerAll = builder[all] as Instruction35c
        assertEquals("the same state and rows", listOf(offerAll.registerC, offerAll.registerD), listOf(offer.registerC, offer.registerD))
        val menu = context.method(helper, "A09").code()
        assertEquals(COVER_OPTION, menu[33].referenceText())
        assertEquals("the getter that reads the post", "$helper->A01($helper)$MEDIA", menu[38].referenceText())
        assertEquals("$helper->activity:$activity", menu[40].referenceText())
        assertEquals("the post's feed state", "$helper->item:$itemState", menu[41].referenceText())
        val save = menu[42] as Instruction35c
        assertEquals(SAVE_COVER, save.referenceText())
        assertEquals("saveCover()'s arguments", listOf(1, 0, 2), listOf(save.registerC, save.registerD, save.registerE))
        assertEquals("the cover tap stops native dispatch", Opcode.RETURN_VOID, menu[43].opcode)
        for (branch in listOf(35, 36)) assertEquals("native options reach current-page handling", 44, menu.target(branch))
        assertEquals(DOWNLOAD, menu[45].referenceText())
    }

    /** Unknown enum initialization and ambiguous or branching entry anchors cannot write half a patch. */
    @Test
    fun unknownBatchOptionOrEntryFailsBeforeAnythingChanges() {
        for ((case, classes) in listOf(
            "not an enum" to classes(optionEnum = false),
            "icon initialized from the ordinal" to classes(optionInitializesIcon = false),
            "no state cast" to classes(stateCasts = 0),
            "two state casts" to classes(stateCasts = 2),
            "branch between list and state" to classes(branchBeforeStateCast = true),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(case, PatchException::class.java) { context.offerDownloadOnEveryVideo() }
            assertUntouched(context)
            assertEquals("$case: the option bridge changed", Opcode.CONST_4,
                context.method(INSTAGRAM_MEDIA, "saveAllOption").code().first().opcode)
            assertEquals("$case: the batch row bridge changed", Opcode.RETURN_VOID,
                context.method(INSTAGRAM_MEDIA, "addSaveAllRow").code().first().opcode)
            assertEquals("$case: the player option bridge changed", Opcode.CONST_4,
                context.method(INSTAGRAM_MEDIA, "feedOption").code().first().opcode)
        }
    }

    /**
     * A carousel page the patch can't place fails it before anything changes: two fields read as
     * often, no page lookup reading a carousel's pages, or the feed state missing from the menu.
     */
    @Test
    fun aPageItCantPlaceFailsBeforeAnythingChanges() {
        for ((case, classes) in listOf(
            "two fields as often" to classes(pageReads = 2 to 2),
            "no page lookup" to classes(lookupReadsPages = false),
            "no feed state on the menu" to classes(menuHoldsItem = false),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(case, PatchException::class.java) { context.offerDownloadOnEveryVideo() }
            assertUntouched(context)
            assertEquals("$case: the page bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "carouselIndex").code().first().opcode)
        }
    }

    /**
     * Each return of the short menu's list hands the list to allow() first, with Download in a
     * register the list isn't in, and takes back what it answers. A jump that went to a return goes
     * to its hook.
     */
    @Test
    fun theShortMenuListAsksAllowAtEachReturn() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val code = context.method(shortMenu, "A01").code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("the returns", 2, returns.size)
        for ((at, list) in returns.zip(listOf(1, 0))) {
            val spare = if (list == 0) 1 else 0
            assertEquals("the option at $at", DOWNLOAD, code[at - 3].referenceText())
            assertEquals("its register", spare, (code[at - 3] as OneRegisterInstruction).registerA)
            val call = code[at - 2] as Instruction35c
            assertEquals(ALLOW_VIDEO, call.referenceText())
            assertEquals("allow()'s arguments at $at", listOf(list, spare), listOf(call.registerC, call.registerD))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
            assertEquals("the list taken back at $at", list, (code[at - 1] as OneRegisterInstruction).registerA)
            assertEquals("the list returned at $at", list, (code[at] as OneRegisterInstruction).registerA)
        }
        val jump = code.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals("the jump to the last return", returns.last() - 3, code.target(jump))
        assertEquals("allow() calls", 2, code.count { it.referenceText() == ALLOW_VIDEO })
    }

    /** A short menu list the patch can't tell apart or can't hook fails the patch before anything changes. */
    @Test
    fun aShortMenuListItCantHookFailsBeforeAnythingChanges() {
        for ((case, classes) in listOf(
            "no list" to classes(shortLists = 0),
            "two lists" to classes(shortLists = 2),
            "one local register" to classes(shortListRegisters = 2),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(case, PatchException::class.java) { context.offerDownloadOnEveryVideo() }
            assertUntouched(context)
            if (case != "no list") {
                assertTrue("$case: the list changed", context.method(shortMenu, "A01").code().none { it.referenceText() == ALLOW_VIDEO })
            }
        }
    }

    /** Rows after Download that the download check can reach aren't anyone else's for sure, and nothing changes. */
    @Test
    fun aJumpAfterTheCheckFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(lateJump = true))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertUntouched(context)
    }

    /**
     * A builder that never touches the menu itself still gets offer(): it's found by its row adder,
     * a static method of the state the menu keeps a field of. 450's 385611395 build merged the
     * builder somewhere it never touches the menu (#77).
     */
    @Test
    fun aBuilderThatNeverTouchesTheMenuIsFoundByItsState() {
        val context = PatchContexts.of(classes(builderUsesMenu = false))

        context.offerDownloadOnEveryVideo()

        val code = context.method(lambda, "invoke").code()
        val offer = code.indexOfFirst { it.referenceText() == OFFER_VIDEO }
        assertEquals("offer() calls", 1, code.count { it.referenceText() == OFFER_VIDEO })
        assertEquals("anyone else's first row follows", "$state->other:Ljava/lang/Object;", code[offer + 1].referenceText())
    }

    /** A builder adding rows of a state the menu doesn't keep isn't the feed menu's, and nothing changes. */
    @Test
    fun aBuilderOfAStateTheMenuDoesntKeepFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(menuKeepsState = false))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertTrue(failure.message!!, failure.message!!.contains("expected one builder of the feed menu"))
        assertUntouched(context)
    }

    /**
     * A builder that makes its list and reads Download's label through methods R8 outlined, as
     * 450's 385611400 build does, gets every row in the same places, and the row bridge reads the
     * label by the same resource id itself (#77).
     */
    @Test
    fun anOutlinedListAndLabelStillGetEveryRow() {
        val context = PatchContexts.of(classes(outlinedList = true, outlinedLabel = true, builderUsesMenu = false))

        context.offerDownloadOnEveryVideo()

        val code = context.method(lambda, "invoke").code()
        val offer = code[code.indexOfFirst { it.referenceText() == OFFER_VIDEO }] as Instruction35c
        assertEquals("offer()'s arguments", listOf(0, 3), listOf(offer.registerC, offer.registerD))
        val cast = code.indexOfFirst { it.opcode == Opcode.CHECK_CAST && it.referenceText() == state }
        assertEquals("Save all right after the state cast", OFFER_ALL, code[cast + 1].referenceText())
        assertEquals("one ownPost() call", 1, code.count { it.referenceText() == OWN_POST })
        val bridge = context.method(INSTAGRAM_MEDIA, "addDownloadRow").code()
        assertEquals("the label", 0x7f131703, bridge.filterIsInstance<NarrowLiteralInstruction>().single { it.narrowLiteral != 0 }.narrowLiteral)
        assertTrue("the bridge reads the label itself", bridge.any { it.referenceText() == "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;" })
    }

    /** A method of an outline's shape that does something else isn't taken for one, and nothing changes. */
    @Test
    fun anOutlineThatDoesSomethingElseFailsBeforeAnythingChanges() {
        for ((case, classes) in listOf(
            "the list" to classes(outlinedList = true, outlinesOnlyDoThat = false),
            "the label" to classes(outlinedLabel = true, outlinesOnlyDoThat = false),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(case, PatchException::class.java) { context.offerDownloadOnEveryVideo() }
            assertUntouched(context)
        }
    }

    @Test
    fun aMissingMenuClassFailsThePatch() {
        val context = PatchContexts.of(classes(name = "SomethingElse"))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertTrue(failure.message!!, failure.message!!.contains(FEED_HELPER_NAME))
    }

    @Test
    fun aHandlerWithoutLocalsFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(handlerRegisters = 3))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertUntouched(context)
    }

    /**
     * In each declared build, the feed menu's class is found by its kept name, its builder calls
     * offer() once, where a jump from before the download check lands after the Download row, the
     * handler asks save() first, and the post, row, video and picture bridges are written.
     */
    @Test
    fun eachDeclaredBuildOffersDownloadOnEveryVideo() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                offersDownloadOnEveryVideo(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The same holds in the other arm64 builds of each declared version, where R8 merged the
     * builder elsewhere and outlined its list and label in some (#77).
     */
    @Test
    fun eachOtherBuildOffersDownloadOnEveryVideo() {
        for (apk in Fixtures.otherBuilds()) offersDownloadOnEveryVideo(apk, apk.parentFile.name)
    }

    private fun offersDownloadOnEveryVideo(bundle: File, label: String) {
        val types = setOf(MEDIA, USER, VIDEO_VERSION, PANDO_VIDEO_VERSION, IMAGE_INFO, PANDO_IMAGE_INFO, IMAGE_URL, MEDIA_EXT, OPTION)
        val classes = mutableListOf<ClassDef>(ExtensionDex.classDef(INSTAGRAM_MEDIA))
        FixtureDex.forEach(bundle) { dex ->
            val marked = dex.stringSection.any { it.startsWith("android_purge_") && PURGE_MARKER.find(it)?.groupValues?.get(1) == ELIGIBLE_MARKER }
            val loads = dex.fieldSection.any { it.toString() == DOWNLOAD }
            val named = dex.stringSection.any { it == FEED_HELPER_NAME }
            val options = dex.fieldSection.any { it.toString() == WHY_OPTION }
            val pages = dex.methodSection.any { it.definingClass == MEDIA_EXT }
            if (!marked && !loads && !named && !options && !pages && dex.classes.none { it.type in types }) return@forEach
            for (classDef in dex.classes) {
                val wanted = classDef.type in types || classDef.originalName() == FEED_HELPER_NAME || classDef.methods.any { method ->
                    ELIGIBLE_MARKER in method.markers() || method.code().any { it.referenceText() == DOWNLOAD } ||
                        method.isShortMenuList() || method.pageReads().isNotEmpty()
                }
                if (wanted) classes += ImmutableClassDef.of(classDef)
            }
        }
        // The caption's comment type, from Media's getter that holds the key's hash, and its
        // classes, which Copy caption's bridges read.
        val caption = classes.single { it.type == MEDIA }.methods.single { method ->
            method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
                method.code().any { it is NarrowLiteralInstruction && it.narrowLiteral == "caption".hashCode() }
        }
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if ((classDef.type == caption.returnType || caption.returnType in classDef.interfaces) && classes.none { it.type == classDef.type }) {
                    classes += ImmutableClassDef.of(classDef)
                }
            }
        }
        // The static methods R8 outlined `new ArrayList()` and getString into, in a build that has them.
        val outlined = classes.asSequence().flatMap { it.methods.asSequence() }.flatMap { it.code().asSequence() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { (it.returnType == "Ljava/util/ArrayList;" && it.parameterTypes.isEmpty()) ||
                (it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/res/Resources;", "I")) }
            .map { it.definingClass }.filter { type -> classes.none { it.type == type } }.toSet()
        if (outlined.isNotEmpty()) classes += FixtureDex.classes(bundle, outlined).values
        // The Feed action-row binder the button hook is in, and the classes it takes (its holder, row state and feed state).
        val binders = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any { it is NarrowLiteralInstruction && it.opcode == Opcode.CONST && it.narrowLiteral == REPOSTS_UFI_ICON_ID } == true
        }
        val feed = binders.flatMap { listOf(it.definingClass) + it.parameterTypes.map(Any::toString) }.filter { type -> classes.none { it.type == type } }.toSet()
        if (feed.isNotEmpty()) classes += FixtureDex.classes(bundle, feed).values
        val context = PatchContexts.of(classes)

        val page = context.offerDownloadOnEveryVideo()
        assertFeedDownloadButton(context, page, label)

        // Copy caption reads Media's caption, then the comment's text through its interface,
        // by the name the tree-backed class that holds the text's hash gives the getter.
        val captionRead = context.method(INSTAGRAM_MEDIA, "caption").code()
        assertEquals("$label: caption", "$MEDIA->${caption.name}()${caption.returnType}", captionRead[1].referenceText())
        val textRead = context.method(INSTAGRAM_MEDIA, "captionText").code()
        assertEquals("$label: the caption's type", caption.returnType, textRead[0].referenceText())
        assertEquals("$label: through the interface", Opcode.INVOKE_INTERFACE, textRead[1].opcode)
        val textGetter = textRead[1].referenceText()!!
        assertTrue("$label: $textGetter", textGetter.startsWith("${caption.returnType}->") && textGetter.endsWith("()Ljava/lang/String;"))
        val tree = classes.filter { caption.returnType in it.interfaces }.single { type ->
            type.methods.any { "${caption.returnType}->${it.name}()${it.returnType}" == textGetter &&
                it.code().any { instruction -> instruction is NarrowLiteralInstruction && instruction.narrowLiteral == "text".hashCode() } }
        }
        assertTrue("$label: a tree-backed caption", tree.superclass != "Ljava/lang/Object;")

        val menu = classes.single { it.originalName() == FEED_HELPER_NAME }
        val handler = menu.methods.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf(OPTION) && it.returnType == "V" }
        val handled = context.method(menu.type, handler.name, listOf(OPTION)).code()
        val save = handled.indexOfFirst { it.referenceText() == SAVE_VIDEO }
        assertEquals("$label: the current-page save", SAVE_VIDEO, handled[save].referenceText())
        assertEquals("$label: one batch tap", 1, handled.count { it.referenceText() == SAVE_ALL })
        assertEquals("$label: one player tap", 1, handled.count { it.referenceText() == PLAY_VIDEO })
        assertEquals("$label: one Details tap", 1, handled.count { it.referenceText() == SHOW_DETAILS })
        assertEquals("$label: one Download cover tap", 1, handled.count { it.referenceText() == SAVE_COVER })
        val constructor = context.method(INSTAGRAM_MEDIA, "saveAllOption").code().single { it.opcode == Opcode.INVOKE_DIRECT }
        assertEquals("$label: direct native construction", "$OPTION-><init>(Ljava/lang/String;II)V", constructor.referenceText())
        assertEquals("$label: the native option class was preserved", classes.single { it.type == OPTION }.methods.map { it.code().map { instruction -> instruction.referenceText() } },
            context.classDefBy(OPTION).methods.map { it.code().map { instruction -> instruction.referenceText() } })
        // The menu hands save() the feed state whose page the index bridge reads.
        val itemType = handled[save - 1].referenceText()!!.substringAfterLast(':')
        val index = context.method(INSTAGRAM_MEDIA, "carouselIndex").code()
        assertEquals("$label: the page's class", itemType, index[0].referenceText())
        assertEquals("$label: the page", Opcode.IGET, index[1].opcode)
        assertTrue("$label: the page is on the feed state", index[1].referenceText()!!.startsWith("$itemType->"))
        val item = context.method(INSTAGRAM_MEDIA, "feedMenuItemState").code()
        assertTrue("$label: the builder's feed state", item[1].referenceText()!!.endsWith(":$itemType"))
        assertEquals("$label: the carousel bridge", Opcode.INVOKE_VIRTUAL, context.method(INSTAGRAM_MEDIA, "carouselMedia").code()[1].opcode)
        val builders = classes.flatMap { it.methods }.filter { method ->
            context.method(method.definingClass, method.name, method.parameterTypes.map(Any::toString)).code().any { it.referenceText() == OFFER_VIDEO }
        }
        assertEquals("$label: the builders offering Download", 1, builders.size)
        val code = context.method(builders.single().definingClass, builders.single().name, builders.single().parameterTypes.map(Any::toString)).code()
        assertEquals("$label: offer() calls", 1, code.count { it.referenceText() == OFFER_VIDEO })
        assertEquals("$label: Save all offered once", 1, code.count { it.referenceText() == OFFER_ALL })
        assertEquals("$label: Download cover offered once, right after it",
            code.indexOfFirst { it.referenceText() == OFFER_ALL } + 1, code.indexOfLast { it.referenceText() == OFFER_COVER })
        assertEquals("$label: the player offered once, right after Download cover",
            code.indexOfFirst { it.referenceText() == OFFER_COVER } + 1, code.indexOfLast { it.referenceText() == OFFER_PLAYER })
        assertEquals("$label: Details offered once, right after the player",
            code.indexOfFirst { it.referenceText() == OFFER_PLAYER } + 1, code.indexOfLast { it.referenceText() == OFFER_DETAILS })
        val own = code.indexOfFirst { it.referenceText() == OWN_POST }
        assertEquals("$label: ownPost() calls", 1, code.count { it.referenceText() == OWN_POST })
        assertEquals("$label: ownPost() takes the download check's answer", Opcode.MOVE_RESULT, code[own - 1].opcode)
        assertTrue("$label: right after the check", code[own - 2].referenceText()!!.contains(";->") &&
            classes.any { classDef -> classDef.methods.any { ELIGIBLE_MARKER in it.markers() && code[own - 2].referenceText() == "${classDef.type}->${it.name}(${it.parameterTypes.joinToString("")})Z" } })
        assertEquals("$label: its answer replaces the check's", (code[own - 1] as OneRegisterInstruction).registerA,
            (code[own + 1] as OneRegisterInstruction).registerA)
        val offer = code.indexOfFirst { it.referenceText() == OFFER_VIDEO }
        assertTrue("$label: offer() follows the Download row's jump", code[offer - 1].opcode.name.startsWith("goto"))
        assertTrue("$label: a jump reaches offer()", code.indices.any { it < offer && code[it].opcode == Opcode.IF_EQZ && code.target(it) == offer })
        val row = context.method(INSTAGRAM_MEDIA, "addDownloadRow").code()
        assertTrue("$label: the row bridge", row.any { it.referenceText() == DOWNLOAD } && row.any { it.opcode == Opcode.INVOKE_STATIC_RANGE })
        assertEquals("$label: the post bridge", Opcode.CHECK_CAST, context.method(INSTAGRAM_MEDIA, "feedMenuMedia").code().first().opcode)
        val lists = classes.flatMap { it.methods }.filter { it.isShortMenuList() }
        assertEquals("$label: the short menu's lists", 1, lists.size)
        val list = context.method(lists.single().definingClass, lists.single().name, listOf("Z")).code()
        val returns = list.indices.filter { list[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("$label: allow() calls", returns.size, list.count { it.referenceText() == ALLOW_VIDEO })
        returns.forEach { at ->
            assertEquals("$label: allow() before the return at $at", ALLOW_VIDEO, list[at - 2].referenceText())
            assertEquals("$label: Download handed to it", DOWNLOAD, list[at - 3].referenceText())
        }
        val bridges = context.classDefBy(INSTAGRAM_MEDIA).methods.filter { it.name in videoBridges }
        assertEquals("$label: the video bridges", videoBridges.size, bridges.size)
        bridges.forEach { assertEquals("$label: ${it.name}", Opcode.CHECK_CAST, it.code().first().opcode) }
    }

    /**
     * In each build the button hook finds the binder, the holder's Save button, the row state's post,
     * the feed state and the activity, lands first in the binder, and leaves Hide the Repost button's
     * hook of the same binder in place and found.
     */
    private fun assertFeedDownloadButton(context: BytecodePatchContext, page: PageIndex, label: String) {
        val site = context.findFeedButtonSite(page)
        context.addFeedDownloadButton(site)
        val code = context.method(site.type, site.name, site.parameters).code()
        assertEquals("$label: one button hook", 1, code.count { it.referenceText() == FEED_BUTTON })
        assertEquals("$label: the hook is first", 7, code.indexOfFirst { it.referenceText() == FEED_BUTTON })
        assertEquals("$label: the Save button", site.save.toString(), code[1].referenceText())
        assertTrue("$label: the Save button is a bouncy field of the holder", site.save.toString().startsWith(site.parameters[site.holder] + "->"))
        assertEquals("$label: the post", site.media.toString(), code[3].referenceText())
        assertEquals("$label: the activity", site.activity.toString(), code[6].referenceText())
        assertEquals("$label: the feed state is the carousel page's", page.index.definingClass, site.parameters[site.item])
        context.hideFeedUfi(context.findFeedUfiSite())
        val both = context.method(site.type, site.name, site.parameters).code()
        assertEquals("$label: Hide the Repost button still lands beside it", 1, both.count { it.referenceText() == REPOSTS_FEED_UFI })
        assertEquals("$label: the button hook stays once", 1, both.count { it.referenceText() == FEED_BUTTON })
    }

    private val videoBridges = setOf(
        "videoVersions", "dashManifest", "mediaId", "owner", "takenAt", "username", "versionUrl", "versionWidth", "versionHeight",
        "imageVersions", "imageCandidates", "candidateUrl", "candidateWidth", "candidateHeight", "caption", "captionText",
    )

    private fun assertUntouched(context: BytecodePatchContext) {
        assertTrue("the builder changed", context.method(lambda, "invoke").code().none { it.referenceText()?.startsWith("Lapp/hushgram/") == true })
        assertEquals("the handler changed", Opcode.CONST_STRING, context.method(helper, "A09").code().first().opcode)
        assertEquals("a bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "videoVersions").code().first().opcode)
        assertEquals("the row bridge was written", Opcode.RETURN_VOID, context.method(INSTAGRAM_MEDIA, "addDownloadRow").code().first().opcode)
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.single { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The index [branch] at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private fun classes(
        name: String = FEED_HELPER_NAME,
        lateJump: Boolean = false,
        handlerRegisters: Int = 42,
        shortLists: Int = 1,
        shortListRegisters: Int = 4,
        pageReads: Pair<Int, Int> = 3 to 1,
        lookupReadsPages: Boolean = true,
        menuHoldsItem: Boolean = true,
        stateCasts: Int = 1,
        branchBeforeStateCast: Boolean = false,
        ownPostFromState: Boolean = true,
        flagSkipsRow: Boolean = false,
        optionEnum: Boolean = true,
        optionInitializesIcon: Boolean = true,
        menuKeepsState: Boolean = true,
        builderUsesMenu: Boolean = true,
        outlinedList: Boolean = false,
        outlinedLabel: Boolean = false,
        outlinesOnlyDoThat: Boolean = true,
    ): List<ClassDef> {
        val menu = ImmutableClassDef(
            helper, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOfNotNull(
                ImmutableField(helper, "__redex_internal_original_name", "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, ImmutableStringEncodedValue(name), null, null),
                field(helper, "activity", activity),
                field(helper, "post", MEDIA),
                field(helper, "item", itemState).takeIf { menuHoldsItem },
                field(helper, "elsewhere", elsewhere),
                field(helper, "rows", state).takeIf { menuKeepsState },
            ),
            listOf(
                method(helper, "A09", listOf(OPTION), "V", handlerRegisters, static = false, body = """
                    const-string v0, "feed_action_sheet"
                    return-void
                """),
                // The getter that throws on null, by calling the one that reads the field.
                method(helper, "A00", listOf(helper), "$MEDIA", 2, static = true, body = """
                    invoke-static { p0 }, $helper->A01($helper)$MEDIA
                    move-result-object v0
                    return-object v0
                """),
                method(helper, "A01", listOf(helper), "$MEDIA", 2, static = true, body = """
                    iget-object v0, p0, $helper->post:$MEDIA
                    return-object v0
                """),
            ),
        )
        // The feed menu's builder, one case of a merged lambda: your own post goes past the download
        // check and two flags to the Download row, out of line; anyone else's jumps past it. Some
        // builds make the list and read the label through methods R8 outlined, and some never touch
        // the menu in the builder.
        val builder = classDef(lambda, listOf(method(lambda, "invoke", emptyList(), "Ljava/lang/Object;", 15, static = false, body = """
            ${if (outlinedList) "invoke-static {}, $outline->A0b()Ljava/util/ArrayList;\nmove-result-object v3"
                else "new-instance v3, Ljava/util/ArrayList;\ninvoke-direct { v3 }, Ljava/util/ArrayList;-><init>()V"}
            ${if (branchBeforeStateCast) "goto :captured\n:captured" else ""}
            iget-object v0, p0, $lambda->state:$state
            ${List(stateCasts) { "check-cast v0, $state" }.joinToString("\n")}
            iget-object v1, v0, $state->media:$MEDIA
            invoke-static { v1 }, $mine
            move-result v1
            if-eqz v1, :others
            ${if (ownPostFromState) "iget-object v12, v0, $state->media:$MEDIA" else "const/4 v12, 0x0"}
            const/4 v2, 0x0
            invoke-virtual { v2, v2, v12 }, $check
            move-result v1
            if-eqz v1, ${if (lateJump) ":others" else ":mine"}
            iget-boolean v1, v0, $state->flagged:Z
            const/4 v5, 0x0
            if-eqz v1, ${if (flagSkipsRow) ":mine" else ":row"}
            const-wide v6, 0x81034200060c62L
            invoke-static { v2, v6, v7 }, $flag
            move-result v1
            if-eqz v1, :row
            :mine
            ${if (builderUsesMenu) "invoke-static { v13 }, $helper->A01($helper)$MEDIA" else "nop"}
            return-object v3
            :row
            sget-object v7, $DOWNLOAD
            iget-object v1, v0, $state->context:$contextType
            invoke-virtual { v1 }, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
            move-result-object v2
            const v1, 0x7f131703
            ${if (outlinedLabel) "invoke-static { v2, v1 }, $outline->A0y(Landroid/content/res/Resources;I)Ljava/lang/String;"
                else "invoke-virtual { v2, v1 }, Landroid/content/res/Resources;->getString(I)Ljava/lang/String;"}
            move-result-object v9
            sget-object v6, $kind->A05:$kind
            move-object v8, v0
            move-object v10, v3
            move v11, v5
            invoke-static/range { v6 .. v11 }, $adder
            goto :mine
            :others
            iget-object v1, v0, $state->other:Ljava/lang/Object;
            iget-object v2, v0, $state->item:$itemState
            iget-object v4, v0, $state->media:$MEDIA
            sget-object v6, $OPTION->REPORT:$OPTION
            const v5, 0x7f000001
            invoke-static { v6, v0, v3, v5 }, $state->A01($OPTION${state}Ljava/util/ArrayList;I)V
            return-object v3
        """)))
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
            "A8k" to ("carousel_media" to "Ljava/util/List;"),
            "A3F" to ("image_versions2" to IMAGE_INFO),
        ).map { (name, field) -> getter(MEDIA, name, field.first, field.second) } +
            method(MEDIA, "getId", emptyList(), "Ljava/lang/String;", 1, static = false, body = """
                const/4 v0, 0x0
                return-object v0
            """)
        val versionGetters = listOf("getUrl" to ("url" to "Ljava/lang/String;"), "DvO" to ("width" to "Ljava/lang/Integer;"),
            "CK7" to ("height" to "Ljava/lang/Integer;"))
        val imageGetters = listOf("Bd1" to ("candidates" to "Ljava/util/List;"))
        // The short menu's list of the options it keeps: made once, answered as it is on one arm and
        // made read-only on the other, where a jump goes to the return.
        val shortList = { listName: String ->
            method(shortMenu, listName, listOf("Z"), "Ljava/util/List;", shortListRegisters, static = true, body = """
                sget-object v1, $WHY_OPTION
                sget-object v0, $REPORT_OPTION
                filled-new-array { v1, v0 }, [$OPTION
                move-result-object v1
                invoke-static { v1 }, Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;
                move-result-object v1
                if-nez p0, :short
                return-object v1
                :short
                invoke-static { v1 }, Ljava/util/Collections;->unmodifiableList(Ljava/util/List;)Ljava/util/List;
                move-result-object v0
                if-eqz v0, :done
                :done
                return-object v0
            """)
        }
        val shortMenus = classDef(shortMenu, listOf("A01", "A02").take(shortLists).map(shortList) +
            // A static list of the same shape reading Report alone, which isn't the menu's.
            method(shortMenu, "A03", listOf("Z"), "Ljava/util/List;", 2, static = true, body = """
                sget-object v0, $REPORT_OPTION
                const/4 v0, 0x0
                return-object v0
            """))
        // The page lookup, which reads a carousel's pages, and one of the same shape that doesn't.
        val mediaExt = classDef(MEDIA_EXT, listOf(
            method(MEDIA_EXT, "A0Q", listOf(MEDIA, "I"), MEDIA, 3, static = true, body = """
                ${if (lookupReadsPages) "invoke-virtual { p0 }, $MEDIA->A8k()Ljava/util/List;" else "nop"}
                const/4 v0, 0x0
                return-object v0
            """),
            method(MEDIA_EXT, "A0R", listOf(MEDIA, "I"), MEDIA, 3, static = true, body = """
                const/4 v0, 0x0
                return-object v0
            """),
        ))
        // Calls to the lookup: the feed state's page, another of its ints read less often, an int on a
        // class the builder's state doesn't hold read most often of all, and a constant.
        fun call(number: Int, holder: String, field: String) =
            method(pager, "p$number", listOf(MEDIA, holder), MEDIA, 4, static = true, body = """
                iget v0, p1, $holder->$field:I
                invoke-static { p0, v0 }, $pageLookup
                move-result-object v1
                return-object v1
            """)
        val calls = (1..pageReads.first).map { call(it, itemState, "page") } +
            (1..pageReads.second).map { call(10 + it, itemState, "scroll") } +
            (1..5).map { call(20 + it, elsewhere, "index") } +
            method(pager, "constant", listOf(MEDIA), MEDIA, 3, static = true, body = """
                const/4 v0, 0x0
                invoke-static { p0, v0 }, $pageLookup
                move-result-object v1
                return-object v1
            """)
        // What R8 outlines `new ArrayList()` and a label's getString into, or methods of the same
        // shape that do something else.
        val outlines = classDef(outline, listOf(
            method(outline, "A0b", emptyList(), "Ljava/util/ArrayList;", 1, static = true, body = if (outlinesOnlyDoThat) """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                return-object v0
            """ else """
                new-instance v0, Ljava/util/ArrayList;
                const/4 v0, 0x0
                return-object v0
            """),
            method(outline, "A0y", listOf("Landroid/content/res/Resources;", "I"), "Ljava/lang/String;", 3, static = true,
                body = if (outlinesOnlyDoThat) """
                    invoke-virtual { p0, p1 }, Landroid/content/res/Resources;->getString(I)Ljava/lang/String;
                    move-result-object v0
                    invoke-static { v0 }, Lfixture/Checks;->A0F(Ljava/lang/Object;)V
                    return-object v0
                """ else """
                    const-string v0, "Download"
                    return-object v0
                """),
        ))
        return listOf(
            menu, builder, eligible, shortMenus, mediaExt, classDef(pager, calls), outlines,
            ImmutableClassDef(OPTION, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or
                (if (optionEnum) AccessFlags.ENUM.value else 0),
                "Ljava/lang/Enum;", null, null, null, emptyList(), listOf(
                    method(OPTION, "<init>", listOf("Ljava/lang/String;", "I", "I"), "V", 4, static = false, body = """
                        invoke-direct { p0, p1, p2 }, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
                        iput ${if (optionInitializesIcon) "p3" else "p2"}, p0, $OPTION->iconDrawable:I
                        return-void
                    """),
                    method(OPTION, "getIconDrawable", emptyList(), "I", 2, static = false, body = """
                        iget v0, p0, $OPTION->iconDrawable:I
                        return v0
                    """),
                )),
            classDef(MEDIA, mediaGetters),
            classDef(USER, listOf(getter(USER, "A89", "username", "Ljava/lang/String;"))),
            anInterface(VIDEO_VERSION, versionGetters.map { it.first to it.second.second }),
            classDef(PANDO_VIDEO_VERSION, versionGetters.map { (name, field) -> getter(PANDO_VIDEO_VERSION, name, field.first, field.second) }),
            anInterface(IMAGE_INFO, imageGetters.map { it.first to it.second.second }),
            classDef(PANDO_IMAGE_INFO, imageGetters.map { (name, field) -> getter(PANDO_IMAGE_INFO, name, field.first, field.second) }),
            anInterface(IMAGE_URL, listOf("getUrl" to "Ljava/lang/String;", "getWidth" to "I", "getHeight" to "I")),
            ExtensionDex.classDef(INSTAGRAM_MEDIA),
        )
    }

    private fun anInterface(type: String, methods: List<Pair<String, String>>): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
        "Ljava/lang/Object;", null, null, null, null,
        methods.map { (name, returns) ->
            ImmutableMethod(type, name, emptyList(), returns, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
        },
    )

    private fun getter(owner: String, name: String, field: String, returns: String) =
        method(owner, name, emptyList(), returns, 2, static = false, body = """
            const v0, ${field.hashCode()}
            const/4 v0, 0x0
            return-object v0
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
