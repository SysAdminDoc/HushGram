/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.origin

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FindReelOriginHookTest {
    // Its switch starts off, so simple mode picks it (DefaultSelectionPolicyTest).
    @Test fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(findReelOriginPatch.default)
    }

    @Test fun bothHooksGoFirstInTheirMethodsAndNothingElseChanges() {
        val input = ReelOriginFixture.classes()
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val open = ReelOriginFixture.opener().methods.single { it.name == "A03" }.code()
        val resume = ReelOriginFixture.tab().methods.single { it.name == "onResume" }.code()
        context.hookReelOrigin()

        val opener = context.mutableClassDefBy(ReelOriginFixture.OPENER).methods
        assertOpenHook(opener.single { it.name == "A03" }, open, "${ReelOriginFixture.DESTINATION}->A03:$MEDIA")
        assertEquals("the opener's other methods stay as they were",
            before.getValue(ReelOriginFixture.OPENER).filter { !it.first.contains("->A03(") },
            snapshot(opener.filter { it.name != "A03" }))
        val tab = context.mutableClassDefBy(ReelOriginFixture.TAB).methods
        assertResumeHook(tab.single { it.name == "onResume" }, resume)
        assertEquals("the tab's other methods stay as they were",
            before.getValue(ReelOriginFixture.TAB).filter { !it.first.contains("->onResume(") },
            snapshot(tab.filter { it.name != "onResume" }))
        val stubs = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
        assertTrialBridge(stubs)
        assertEquals("the other bridges stay unwritten",
            before.getValue(INSTAGRAM_MEDIA).filter { !it.first.contains("->activeTrial(") },
            snapshot(stubs.filter { it.name != "activeTrial" }))
        val hooked = setOf(ReelOriginFixture.OPENER, ReelOriginFixture.TAB, INSTAGRAM_MEDIA)
        for (other in input.filter { it.type !in hooked }) {
            assertEquals("${other.type} changed", before[other.type], snapshot(context.mutableClassDefBy(other.type).methods))
        }
    }

    /**
     * A trial reel isn't on its poster's profile, so the extension skips it by Media's one getter of
     * `is_active_trial` (#225). Without one, or with two, every hook still goes in and the trial stub
     * stays as it was, after the patch log says why, once.
     */
    @Test fun aMediaWithoutATrialGetterLooksForTrialReelsToo() =
        skipsTrials(ReelOriginFixture.withPhotos(ReelOriginFixture.media(AccessFlags.PUBLIC.value, typeGetters = 1, trialGetters = 0)),
            "for is_active_trial, found none")
    @Test fun aMediaWithTwoTrialGettersLooksForTrialReelsToo() =
        skipsTrials(ReelOriginFixture.withPhotos(ReelOriginFixture.media(AccessFlags.PUBLIC.value, typeGetters = 1, trialGetters = 2)),
            "for is_active_trial, found A4p, A4q")

    private fun skipsTrials(input: List<ClassDef>, reason: String) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val gridResume = ReelOriginFixture.postsGrid().methods.single { it.name == "onResume" }.code()
        val tabResume = ReelOriginFixture.tab().methods.single { it.name == "onResume" }.code()
        val warnings = PatchLogCapture.warnings { context.hookReelOrigin() }

        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("$REEL_ORIGIN_PATCH: ") && reason in warnings.single() &&
            warnings.single().endsWith("$REEL_ORIGIN_PATCH goes in looking for trial reels too."))
        assertResumeHook(context.mutableClassDefBy(ReelOriginFixture.TAB).methods.single { it.name == "onResume" }, tabResume)
        assertGridHook(context.mutableClassDefBy(POSTS_GRID).methods.single { it.name == "onResume" }, gridResume)
        val stubs = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
        assertEquals("the trial stub stays as it was", before.getValue(INSTAGRAM_MEDIA).filter { it.first.contains("->activeTrial(") },
            snapshot(stubs.filter { it.name == "activeTrial" }))
        assertEquals("the media type bridge is still written", "$MEDIA->A6N()Ljava/lang/Integer;",
            stubs.single { it.name == "mediaType" }.code()[1].reference())
    }

    @Test fun aMissingOpenerIsRefused() = refuses("holding the $OPEN_PROFILE marker, found none",
        ReelOriginFixture.classes().filter { it.type != ReelOriginFixture.OPENER })
    @Test fun anOpenerMarkerInTwoMethodsIsRefused() = refuses("holding the $OPEN_PROFILE marker, found LX/07FC;->A0",
        ReelOriginFixture.with(ReelOriginFixture.opener(twice = true)))
    @Test fun aStaticOpenerIsRefused() = refuses("LX/07FC;->A03 is static",
        ReelOriginFixture.with(ReelOriginFixture.opener(flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))
    @Test fun anOpenerTakingTwoArgumentsIsRefused() = refuses("LX/07FC;->A03 doesn't take one object",
        ReelOriginFixture.with(ReelOriginFixture.opener(parameters = listOf(ReelOriginFixture.DESTINATION, ReelOriginFixture.DESTINATION))))
    @Test fun aJumpToTheOpenersFirstInstructionIsRefused() = refuses("enters LX/07FC;->A03 at its first instruction",
        ReelOriginFixture.with(ReelOriginFixture.opener(loop = true)))
    @Test fun anOpenerWithoutALocalIsRefused() = refuses("LX/07FC;->A03 has no local register",
        ReelOriginFixture.with(ReelOriginFixture.opener(registers = 2)))
    @Test fun anOpenerReadingV0FirstIsRefused() = refuses("LX/07FC;->A03 reads v0 before writing it",
        ReelOriginFixture.with(ReelOriginFixture.opener(readsV0 = true)))
    @Test fun aDestinationWithoutAMediaIsRefused() = refuses("${ReelOriginFixture.DESTINATION} doesn't hold exactly one $MEDIA",
        ReelOriginFixture.with(ReelOriginFixture.destination(media = 0)))
    @Test fun aDestinationWithTwoMediaIsRefused() = refuses("${ReelOriginFixture.DESTINATION} doesn't hold exactly one $MEDIA",
        ReelOriginFixture.with(ReelOriginFixture.destination(media = 2)))
    @Test fun anOpenerNotReadingTheMediaIsRefused() = refuses("LX/07FC;->A03 doesn't read ${ReelOriginFixture.DESTINATION}->A03",
        ReelOriginFixture.with(ReelOriginFixture.opener(reads = "A01")))
    @Test fun aMediaWithoutPublicGetIdIsRefused() = refuses("$MEDIA has no public getId()",
        ReelOriginFixture.with(ReelOriginFixture.media(AccessFlags.PRIVATE.value)))
    @Test fun aMissingTabIsRefused() = refuses("holding the $TAB_RESUME marker, found none",
        ReelOriginFixture.classes().filter { it.type != ReelOriginFixture.TAB })
    @Test fun aTabMarkerOutsideOnResumeIsRefused() = refuses("the Reels tab's marker isn't in an instance onResume()V",
        ReelOriginFixture.with(ReelOriginFixture.tab(resumeName = "onStart")))
    @Test fun aJumpToTheTabsFirstInstructionIsRefused() = refuses("enters the Reels tab's onResume at its first instruction",
        ReelOriginFixture.with(ReelOriginFixture.tab(loop = true)))
    @Test fun aTabThatNoLongerReadsWhoseProfileItIsIsRefused() = refuses("the Reels tab's onCreate doesn't read $USER_KEY",
        ReelOriginFixture.with(ReelOriginFixture.tab(userKey = false)))
    @Test fun aTabThatNoLongerFindsTilesByTransitionNameIsRefused() = refuses("doesn't look its tiles up by transition name",
        ReelOriginFixture.with(ReelOriginFixture.tab(transitionNames = false)))
    @Test fun aTileLookupInAnotherClassIsRefused() = refuses("the $TILE_LOOKUP marker is in LX/07FC;, not the Reels tab",
        ReelOriginFixture.with(ReelOriginFixture.opener(lookup = true)).map {
            if (it.type == ReelOriginFixture.TAB) ReelOriginFixture.tab(lookup = false) else it
        })
    @Test fun aTabWithoutAGridIsRefused() = refuses("the Reels tab has 0 RecyclerView fields, not one",
        ReelOriginFixture.with(ReelOriginFixture.tab(grids = emptyList())))
    @Test fun aTabWithTwoGridsIsRefused() = refuses("the Reels tab has 2 RecyclerView fields, not one",
        ReelOriginFixture.with(ReelOriginFixture.tab(grids = listOf(AccessFlags.PUBLIC.value, AccessFlags.PUBLIC.value))))
    @Test fun aPrivateGridIsRefused() = refuses("the Reels tab's grid A01 isn't public",
        ReelOriginFixture.with(ReelOriginFixture.tab(grids = listOf(AccessFlags.PRIVATE.value))))
    @Test fun aTabThatIsNoFragmentIsRefused() = refuses("the Reels tab isn't a $FRAGMENT",
        ReelOriginFixture.classes().filter { it.type != ReelOriginFixture.BASE })
    @Test fun aFragmentWithoutGetActivityIsRefused() = refuses("$FRAGMENT has no public getActivity()$FRAGMENT_ACTIVITY",
        ReelOriginFixture.with(ReelOriginFixture.fragment(leaveOut = "getActivity")))
    @Test fun aFragmentWithoutGetArgumentsIsRefused() = refuses("$FRAGMENT has no public getArguments()$BUNDLE",
        ReelOriginFixture.with(ReelOriginFixture.fragment(leaveOut = "getArguments")))
    @Test fun aFragmentWithoutIsResumedIsRefused() = refuses("$FRAGMENT has no public isResumed()Z",
        ReelOriginFixture.with(ReelOriginFixture.fragment(leaveOut = "isResumed")))
    @Test fun anExtensionWithoutOpeningIsRefused() = refuses("extension has no public static opening(Ljava/lang/Object;)V",
        ReelOriginFixture.with(ReelOriginFixture.extension(leaveOut = "opening")))
    @Test fun anExtensionWithoutTheTabHookIsRefused() = refuses("extension has no public static reelsTabResumed(Ljava/lang/Object;)V",
        ReelOriginFixture.with(ReelOriginFixture.extension(leaveOut = "reelsTabResumed")))

    /**
     * A photo reel's search: the posts grid goes to the extension right after its super.onResume(),
     * and the media type bridge reads Media's media_type getter (#225).
     */
    @Test fun thePostsGridHookFollowsItsSuperCallAndTheMediaTypeBridgeIsWritten() {
        val input = ReelOriginFixture.withPhotos()
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val gridResume = ReelOriginFixture.postsGrid().methods.single { it.name == "onResume" }.code()
        val tabResume = ReelOriginFixture.tab().methods.single { it.name == "onResume" }.code()
        context.hookReelOrigin()

        val grid = context.mutableClassDefBy(POSTS_GRID).methods
        assertGridHook(grid.single { it.name == "onResume" }, gridResume)
        assertEquals("the posts grid's other methods stay as they were",
            before.getValue(POSTS_GRID).filter { !it.first.contains("->onResume(") },
            snapshot(grid.filter { it.name != "onResume" }))
        assertResumeHook(context.mutableClassDefBy(ReelOriginFixture.TAB).methods.single { it.name == "onResume" }, tabResume)

        val stubs = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
        val bridge = stubs.single { it.name == "mediaType" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            bridge.take(4).map { it.opcode })
        assertEquals(MEDIA, bridge[0].reference())
        assertEquals("the getter loading media_type, not its neighbor", "$MEDIA->A6N()Ljava/lang/Integer;", bridge[1].reference())
        assertTrialBridge(stubs)
        val written = setOf("mediaType", "activeTrial")
        assertEquals("the other bridges stay unwritten",
            before.getValue(INSTAGRAM_MEDIA).filter { snapshot -> written.none { snapshot.first.contains("->$it(") } },
            snapshot(stubs.filter { it.name !in written }))
        val hooked = setOf(ReelOriginFixture.OPENER, ReelOriginFixture.TAB, POSTS_GRID, INSTAGRAM_MEDIA)
        for (other in input.filter { it.type !in hooked }) {
            assertEquals("${other.type} changed", before[other.type], snapshot(context.mutableClassDefBy(other.type).methods))
        }
    }

    /**
     * Profile posts as a list puts its own call first in the same onResume, with a start-call
     * contract. Whichever patch goes in first, it stays first and this hook still follows the super call.
     */
    @Test fun profilePostsAsAListKeepsTheFirstSpot() {
        val input = ReelOriginFixture.withPhotos()
        val context = PatchContexts.of(input)
        val gridResume = ReelOriginFixture.postsGrid().methods.single { it.name == "onResume" }.code()
        val postsList = "Lapp/hushgram/extension/instagram/profile/PostsList;->resumed(Ljava/lang/Object;)V"
        val grid = context.mutableClassDefBy(POSTS_GRID).methods.single { it.name == "onResume" }
        grid.addInstructions(0, "invoke-static/range { p0 .. p0 }, $postsList")
        context.hookReelOrigin()

        val code = grid.code()
        assertEquals("Profile posts as a list stays first", postsList, code[0].reference())
        assertGridHook(grid, listOf(code[0]) + gridResume)
    }

    @Test fun withoutThePostsGridOnlyTheReelsTabIsHooked() = skipsPhotos(ReelOriginFixture.withPhotos().filter { it.type != POSTS_GRID },
        "no $POSTS_GRID")
    @Test fun aPostsGridWithoutItsKeyIsSkipped() = skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.postsGrid(key = false)),
        "the posts grid's onCreate doesn't read $GRID_KEY")
    @Test fun aJumpToJustPastThePostsGridsSuperCallIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.postsGrid(jumpPastSuper = true)),
            "something in the posts grid's onResume jumps to just past its super call")
    @Test fun aPostsGridNotCallingSuperOnResumeIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.postsGrid(supers = 0)),
            "the posts grid's onResume doesn't call super.onResume once, found 0")
    @Test fun aPostsGridCallingSuperOnResumeTwiceIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.postsGrid(supers = 2)),
            "the posts grid's onResume doesn't call super.onResume once, found 2")
    @Test fun aPostsGridWithTwoGridsIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.postsGrid(grids = listOf(AccessFlags.PUBLIC.value, AccessFlags.PUBLIC.value))),
            "the posts grid has 2 RecyclerView fields, not one")
    @Test fun aPrivatePostsGridIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.postsGrid(grids = listOf(AccessFlags.PRIVATE.value))),
            "the posts grid's recyclerView isn't public")
    @Test fun aPostsGridThatIsNoFragmentIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.postsGrid(superclass = "Ljava/lang/Object;")),
            "the posts grid isn't a $FRAGMENT")
    @Test fun anExtensionWithoutThePostsGridHookIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.extension(leaveOut = "postsGridResumed")),
            "extension has no public static postsGridResumed(Ljava/lang/Object;)V")
    @Test fun aMediaWithoutAMediaTypeGetterIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.media(AccessFlags.PUBLIC.value, typeGetters = 0)),
            "for media_type, found none")
    @Test fun aMediaWithTwoMediaTypeGettersIsSkipped() =
        skipsPhotos(ReelOriginFixture.withPhotos(ReelOriginFixture.media(AccessFlags.PUBLIC.value, typeGetters = 2)),
            "for media_type, found A6N, A6O")

    /**
     * The Reels tab is hooked as ever, while the posts grid and the media type stub are left as they
     * were, and the patch log says why, once. The trial stub, which video reels need too, is written.
     */
    private fun skipsPhotos(input: List<ClassDef>, reason: String) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val tabResume = ReelOriginFixture.tab().methods.single { it.name == "onResume" }.code()
        val warnings = PatchLogCapture.warnings { context.hookReelOrigin() }

        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("$REEL_ORIGIN_PATCH: ") && reason in warnings.single() &&
            warnings.single().endsWith("$REEL_ORIGIN_PATCH goes in finding video reels alone."))
        assertResumeHook(context.mutableClassDefBy(ReelOriginFixture.TAB).methods.single { it.name == "onResume" }, tabResume)
        if (input.any { it.type == POSTS_GRID }) {
            assertEquals("$POSTS_GRID changed", before[POSTS_GRID], snapshot(context.mutableClassDefBy(POSTS_GRID).methods))
        }
        val stubs = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
        assertTrialBridge(stubs)
        assertEquals("$INSTAGRAM_MEDIA changed", before.getValue(INSTAGRAM_MEDIA).filter { !it.first.contains("->activeTrial(") },
            snapshot(stubs.filter { it.name != "activeTrial" }))
    }

    /** Refused for [reason], with every class as it was. */
    private fun refuses(reason: String, input: List<ClassDef>) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.hookReelOrigin() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$REEL_ORIGIN_PATCH: ") && reason in refusal.message!!)
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    companion object {
        /**
         * The opener reads its argument's media into v0 and hands it to the extension, three plain
         * instructions in front, and then Instagram's [original] code runs as it was.
         */
        internal fun assertOpenHook(method: Method, original: List<Instruction>, media: String) {
            val code = method.code()
            val argument = method.implementation!!.registerCount - 1
            assertEquals(1, code.count { it.reference() == OPENING })
            assertEquals(Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
            assertEquals("the argument goes into v0", listOf(0, argument), code[0].namedRegisters())
            assertEquals(Opcode.IGET_OBJECT, code[1].opcode)
            assertEquals(media, code[1].reference())
            assertEquals("v0's media is read into v0", listOf(0, 0), code[1].namedRegisters())
            assertEquals(Opcode.INVOKE_STATIC_RANGE, code[2].opcode)
            assertEquals(OPENING, code[2].reference())
            assertEquals("the hook reads only v0", listOf(0), code[2].namedRegisters())
            assertEquals("three instructions come in front, nothing else", original.size + 3, code.size)
            assertEquals(original.map { Triple(it.opcode, it.reference(), it.namedRegisters()) },
                code.drop(3).map { Triple(it.opcode, it.reference(), it.namedRegisters()) })
        }

        /** The tab goes to the extension's [hook] first, in its own register, and then Instagram's [original] onResume runs as it was. */
        internal fun assertResumeHook(method: Method, original: List<Instruction>, hook: String = TAB_RESUMED) {
            val code = method.code()
            val self = method.implementation!!.registerCount - 1
            assertEquals(1, code.count { it.reference() == hook })
            assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
            assertEquals(hook, code[0].reference())
            assertEquals("the hook reads only the tab, p0", listOf(self), code[0].namedRegisters())
            assertEquals("one instruction comes in front, nothing else", original.size + 1, code.size)
            assertEquals(original.map { Triple(it.opcode, it.reference(), it.namedRegisters()) },
                code.drop(1).map { Triple(it.opcode, it.reference(), it.namedRegisters()) })
        }

        /**
         * The posts grid goes to [GRID_RESUMED] right after its super.onResume(), in its own register,
         * and the rest of its [original] onResume runs as it was, whatever sits in front.
         */
        internal fun assertGridHook(method: Method, original: List<Instruction>) {
            val code = method.code()
            val self = method.implementation!!.registerCount - 1
            assertEquals(1, code.count { it.reference() == GRID_RESUMED })
            val at = code.indexOfFirst { it.reference() == GRID_RESUMED }
            assertEquals(Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            assertEquals("the hook reads only the grid, p0", listOf(self), code[at].namedRegisters())
            assertEquals(Opcode.INVOKE_SUPER, code[at - 1].opcode)
            assertTrue("right after super.onResume(), not ${code[at - 1].reference()}", code[at - 1].reference()!!.endsWith("->onResume()V"))
            assertEquals("one instruction goes in, nothing else", original.size + 1, code.size)
            assertEquals(original.map { Triple(it.opcode, it.reference(), it.namedRegisters()) },
                code.filterIndexed { index, _ -> index != at }.map { Triple(it.opcode, it.reference(), it.namedRegisters()) })
        }

        /** The trial bridge casts to Media and calls the getter loading `is_active_trial`'s key, 452's A4p in the fixture. */
        internal fun assertTrialBridge(stubs: Iterable<Method>) {
            val bridge = stubs.single { it.name == "activeTrial" }.code()
            assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                bridge.take(4).map { it.opcode })
            assertEquals(MEDIA, bridge[0].reference())
            assertEquals("the getter loading is_active_trial, not its neighbor", "$MEDIA->A4p()Ljava/lang/Boolean;", bridge[1].reference())
        }

        internal fun snapshot(methods: Iterable<Method>) = methods.map { method ->
            method.toString() to method.code().map { Triple(it.opcode, it.reference(), it.namedRegisters()) }
        }

        internal fun Method.code() = implementation?.instructions?.toList().orEmpty()
        internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    }
}

/**
 * Stand-ins for 450's profile opener (AuthorInfoNavigator.navigateToNormalProfile) and what it's
 * handed, Media, the Reels tab of a profile with the Fragment it extends, and the extension.
 */
internal object ReelOriginFixture {
    private const val OBJECT = "Ljava/lang/Object;"
    const val OPENER = "LX/07FC;"
    const val DESTINATION = "LX/0Kxa;"
    const val TAB = "LX/052v;"
    /** The tab's own superclass, between it and Fragment, as in 450. */
    const val BASE = "LX/0ASy;"

    private fun marker(name: String) = "android_purge_26_q3_$name"

    fun classes(): List<ClassDef> = listOf(opener(), destination(), media(AccessFlags.PUBLIC.value or AccessFlags.FINAL.value),
        tab(), clazz(BASE, FRAGMENT, emptyList()), fragment(), extension(), ExtensionDex.classDef(INSTAGRAM_MEDIA))

    /** [classes] with [replacement] in place of the class of its type. */
    fun with(replacement: ClassDef): List<ClassDef> = classes().map { if (it.type == replacement.type) replacement else it }

    /**
     * 450's opener: a trace marker, then the destination's media read and the rest. v0 and v1 are
     * its locals, p1 the destination.
     */
    fun opener(
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        parameters: List<String> = listOf(DESTINATION),
        registers: Int = 4,
        loop: Boolean = false,
        readsV0: Boolean = false,
        reads: String = "A03",
        twice: Boolean = false,
        lookup: Boolean = false,
    ): ClassDef {
        val argument = registers - 1
        val code = mutableListOf<Instruction>()
        if (readsV0) code += ImmutableInstruction12x(Opcode.MOVE_OBJECT, 1, 0)
        code += ImmutableInstruction11n(Opcode.CONST_4, 1, 0)
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(marker(OPEN_PROFILE)))
        val type = if (reads == "A03") MEDIA else "LX/011g;"
        code += ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, argument, ImmutableFieldReference(DESTINATION, reads, type))
        // A goto back to the start, five code units back.
        code += if (loop) ImmutableInstruction10t(Opcode.GOTO, -5) else ImmutableInstruction11x(Opcode.RETURN, 1)
        val methods = mutableListOf(method(OPENER, "A03", parameters, "Z", registers, code, flags))
        if (twice) methods += method(OPENER, "A04", listOf(DESTINATION), "Z", 4, listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(marker(OPEN_PROFILE))),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)), flags)
        if (lookup) methods += method(OPENER, "A05", emptyList(), "V", 1, listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(marker(TILE_LOOKUP))),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference("Landroid/view/View;", "getTransitionName", emptyList(), "Ljava/lang/String;")),
            ImmutableInstruction10x(Opcode.RETURN_VOID)), AccessFlags.PUBLIC.value)
        // Another method of the class, which the hook leaves alone.
        methods += method(OPENER, "A02", emptyList(), "V", 1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), AccessFlags.PUBLIC.value)
        return clazz(OPENER, OBJECT, methods)
    }

    /** What the opener is handed: the reel's media and, like 450's, another field beside it. */
    fun destination(media: Int = 1): ClassDef = clazz(DESTINATION, OBJECT, emptyList(),
        listOf(field(DESTINATION, "A01", "LX/011g;", AccessFlags.PUBLIC.value)) +
            (0 until media).map { field(DESTINATION, "A0${3 + it}", MEDIA, AccessFlags.PUBLIC.value) })

    /**
     * Media with its getId and [typeGetters] getters loading `media_type`'s key, as 451's A6N does,
     * beside a getter of another Integer field, as its A6P of `original_media_type`, and
     * [trialGetters] getters loading `is_active_trial`'s, as 452's A4p does, beside another Boolean's.
     */
    fun media(getIdFlags: Int, typeGetters: Int = 0, trialGetters: Int = 1): ClassDef = clazz(MEDIA, OBJECT, listOf(method(MEDIA, "getId", emptyList(), "Ljava/lang/String;", 1,
        listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("1_2")), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
        getIdFlags)) + (0 until typeGetters).map { getter("A6${"NO"[it]}", "media_type") } + getter("A6P", "original_media_type") +
        (0 until trialGetters).map { getter("A4${"pq"[it]}", "is_active_trial", BOOLEAN) } + getter("A4o", "is_paid_partnership", BOOLEAN))

    private const val BOOLEAN = "Ljava/lang/Boolean;"

    private fun getter(name: String, field: String, returns: String = "Ljava/lang/Integer;") = method(MEDIA, name, emptyList(), returns, 2, listOf(
        ImmutableInstruction31i(Opcode.CONST, 0, field.hashCode()), ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)

    /**
     * [classes] with what a photo reel's search needs too: 451's posts grid and Media's media_type
     * getter, then [replacements] in place of the classes of their types.
     */
    fun withPhotos(vararg replacements: ClassDef): List<ClassDef> =
        (classes().map { if (it.type == MEDIA) media(AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, typeGetters = 1) else it } +
            postsGrid())
            .map { classDef -> replacements.firstOrNull { it.type == classDef.type } ?: classDef }

    /**
     * 451's posts grid, ProfileMediaTabFragment: an onResume holding no string, only its trace
     * number, an onCreate reading which of the profile's grids it is, and its grid.
     */
    fun postsGrid(
        jumpPastSuper: Boolean = false,
        supers: Int = 1,
        key: Boolean = true,
        grids: List<Int> = listOf(AccessFlags.PUBLIC.value),
        superclass: String = BASE,
    ): ClassDef {
        val resume = mutableListOf<Instruction>(ImmutableInstruction31i(Opcode.CONST, 0, 0x421bb1cc))
        // A branch over the super calls to what follows them: two code units of its own, three each call.
        if (jumpPastSuper) resume += ImmutableInstruction21t(Opcode.IF_EQZ, 0, 2 + 3 * supers)
        repeat(supers) {
            resume += ImmutableInstruction35c(Opcode.INVOKE_SUPER, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(BASE, "onResume", emptyList(), "V"))
        }
        resume += ImmutableInstruction10x(Opcode.RETURN_VOID)
        val create = listOfNotNull<Instruction>(
            if (key) ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(GRID_KEY)) else null,
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val methods = listOf(
            method(POSTS_GRID, "onResume", emptyList(), "V", 2, resume, final),
            method(POSTS_GRID, "onCreate", listOf(BUNDLE), "V", 3, create, final),
            // Another method of the same shape, which the hook leaves alone.
            method(POSTS_GRID, "onPause", emptyList(), "V", 1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), final),
        )
        val fields = grids.mapIndexed { at, flags -> field(POSTS_GRID, if (at == 0) "recyclerView" else "A0$at", RECYCLER_VIEW, flags) } +
            field(POSTS_GRID, "A0G", "Ljava/lang/String;", AccessFlags.PUBLIC.value)
        return clazz(POSTS_GRID, superclass, methods, fields)
    }

    /** 450's Reels tab: its onResume with the trace marker, its onCreate, its tile lookup and its grid. */
    fun tab(
        resumeName: String = "onResume",
        loop: Boolean = false,
        userKey: Boolean = true,
        transitionNames: Boolean = true,
        lookup: Boolean = true,
        grids: List<Int> = listOf(AccessFlags.PUBLIC.value),
    ): ClassDef {
        val resume = mutableListOf<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(marker(TAB_RESUME))),
            ImmutableInstruction35c(Opcode.INVOKE_SUPER, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(BASE, "onResume", emptyList(), "V")),
        )
        // A goto back to the start, five code units back.
        resume += if (loop) ImmutableInstruction10t(Opcode.GOTO, -5) else ImmutableInstruction10x(Opcode.RETURN_VOID)
        val create = listOfNotNull<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(marker(TAB_CREATE))),
            if (userKey) ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(USER_KEY)) else null,
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val find = listOfNotNull<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(marker(TILE_LOOKUP))),
            if (transitionNames) ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference("Landroid/view/View;", "getTransitionName", emptyList(), "Ljava/lang/String;")) else null,
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val methods = listOfNotNull(
            method(TAB, resumeName, emptyList(), "V", 2, resume, final),
            method(TAB, "onCreate", listOf(BUNDLE), "V", 3, create, final),
            if (lookup) method(TAB, "FgZ", listOf("Ljava/util/List;", "Ljava/util/Map;"), "V", 3, find, final) else null,
            // Another method of the same shape, which the hook leaves alone.
            method(TAB, "onPause", emptyList(), "V", 1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), final),
        )
        val fields = grids.mapIndexed { at, flags -> field(TAB, "A0${1 + at}", RECYCLER_VIEW, flags) } +
            field(TAB, "A0B", "Ljava/lang/String;", AccessFlags.PUBLIC.value)
        return clazz(TAB, BASE, methods, fields)
    }

    fun fragment(leaveOut: String? = null): ClassDef = clazz(FRAGMENT, OBJECT,
        listOf("getActivity" to FRAGMENT_ACTIVITY, "getArguments" to BUNDLE, "isResumed" to "Z").filter { it.first != leaveOut }
            .map { (name, returns) ->
                method(FRAGMENT, name, emptyList(), returns, 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    if (returns == "Z") ImmutableInstruction11x(Opcode.RETURN, 0) else ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
            })

    fun extension(leaveOut: String? = null): ClassDef = clazz(REEL_ORIGIN, OBJECT, listOf("opening", "reelsTabResumed", "postsGridResumed")
        .filter { it != leaveOut }
        .map { method(REEL_ORIGIN, it, listOf(OBJECT), "V", 1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value) })

    fun clazz(type: String, superclass: String, methods: List<Method>, fields: List<Field> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, fields, methods)

    fun field(type: String, name: String, fieldType: String, flags: Int): Field =
        ImmutableField(type, name, fieldType, flags, null, null, null)

    fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, flags: Int): Method =
        ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, code, null, null))
}
