/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.seekbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReelSeekBarHookTest {
    /** The hooks the patch writes are in the ReelSeekBar and ReelSeekTouch the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(MIN_SECONDS, LAZY, PASS_THROUGH, PROGRESS, BIND, BIND_REEL, LISTEN, PHOTO_BAR_ON, PHOTO_REEL, PHOTO_ROW, PHOTO_PROGRESS, PHOTO_TOUCH_ONLY, PHOTO_STRIP, SEEK_PREVIEW_ON, REMEMBER_REEL, BARS_HIDDEN, HIDDEN_TOUCH_ONLY)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Three reads of the minimum, two shared with the ads' minimum, one of the hidden kind, shared too, and the bar's listener. */
    @Test
    fun everyReadIsFoundAndCheckedAgainstTheSeekBar() {
        val sites = context().findReelSeekBarSites()

        assertEquals(ORGANIC_MIN_SECONDS_READS, sites.lengths.size)
        assertEquals(setOf(UTIL, PROGRESS_STATE), sites.lengths.map { it.type }.toSet())
        assertEquals(listOf(false, true, true), sites.lengths.map { it.shared }.sorted())
        assertEquals(ROW, sites.lazy.type)
        assertTrue("the hidden kind's read is the ads' too", sites.lazy.shared)
        assertEquals(listOf(PROGRESS_STATE, PROGRESS_STATE), sites.passThroughs.map { it.type })
        assertEquals(SEEK_BAR, sites.progress.definingClass)
        assertEquals(BINDER, sites.bind.method.definingClass)
        assertEquals("the container", sites.bind.method.parameterRegisterNumber(1), sites.bind.view)
        assertEquals(STRIP, sites.touch.method.definingClass)
        assertEquals("the strip and its listener", listOf(2, 0), listOf(sites.touch.view, sites.touch.listener))
    }

    /**
     * In both of the util's checks the ordinary reel's branch reads the minimum on its own and
     * answers it through the extension, then goes on after the shared read; the ad's branch still
     * lands on the shared read, which has no hook. The progress controller's own read is answered
     * in place, and the bar's listener hands the extension the bar and its position first.
     */
    @Test
    fun onlyTheOrdinaryReelsBranchIsAnswered() {
        val context = context()

        context.applyReelSeekBar(context.findReelSeekBarSites())

        assertAnsweredOnItsOwn("A0L", context.method(UTIL, "A0L"), ORGANIC_MIN_SECONDS, AD_MIN_SECONDS, MIN_SECONDS)
        assertAnsweredOnItsOwn("A0M", context.method(UTIL, "A0M"), ORGANIC_MIN_SECONDS, AD_MIN_SECONDS, MIN_SECONDS)
        assertAnsweredOnItsOwn("progress state", context.method(PROGRESS_STATE, "A00"), ORGANIC_MIN_SECONDS, null, MIN_SECONDS)
        assertAnsweredOnItsOwn("scrubber row", context.method(ROW, "A00"), ORGANIC_LAZY, AD_LAZY, LAZY)
        assertDragAnswered("stand-in", context.method(PROGRESS_STATE, "A00"))
        assertProgressHookedFirst("stand-in", context.method(SEEK_BAR, PROGRESS_CHANGED))
        assertBindHookedAfterTheAdRead("stand-in", context.method(BINDER, "invoke"), context.method(ROW, "A00"))
        assertReelIdAsked("stand-in", context, ITEM_TYPE)
        assertTouchHandedOver("stand-in", context.method(STRIP, "A0L"), context().findReelSeekBarSites().touch)
    }

    /**
     * A photo with a song (#10): the check of whether a reel gets the bar, the row's state and the
     * player's callback each ask the extension's switch first, and with it off go on as Instagram's own.
     */
    @Test
    fun theSwitchIsAskedBeforeAPhotosHooks() {
        val context = context()
        val sites = context.findReelSeekBarSites()
        assertEquals(ITEM_TYPE, sites.photo.row.parameterTypes[1].toString())
        assertEquals("$ITEM_TYPE->A0g:$MEDIA_TYPE", sites.photo.media)
        assertEquals("$KIND_EXT->A00($MEDIA_TYPE)$KIND_TYPE", sites.photo.kindOf)

        context.applyReelSeekBar(sites)

        assertPhotoHooked("stand-in", context, sites)
        assertHiddenBarsHooked("stand-in", context, sites)
    }

    /** A build without what a photo's hooks need stops the patch before anything changes, naming what it couldn't find. */
    @Test
    fun aPhotosMissingPieceFailsBeforeAnythingChanges() {
        for ((classes, message) in listOf(
            standIns().filter { it.type != KIND_EXT } to "\"$KIND_OF_REPORT\"",
            standIns().filter { it.type != PLAYER } to
                "expected one instance method taking a $ITEM_TYPE, the position and the length to load \"$PREVIEW_END\", found 0",
            standIns().filter { it.type != ITEM_TYPE } to "this Instagram build has no $ITEM_TYPE",
            standIns().map { if (it.type == ITEM_TYPE) itemType(medias = 2) else it } to "to hold one $MEDIA_TYPE, found 2",
            standIns().map { if (it.type == ITEM_TYPE) itemType(access = AccessFlags.FINAL.value) else it } to "or its getId isn't public",
            standIns().filter { it.type != REEL_ITEM_READER } to "the extension has no static $REEL_ITEM_ID",
            standIns().map { if (it.type == ITEM) item(tail = "const/4 v0, 0x1\nreturn v0") else it } to "doesn't return what",
            standIns().map { if (it.type == STRIP) strip(touchTail = "const/4 v0, 0x1\nreturn v0") else it } to "to return one call's answer, found 0",
            standIns().map { if (it.type == STRIP) strip(current = "") else it } to "to ask its controller for one $ITEM_TYPE, found 0",
            standIns().map { if (it.type == STRIP) strip(touchHead = "if-eqz p0, :answer", touchTail = "move-result v0\n:answer\nreturn v0") else it } to
                "jumps to its return of the answer",
            standIns().map { if (it.type == STRIP) strip(touchHead = "move-object p0, v1") else it } to "writes over its controller",
            standIns().map { if (it.type == STRIP) strip(adRead = "const/4 v0, 0x0") else it } to "whether it's an ad, found 0",
            standIns().map { if (it.type == STRIP) strip(adRead = "iget-boolean v1, v0, $ITEM_TYPE->A18:Z\niget-boolean v0, v0, $AD_FIELD") else it } to
                "whether it's an ad, found 2",
            standIns().map { if (it.type == STRIP) strip(lengthRead = "const-wide/16 v4, 0x5") else it } to
                "to ask its controller for the reel's length once, found 0",
            standIns().map { if (it.type == STRIP) strip(stripHead = "invoke-static {p0}, $STRIP->A03($STRIP)J\nmove-result-wide v4") else it } to
                "to ask its controller for the reel's length once, found 2",
            standIns().map { if (it.type == STRIP) strip(lengthRead = "invoke-static {p0}, $STRIP->A03($STRIP)J\nmove-result v4") else it } to
                "doesn't keep the reel's length",
            standIns().map { if (it.type == STRIP) strip(stripHead = "if-nez v3, :length\nnop\n:length") else it } to
                "branches before it compares the reel's length",
            standIns().map { if (it.type == STRIP) strip(stripTop = "if-nez p1, :length", stripHead = ":length\nnop") else it } to
                "jumps in between asking for the reel's length and comparing it",
            standIns().map { if (it.type == STRIP) strip(stripHead = "const-wide/16 v4, 0x0") else it } to
                "writes over the reel's length before comparing it",
            standIns().map { if (it.type == STRIP) strip(stripHead = "move-object p1, p0") else it } to "writes over parameter 0",
        )) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.findReelSeekBarSites() }
            assertTrue("$message in: ${failure.message}", failure.message!!.contains(message))
            assertNothingWritten(message, context)
        }
    }

    /**
     * The strip's listener call is swapped in place only when the progress controller, the one class
     * reporting its strip's error, has one method setting a touch listener, which makes exactly one such
     * call, in the plain two-register form. Anything else stops the patch before anything changes, since
     * the bar's switch would otherwise come with taps still lost to the strip.
     */
    @Test
    fun aStripTheGateCantGoOnFailsBeforeAnythingChanges() {
        fun stripWith(strip: ClassDef) = standIns().map { if (it.type == STRIP) strip else it }
        val secondSetter = method(
            STRIP, "A0M", emptyList(), "V", 4,
            """
                iget-object v2, p0, $STRIP->A0A:Landroid/view/View;
                const/4 v0, 0x0
                $SET_ON_TOUCH_CALL
                return-void
            """,
            static = false,
        )
        val controller = "expected one class reporting \"$PROGRESS_CONTROLLER_ERROR\", the Reels progress controller"
        val cases = listOf(
            Triple("no controller", standIns().filter { it.type != STRIP }, "$controller, found 0"),
            Triple("a controller reporting something else", stripWith(strip(error = "ClipsScrubberError")), "$controller, found 0"),
            Triple("two controllers", standIns() + strip(type = "Lfixture/OtherController;"), "$controller, found 2"),
            Triple("no listener set", stripWith(strip(set = "nop")), "to have one method setting a touch listener, found 0"),
            Triple("two methods set listeners", stripWith(strip(more = listOf(secondSetter))), "to have one method setting a touch listener, found 2"),
            Triple("two listeners set", stripWith(strip(set = "$SET_ON_TOUCH_CALL\n$SET_ON_TOUCH_CALL")), "to set one touch listener, found 2"),
            Triple(
                "the call is a range",
                stripWith(strip(set = "invoke-virtual/range {v2 .. v3}, Landroid/view/View;->setOnTouchListener(Landroid/view/View\$OnTouchListener;)V")),
                "in a form the patch hasn't seen",
            ),
        )
        for ((what, classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains(expected))
            assertNothingWritten(what, context)
        }
    }

    /**
     * Without a binder that can be tied to the seek bar and to the row's own ad answer, the label
     * couldn't tell an ordinary reel's bar from an ad's or another screen's, so the patch stops
     * before anything changes.
     */
    @Test
    fun aBinderThatCantBeTiedToAnOrdinaryReelFailsBeforeAnythingChanges() {
        fun binderWith(binder: ClassDef) = standIns().map { if (it.type == BINDER) binder else it }
        fun rowWith(row: ClassDef) = standIns().map { if (it.type == ROW) row else it }
        val onlyWhenFalse = "doesn't read its ordinary reel's hidden kind only when $AD_FIELD is false"
        val cases = listOf(
            Triple("no binder", standIns().filter { it.type != BINDER }, "no method tags a view $SCRUBBER_TAG"),
            Triple("two binders", standIns() + binder(type = "Lfixture/OtherBinder;"), "makes $SEEK_BAR, found 2 of 2 tagging"),
            Triple(
                "the binder reads another boolean of the item",
                binderWith(binder(adRead = "iget-boolean v0, v0, $ITEM_TYPE->A18:Z")), "to read $AD_FIELD once, found 0",
            ),
            Triple(
                "the binder reads the ad answer twice",
                binderWith(binder(between = "iget-boolean v0, v0, $AD_FIELD")), "to read $AD_FIELD once, found 2",
            ),
            Triple(
                "something jumps to just after the binder's read",
                binderWith(binder(head = "if-nez p1, :after", between = ":after\nnop")), "jumps to just after its read of $AD_FIELD",
            ),
            Triple(
                "something jumps to the binder's read",
                binderWith(binder(head = "if-nez p1, :read", adRead = ":read\niget-boolean v0, v0, $AD_FIELD")),
                "jumps to its read of $AD_FIELD",
            ),
            Triple("the binder writes over its container", binderWith(binder(between = "move-object p2, v1")), "writes over the view it tags"),
            Triple("the binder tags a view of its own", binderWith(binder(tagged = "v1")), "tags a view of its own"),
            Triple(
                "the binder isn't the seek bar primitive's",
                standIns().map { if (it.type == PRIMITIVE) primitive(returns = "Landroid/view/View;") else it },
                "makes $SEEK_BAR, found 0 of 1 tagging",
            ),
            Triple("the row reads no boolean of its item", rowWith(row(adRead = "const/4 v5, 0x0")), "to read one boolean of $ITEM_TYPE, found 0"),
            Triple(
                "the row reads two booleans of its item",
                rowWith(row(adRead = "iget-boolean v5, p1, $AD_FIELD\niget-boolean v4, p1, $ITEM_TYPE->A18:Z")),
                "to read one boolean of $ITEM_TYPE, found 2",
            ),
            Triple("the row reads the ordinary reel's flag when the field is true", rowWith(row(adTest = "if-nez v5, :ordinary")), onlyWhenFalse),
            Triple("the row decides by something else", rowWith(row(adTest = "if-eqz p3, :ordinary")), onlyWhenFalse),
            Triple(
                "the row writes over the field before testing it",
                rowWith(row(adRead = "iget-boolean v5, p1, $AD_FIELD\nconst/4 v5, 0x0")), "writes over $AD_FIELD before testing it",
            ),
        )
        for ((what, classes, reason) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains(reason))
            assertNothingWritten(what, context)
        }
    }

    /**
     * 451 also tags its legacy attached scrubber's container clips_scrubber_, in a lambda reading
     * that component, which makes no bar. The binder is the one reading the primitive that makes the
     * bar, and the legacy one is left as it was.
     */
    @Test
    fun aTaggerReadingAComponentThatMakesNoBarIsntTheBinder() {
        val legacy = binder(type = LEGACY_BINDER, primitive = LEGACY)
        val context = PatchContexts.of(standIns() + legacy + classOf(LEGACY))
        val sites = context.findReelSeekBarSites()
        assertEquals(BINDER, sites.bind.method.definingClass)
        context.applyReelSeekBar(sites)
        assertBindHookedAfterTheAdRead("stand-in", context.method(BINDER, "invoke"), context.method(ROW, "A00"))
        assertEquals(
            "the legacy tagger is left as it was",
            legacy.methods.single().implementation!!.instructions.map { it.opcode },
            context.method(LEGACY_BINDER, "invoke").implementation!!.instructions.map { it.opcode },
        )
    }

    /**
     * 451's 385713109 and 385713111 ask a static string pool for the tag instead of loading it, so
     * the binder holds no tag of its own. It's found all the same and hooked where it would be.
     */
    @Test
    fun aTagAskedOfAStringPoolIsFound() {
        val pooled = binder(tag = "const/16 v0, 0x2a\ninvoke-static {v0}, $POOL->A00(I)Ljava/lang/String;\nmove-result-object v0")
        val pool = classOf(
            POOL,
            method(
                POOL, "A00", listOf("I"), "Ljava/lang/String;", 2,
                """
                    packed-switch p0, :keys
                    const/4 v0, 0x0
                    return-object v0
                    :tag
                    const-string v0, "$SCRUBBER_TAG"
                    return-object v0
                    :keys
                    .packed-switch 0x2a
                        :tag
                    .end packed-switch
                """,
            ),
        )
        val context = PatchContexts.of(standIns().filter { it.type != BINDER } + pooled + pool)
        val sites = context.findReelSeekBarSites()
        assertEquals(BINDER, sites.bind.method.definingClass)
        context.applyReelSeekBar(sites)
        assertBindHookedAfterTheAdRead("pooled tag", context.method(BINDER, "invoke"), context.method(ROW, "A00"))
    }

    /**
     * An own read before a shared read in one method: the writes are planned on the method as it
     * was found and made last first, so neither moves the other's place.
     */
    @Test
    fun twoReadsInOneMethodKeepTheirPlaces() {
        val mixed = classOf(
            MIXED,
            method(
                MIXED, "A00", listOf("Z"), "J", 9,
                """
                    invoke-static {}, $CONFIGS->A00()Ljava/lang/Object;
                    move-result-object v4
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    invoke-static {v4, v0, v1}, $WRAPPER->A08(Ljava/lang/Object;J)J
                    move-result-wide v2
                    if-nez p0, :ordinary
                    const-wide v0, $AD_MIN_SECONDS_HEX
                    :read
                    check-cast v4, $CONFIG
                    invoke-interface {v4, v0, v1}, $CONFIG->CbE(J)J
                    move-result-wide v5
                    add-long/2addr v5, v2
                    return-wide v5
                    :ordinary
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    goto :read
                """,
            ),
        )
        val context = PatchContexts.of(listOf(mixed))
        val loads = context.findFlagLoads("test", ORGANIC_MIN_SECONDS, "J")
        assertEquals(listOf(false, true), loads.map { it.shared })

        context.answerFlagLoads(loads.map { it to MIN_SECONDS })

        val method = context.method(MIXED, "A00")
        val code = method.implementation!!.instructions.toList()
        assertEquals("hooks", 2, code.count { it.calls(MIN_SECONDS) })
        val own = code.indexOfFirst { it.isLoadOf(ORGANIC_MIN_SECONDS) }
        assertTrue("the own read answers in place", code[own + 3].calls(MIN_SECONDS))
        val ordinary = code.indexOfLast { it.isLoadOf(ORGANIC_MIN_SECONDS) }
        assertAnsweredOnItsOwn("mixed", method, ORGANIC_MIN_SECONDS, AD_MIN_SECONDS, MIN_SECONDS, ordinary)
    }

    /** A fourth read, or one fewer, is an update the patch hasn't seen, and nothing is written. */
    @Test
    fun anotherCountOfMinimumReadsFailsBeforeAnythingChanges() {
        val extra = classOf(
            EXTRA,
            method(
                EXTRA, "A00", emptyList(), "J", 4,
                """
                    invoke-static {}, $CONFIGS->A00()Ljava/lang/Object;
                    move-result-object v2
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    invoke-static {v2, v0, v1}, $WRAPPER->A08(Ljava/lang/Object;J)J
                    move-result-wide v0
                    return-wide v0
                """,
            ),
        )
        for ((what, classes) in listOf("four reads" to standIns() + extra, "two reads" to standIns().filter { it.type != PROGRESS_STATE })) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains("expected 3 reads of the shortest reel"))
            assertNothingWritten(what, context)
        }
    }

    /**
     * Hide the seek bar on reels (#680) needs the sideways drag, so a builder missing one of its reads, or a
     * read of it outside the method reading the minimum, is an update the patch hasn't seen.
     */
    @Test
    fun aDragReadThePatchHasntSeenFailsBeforeAnythingChanges() {
        val elsewhere = classOf(
            EXTRA,
            method(
                EXTRA, "A00", emptyList(), "Z", 4,
                """
                    invoke-static {}, $CONFIGS->A00()Ljava/lang/Object;
                    move-result-object v2
                    const-wide v0, 0x${ATTACHED_PASS_THROUGH.toString(16)}L
                    invoke-static {v2, v0, v1}, $WRAPPER->A1H(Ljava/lang/Object;J)Z
                    move-result v0
                    return v0
                """,
            ),
        )
        val cases = listOf(
            "missing" to standIns().map { if (it.type == PROGRESS_STATE) progressState(dragFlag = 0x81092d001033cbL) else it },
            "moved" to standIns().map { if (it.type == PROGRESS_STATE) progressState(dragFlag = 0x81092d001033cbL) else it } + elsewhere,
        )
        for ((what, classes) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains("sideways drag scrubs a reel"))
            assertNothingWritten(what, context)
        }
    }

    /** No read of the hidden kind, or two, fails before anything changes. */
    @Test
    fun anotherCountOfHiddenKindReadsFailsBeforeAnythingChanges() {
        val second = classOf(
            EXTRA,
            method(
                EXTRA, "A00", emptyList(), "Z", 4,
                """
                    invoke-static {}, $CONFIGS->A00()Ljava/lang/Object;
                    move-result-object v2
                    const-wide v0, $ORGANIC_LAZY_HEX
                    check-cast v2, $CONFIG
                    invoke-interface {v2, v0, v1}, $CONFIG->BXd(J)Z
                    move-result v0
                    return v0
                """,
            ),
        )
        val cases = listOf(
            "two reads" to standIns() + second,
            "none" to standIns().map { if (it.type == ROW) row(lazyFlag = 0x81092d001033cbL) else it },
        )
        for ((what, classes) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains("expected 1 read of the hidden seek bar flag"))
            assertNothingWritten(what, context)
        }
    }

    /**
     * Without the seek bar's own methods around the reads (the attached check, the row's state taking the
     * viewer's settings, and the check asking the attached one with the row's reel item), they could be
     * anything's, so the patch stops.
     */
    @Test
    fun aReadOutsideTheSeekBarsMethodsFailsBeforeAnythingChanges() {
        val calculate = "expected one static check taking a $VIEWER_CONFIG and a $ITEM_TYPE to ask $UTIL->A0M(${SESSION}JZZ)Z, found 0"
        val cases = listOf(
            Triple(
                "no attached check",
                standIns().map { if (it.type == UTIL) util(attached = listOf(SESSION, "J", "Z")) else it },
                "expected one static (${SESSION}JZZ)Z check reading the shortest reel with a seek bar, found 0",
            ),
            Triple(
                "the attached check isn't static",
                standIns().map { if (it.type == UTIL) util(attachedStatic = false) else it },
                "expected one static (${SESSION}JZZ)Z check reading the shortest reel with a seek bar, found 0",
            ),
            Triple("no check asking the attached one", standIns().filter { it.type != ITEM }, calculate),
            Triple("calculate asks another method", standIns().map { if (it.type == ITEM) item(calls = "A0K") else it }, calculate),
            Triple("calculate takes something else first", standIns().map { if (it.type == ITEM) item(config = "Ljava/lang/Object;") else it }, calculate),
            Triple(
                "the row takes something else first",
                standIns().map { if (it.type == ROW) row(config = "Ljava/lang/Object;") else it },
                "which doesn't take a $VIEWER_CONFIG first",
            ),
            Triple(
                "the row asks no reader of the minimum",
                standIns().map { if (it.type == ROW) row(calls = "A0K") else it },
                "asks no method reading the shortest reel with a seek bar",
            ),
        )
        for ((what, classes, reason) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains(reason))
            assertNothingWritten(what, context)
        }
    }

    /** The player's callback is the one instance method taking the reel item, the position and the length that loads [PREVIEW_END]. */
    @Test
    fun anotherPlayerCallbackFailsBeforeAnythingChanges() {
        val second = classOf(
            EXTRA,
            method(
                EXTRA, "A00", listOf(ITEM_TYPE, "I", "I"), "V", 4,
                """
                    const-string v0, "$PREVIEW_END"
                    return-void
                """,
                static = false,
            ),
        )
        val cases = listOf(
            Triple("two callbacks", standIns() + second, "found 2"),
            Triple("the callback is static", standIns().map { if (it.type == PLAYER) player(static = true) else it }, "found 0"),
            Triple(
                "the callback takes the length first",
                standIns().map { if (it.type == PLAYER) player(parameters = listOf(ITEM_TYPE, "J", "I")) else it },
                "found 0",
            ),
        )
        for ((what, classes, reason) in cases) {
            val context = PatchContexts.of(classes)
            val refusal = assertThrows(what, PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
            assertTrue("$what: ${refusal.message}", refusal.message!!.contains("to load \"$PREVIEW_END\", $reason"))
            assertNothingWritten(what, context)
        }
    }

    @Test
    fun noSeekBarClassFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(standIns().filter { it.type != SEEK_BAR })
        val refusal = assertThrows(PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
        assertTrue(refusal.message, refusal.message!!.contains("has no $SEEK_BAR"))
        assertNothingWritten("no seek bar", context)
    }

    /** Two methods named onProgressChanged leave the patch unsure which is the listener's. */
    @Test
    fun anAmbiguousOnProgressChangedFailsBeforeAnythingChanges() {
        val overload = method(
            SEEK_BAR, PROGRESS_CHANGED, listOf("Landroid/widget/SeekBar;", "I"), "V", 3, "return-void", static = false,
        )
        val context = PatchContexts.of(standIns().map { if (it.type == SEEK_BAR) seekBar(overload) else it })
        val refusal = assertThrows(PatchException::class.java) { context.applyReelSeekBar(context.findReelSeekBarSites()) }
        assertTrue(refusal.message, refusal.message!!.contains("expected one $PROGRESS_CHANGED"))
        assertNothingWritten("two listeners", context)
    }

    /** A class of that name that isn't a SeekBar would fail verification with the bar handed over as one. */
    @Test
    fun aSeekBarThatIsNoSeekBarFailsThePatch() {
        val context = PatchContexts.of(standIns().map { if (it.type == APPCOMPAT_SEEK_BAR) classOf(APPCOMPAT_SEEK_BAR, superclass = "Landroid/widget/ProgressBar;") else it })
        assertThrows(PatchException::class.java) { context.findReelSeekBarSites() }
    }

    /** A load that goes nowhere near a read is an update the patch hasn't seen. */
    @Test
    fun aLoadFeedingNoReadFailsThePatch() {
        val stray = classOf(
            EXTRA,
            method(
                EXTRA, "A00", emptyList(), "J", 2,
                """
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    return-wide v0
                """,
            ),
        )
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(stray)).findFlagLoads("test", ORGANIC_MIN_SECONDS, "J") }
    }

    /**
     * In each declared build every read is found and checked against the seek bar's methods, and after
     * the patch each ordinary reel's branch answers through the extension while the ads' reads stay as
     * they were. On 456's 386214574 that's the minimum in LX/04mo's two checks (shared with the ads'
     * minimum) and in the progress controller's limits (its own), the hidden kind in the scrubber
     * row's state (shared with the ads' flag), and VideoScrubberSeekBar.onProgressChanged.
     */
    @Test
    fun eachDeclaredBuildKeepsTheSeekBar() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildKeepsTheSeekBarToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        val tagging = mutableListOf<ClassDef>()
        val builders = HashSet<String>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                classDef.methods.filter { it.buildsTheTag() }.mapTo(builders) { "$it" }
                if (classDef.type == SEEK_BAR || classDef.type == APPCOMPAT_SEEK_BAR ||
                    classDef.methods.any {
                        it.loadsAny(ORGANIC_MIN_SECONDS, ORGANIC_LAZY) || it.asksAnAttachedCheck() ||
                            it.holdsString(PROGRESS_CONTROLLER_ERROR) || it.holdsString(PREVIEW_END) ||
                            it.holdsString(SCRUBBER_TAG) || it.returnType == SEEK_BAR || it.tagsFromAPool()
                    }
                ) {
                    holders += ImmutableClassDef.of(classDef)
                } else if (classDef.methods.any { it.tagsFromAStaticString() }) {
                    tagging += ImmutableClassDef.of(classDef)
                }
            }
        }
        // 385914510's binder calls a static method of its own for the tag, so the classes calling one come too.
        holders += tagging.filter { classDef -> classDef.methods.any { method -> method.calls().any { "$it" in builders } } }
        // The photo's hooks read the reel item's class and the method working out a post's kind.
        val itemType = holders.flatMap { it.methods }.first { it.loadsAny(ORGANIC_LAZY) }.parameterTypes[1].toString()
        holders += FixtureDex.classes(bundle, setOf(itemType)).values
        holders += FixtureDex.classesLoading(bundle, KIND_OF_REPORT)
        holders += ExtensionDex.classDef(REEL_ITEM_READER)
        val context = PatchContexts.of(holders.distinctBy { it.type })

        val sites = context.findReelSeekBarSites()
        assertEquals("${label}: reads of the minimum", 3, sites.lengths.size)
        assertEquals("${label}: shared reads of the minimum", 2, sites.lengths.count { it.shared })
        assertTrue("${label}: the hidden kind's read is shared", sites.lazy.shared)
        context.applyReelSeekBar(sites)

        for (load in sites.lengths) {
            assertAnsweredOnItsOwn(
                "${label} ${load.type}->${load.name}", context.method(load),
                ORGANIC_MIN_SECONDS, if (load.shared) AD_MIN_SECONDS else null, MIN_SECONDS,
            )
        }
        assertAnsweredOnItsOwn(
            "${label} ${sites.lazy.type}->${sites.lazy.name}", context.method(sites.lazy),
            ORGANIC_LAZY, AD_LAZY, LAZY,
        )
        assertEquals("${label}: the drag's reads sit in one method", 1, sites.passThroughs.map { it.type to it.name }.distinct().size)
        assertDragAnswered(label, context.method(sites.passThroughs.first()))
        assertProgressHookedFirst(label, context.method(SEEK_BAR, PROGRESS_CHANGED))
        assertBindHookedAfterTheAdRead(label, context.method(sites.bind.method), context.method(sites.lazy))
        assertReelIdAsked(label, context, itemType)
        assertTouchHandedOver(label, context.method(sites.touch.method), sites.touch)
        assertPhotoHooked(label, context, sites)
        assertHiddenBarsHooked(label, context, sites)
    }

    /**
     * Hide the seek bar on reels (#680): the touch-only check and the strip check each ask [BARS_HIDDEN] first, and off land
     * on the photo's switch. On, the touch-only check asks the controller for the reel on screen again and hands
     * [HIDDEN_TOUCH_ONLY] Instagram's answer and the reel's ad flag, then goes on to the photo's hook with that answer, and
     * the strip check answers yes right there.
     */
    private fun assertHiddenBarsHooked(what: String, context: BytecodePatchContext, sites: ReelSeekBarSites) {
        val onTouch = sites.photo.touchOnly
        val touchFlow = ControlFlow.of(context.method(onTouch.method))
        val touch = touchFlow.instructions
        val at = onTouch.at
        assertTrue("$what: the hidden bars' switch comes first in the touch check", touch[at].calls(BARS_HIDDEN))
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, touch[at + 1].opcode)
        assertEquals("$what: its test", Opcode.IF_EQZ, touch[at + 2].opcode)
        val photoAt = (at until touch.size).first { touch[it].calls(PHOTO_BAR_ON) }
        assertTrue("$what: off, the photo's switch", photoAt in touchFlow.normal[at + 2])
        assertEquals("$what: touch hooks", 1, touch.count { it.calls(HIDDEN_TOUCH_ONLY) })
        val hookAt = touch.indexOfFirst { it.calls(HIDDEN_TOUCH_ONLY) }
        val asked = (at until hookAt).single { touch[it].calls(onTouch.current) }
        assertEquals("$what: of the controller", listOf(onTouch.controller), touch[asked].arguments())
        val reel = (touch[asked + 1] as OneRegisterInstruction).registerA
        assertEquals("$what: no reel on screen", Opcode.IF_EQZ, touch[asked + 2].opcode)
        assertTrue("$what: no reel, the photo's switch", photoAt in touchFlow.normal[asked + 2])
        val adRead = touch[asked + 3]
        assertEquals("$what: the ad flag", Opcode.IGET_BOOLEAN, adRead.opcode)
        assertEquals("$what: the reel's ad flag", onTouch.ad, (adRead.reference() as? FieldReference)?.toString())
        assertEquals("$what: of the reel", reel, (adRead as TwoRegisterInstruction).registerB)
        assertEquals("$what: Instagram's answer and the ad flag go in", listOf(onTouch.answer, adRead.registerA), touch[hookAt].arguments())
        assertEquals("$what: the answer comes back", onTouch.answer, (touch[hookAt + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: then the photo's switch", photoAt, hookAt + 2)

        val onStrip = sites.photo.strip
        val stripFlow = ControlFlow.of(context.method(onStrip.method))
        val strip = stripFlow.instructions
        val start = onStrip.at
        assertTrue("$what: the hidden bars' switch comes first in the strip check", strip[start].calls(BARS_HIDDEN))
        val yes = (strip[start + 1] as OneRegisterInstruction).registerA
        assertEquals("$what: its test", Opcode.IF_EQZ, strip[start + 2].opcode)
        assertEquals("$what: of its answer", yes, (strip[start + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: a yes is returned", Opcode.RETURN, strip[start + 3].opcode)
        assertEquals("$what: the yes itself", yes, (strip[start + 3] as OneRegisterInstruction).registerA)
        assertTrue("$what: off, the photo's switch", strip[start + 4].calls(PHOTO_BAR_ON))
        assertTrue("$what: off, on to it", start + 4 in stripFlow.normal[start + 2])
    }

    /**
     * Each of a photo's hooks asks [PHOTO_BAR_ON] first, and with the switch off lands on the instruction
     * right after the hook, which was Instagram's next: the check's return, unchanged, or the start of its method.
     */
    private fun assertPhotoHooked(what: String, context: BytecodePatchContext, sites: ReelSeekBarSites) {
        val photo = sites.photo
        // The seek preview's own switched block runs first in the row, and lets go straight onto the photo bar's switch (#567).
        val rowFlow = ControlFlow.of(context.method(photo.row))
        val photoRowAt = rowFlow.instructions.indexOfFirst { it.calls(PHOTO_BAR_ON) }
        assertTrue("$what: the preview's switch comes first", rowFlow.instructions[0].calls(SEEK_PREVIEW_ON))
        assertEquals("$what: the post is remembered once", 1, rowFlow.instructions.count { it.calls(REMEMBER_REEL) })
        assertTrue("$what: off, the row goes on to the photo bar's switch", photoRowAt in rowFlow.normal[2])
        // The hidden bars' block (#680) goes in ahead of the photo's in the touch-only and strip checks.
        val onTouch = photo.touchOnly
        val touch = context.method(onTouch.method).implementation!!.instructions.toList()
        val photoTouchAt = (onTouch.at until touch.size).first { touch[it].calls(PHOTO_BAR_ON) }
        for ((method, start, hook) in listOf(
            Triple(context.method(photo.answer), photo.answerAt, PHOTO_REEL),
            Triple(context.method(photo.row), photoRowAt, PHOTO_ROW),
            Triple(context.method(photo.player), 0, PHOTO_PROGRESS),
            Triple(context.method(onTouch.method), photoTouchAt, PHOTO_TOUCH_ONLY),
        )) {
            val name = "$what ${method.definingClass}->${method.name}"
            val flow = ControlFlow.of(method)
            val code = flow.instructions
            assertTrue("$name: the switch comes first, not ${code[start].opcode}", code[start].calls(PHOTO_BAR_ON))
            assertEquals("$name: the switch's answer", Opcode.MOVE_RESULT, code[start + 1].opcode)
            assertEquals("$name: the test", Opcode.IF_EQZ, code[start + 2].opcode)
            assertEquals("$name: hooks", 1, code.count { it.calls(hook) })
            var after = code.indexOfFirst { it.calls(hook) } + 1
            if (code[after].opcode == Opcode.MOVE_RESULT) after++
            assertTrue("$name: off, Instagram's next instruction", after in flow.normal[start + 2])
        }
        val answer = context.method(photo.answer).implementation!!.instructions.toList()
        val hookAt = answer.indexOfFirst { it.calls(PHOTO_REEL) }
        assertEquals("$what: the hook's answer", photo.answerRegister, (answer[hookAt + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the answer goes back", Opcode.RETURN, answer[hookAt + 2].opcode)
        assertEquals("$what: Instagram's answer goes in", photo.answerRegister, answer[hookAt].arguments().first())

        // The touch-only check asks the controller again for the reel on screen, and answers back where Instagram returned.
        val touchAt = touch.indexOfFirst { it.calls(PHOTO_TOUCH_ONLY) }
        val asked = touch.indices.filter { it in photoTouchAt until touchAt && touch[it].calls(onTouch.current) }
        assertEquals("$what: the reel on screen asked once in the hook", 1, asked.size)
        assertEquals("$what: of the controller", listOf(onTouch.controller), touch[asked.single()].arguments())
        assertEquals("$what: the touch hook's answer", onTouch.answer, (touch[touchAt + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the touch answer goes back", Opcode.RETURN, touch[touchAt + 2].opcode)
        assertEquals("$what: Instagram's touch answer goes in", onTouch.answer, touch[touchAt].arguments().first())

        // The strip check answers yes itself for a photo with a song, and otherwise goes on to its length check (#10).
        val onStrip = photo.strip
        val stripFlow = ControlFlow.of(context.method(onStrip.method))
        val strip = stripFlow.instructions
        val photoStripAt = (onStrip.at until strip.size).first { strip[it].calls(PHOTO_BAR_ON) }
        val length = (photoStripAt until strip.size).first { strip[it].opcode == Opcode.CMP_LONG }
        assertEquals("$what: the strip's switch test", Opcode.IF_EQZ, strip[photoStripAt + 2].opcode)
        assertTrue("$what: off, the strip check goes by the length", length in stripFlow.normal[photoStripAt + 2])
        assertEquals("$what: strip hooks", 1, strip.count { it.calls(PHOTO_STRIP) })
        val stripAt = strip.indexOfFirst { it.calls(PHOTO_STRIP) }
        val reelId = strip.indices.filter { it in photoStripAt until stripAt && strip[it].calls(photo.id) }
        assertEquals("$what: the strip's reel asked its id", listOf(listOf(onStrip.item)), reelId.map { strip[it].arguments() })
        val yes = (strip[stripAt + 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the strip hook's answer tested", Opcode.IF_EQZ, strip[stripAt + 2].opcode)
        assertEquals("$what: of the answer", yes, (strip[stripAt + 2] as OneRegisterInstruction).registerA)
        assertTrue("$what: a no goes by the length", length in stripFlow.normal[stripAt + 2])
        assertEquals("$what: a yes is returned", Opcode.RETURN, strip[stripAt + 3].opcode)
        assertEquals("$what: the yes itself", yes, (strip[stripAt + 3] as OneRegisterInstruction).registerA)
    }

    private fun Method.loadsAny(vararg flags: Long): Boolean = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in flags
    } == true

    /** Whether this method takes the viewer's settings first and calls a `(UserSession, long, boolean, boolean)boolean`, as the bar's check does. */
    private fun Method.asksAnAttachedCheck(): Boolean = parameterTypes.firstOrNull()?.toString() == VIEWER_CONFIG && calls().any {
        it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(SESSION, "J", "Z", "Z")
    }

    private fun Method.holdsString(text: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
    } == true

    /**
     * Whether this method tags a view and asks a static `(I)String` pool for a string, the way 451's
     * 385713109 and 385713111 get the binder's tag. The pool itself holds the tag, so it comes along.
     */
    private fun Method.tagsFromAPool(): Boolean {
        val calls = calls()
        return calls.any { "$it" == "Landroid/view/View;->setTag(Ljava/lang/Object;)V" } &&
            calls.any { it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(Any::toString) == listOf("I") }
    }

    /** Whether this method tags a view and asks some other static method for a String, as 385914510's binder asks LX/0JcH->A01. */
    private fun Method.tagsFromAStaticString(): Boolean {
        val code = implementation?.instructions?.toList().orEmpty()
        return calls().any { "$it" == "Landroid/view/View;->setTag(Ljava/lang/Object;)V" } && code.any { instruction ->
            (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                ((instruction as ReferenceInstruction).reference as MethodReference).returnType == "Ljava/lang/String;"
        }
    }

    /** Whether this is a static method answering a String that holds the tag and tags nothing, as 385914510's LX/0JcH->A01 builds it. */
    private fun Method.buildsTheTag(): Boolean =
        AccessFlags.STATIC.isSet(accessFlags) && returnType == "Ljava/lang/String;" && parameterTypes.map(Any::toString) != listOf("I") &&
            holdsString(SCRUBBER_TAG) && calls().none { "$it" == "Landroid/view/View;->setTag(Ljava/lang/Object;)V" }

    private fun Method.calls(): List<MethodReference> =
        implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()

    private fun BytecodePatchContext.method(type: String, name: String): Method = mutableClassDefBy(type).methods.single { it.name == name }

    private fun BytecodePatchContext.method(method: Method): Method = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

    private fun BytecodePatchContext.method(load: FlagLoad): Method = mutableClassDefBy(load.type).methods.single {
        it.name == load.name && it.parameterTypes.map(CharSequence::toString) == load.parameters
    }

    /**
     * Each read of whether a sideways drag scrubs is answered in place: the load, the read, its move-result, then
     * the hook on that register and its answer back into it, and nothing else in [method] calls the hook.
     */
    private fun assertDragAnswered(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: drag hooks", 2, code.count { it.calls(PASS_THROUGH) })
        for (flag in listOf(UNIVERSAL_PASS_THROUGH, ATTACHED_PASS_THROUGH)) {
            val name = "$what ${flag.toString(16)}"
            var at = code.indices.single { code[it].isLoadOf(flag) } + 1
            if (code[at].opcode == Opcode.CHECK_CAST) at++
            assertEquals("$name: the read's answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
            val register = (code[at + 1] as OneRegisterInstruction).registerA
            val call = code[at + 2]
            assertTrue("$name: the hook follows the read", call.calls(PASS_THROUGH))
            assertEquals("$name: the hook's register", listOf(register), call.arguments())
            assertEquals("$name: the answer", Opcode.MOVE_RESULT, code[at + 3].opcode)
            assertEquals("$name: the answer's register", register, (code[at + 3] as OneRegisterInstruction).registerA)
        }
    }

    /**
     * From the load of [flag] (the one at [loadAt], or the method's only one): a cast if the read
     * has one, the read, its move-result, the call to [hook] with that register and the answer back
     * in it. With [other], the read is shared, so a goto follows that lands just past [other]'s own
     * read, and that read has no hook after it. The method calls [hook] once, or as many times as it
     * loads [flag].
     */
    private fun assertAnsweredOnItsOwn(what: String, method: Method, flag: Long, other: Long?, hook: String, loadAt: Int? = null) {
        val flow = ControlFlow.of(method)
        val code = flow.instructions
        val load = loadAt ?: code.indices.single { code[it].isLoadOf(flag) }
        val id = (code[load] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", code.count { it.isLoadOf(flag) }, code.count { it.calls(hook) })
        var at = load + 1
        if (code[at].opcode == Opcode.CHECK_CAST) at++
        val read = code[at]
        assertTrue("$what: the read after the load is ${read.opcode}", read.reference() is MethodReference)
        assertEquals("$what: the read takes the id last", listOf(id, id + 1), read.arguments().takeLast(2))
        val wide = (read.reference() as MethodReference).returnType == "J"
        val moveResult = if (wide) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT
        assertEquals("$what: the read's answer", moveResult, code[at + 1].opcode)
        val register = (code[at + 1] as OneRegisterInstruction).registerA
        val call = code[at + 2]
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertTrue("$what: the hook", call.calls(hook))
        assertEquals("$what: the hook's register", register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the hook's width", if (wide) 2 else 1, call.registerCount)
        assertEquals("$what: the answer", moveResult, code[at + 3].opcode)
        assertEquals("$what: the answer's register", register, (code[at + 3] as OneRegisterInstruction).registerA)
        if (other == null) {
            assertFalse("$what: an own read jumps away", code[at + 4].opcode in GOTOS)
            return
        }
        assertTrue("$what: the own read goes back with a goto, not ${code[at + 4].opcode}", code[at + 4].opcode in GOTOS)
        val landing = flow.normal[at + 4].single()
        val otherLoad = code.indices.single { code[it].isLoadOf(other) }
        var shared = flow.normal[otherLoad].single()
        while (code[shared].opcode in GOTOS || code[shared].opcode == Opcode.CHECK_CAST) shared = flow.normal[shared].single()
        assertTrue("$what: the shared read", code[shared].reference() is MethodReference)
        assertEquals("$what: the own read lands past the shared one", shared + 2, landing)
        assertFalse("$what: the shared read is answered too", code[shared + 2].calls(hook))
    }

    /**
     * The binder calls [BIND] once, right after its one read of the boolean of the reel item that
     * [row] decides between the ordinary reel's flag and the ad's by, with the container it's
     * handed (its second argument) and the register the read went to.
     */
    private fun assertBindHookedAfterTheAdRead(what: String, binder: Method, row: Method) {
        val item = row.parameterTypes[1].toString()
        val rowField = row.implementation!!.instructions.single {
            it.opcode == Opcode.IGET_BOOLEAN && (it.reference() as FieldReference).definingClass == item
        }.reference().toString()
        val code = binder.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { it.calls(BIND) })
        val call = code.indexOfFirst { it.calls(BIND) }
        val read = code[call - 1]
        assertEquals("$what: the read before the hook", Opcode.IGET_BOOLEAN, read.opcode)
        assertEquals("$what: the field the row decides by", rowField, read.reference().toString())
        // 452 folds the legacy component's binder into the same switch, so its read of the field stays unhooked.
        assertTrue("$what: reads of the field", code.count { it.reference()?.toString() == rowField } >= 1)
        val handed = binder.parameterRegisterNumber(1)
        val (container, answer) = code[call].arguments()
        assertEquals("$what: the answer", (read as OneRegisterInstruction).registerA, answer)
        // The reel's id goes over first, from the item the read reads, since 454 tags the container only on some screens.
        assertEquals("$what: reel id hooks", 1, code.count { it.calls(BIND_REEL) })
        assertTrue("$what: the reel id hook right before the read", code[call - 2].calls(BIND_REEL))
        assertEquals("$what: the container and the item", listOf(container, (read as TwoRegisterInstruction).registerB), code[call - 2].arguments())
        if (container != handed) {
            // 453 copies the handed container into a local before its switch, once, and hooks with the copy.
            val dispatch = code.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
            val copies = (0 until dispatch).filter { (code[it] as? OneRegisterInstruction)?.registerA == container }
            assertEquals("$what: one write of the container's local before the switch", 1, copies.size)
            val copy = code[copies.single()]
            assertTrue("$what: a copy", copy.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16))
            assertEquals("$what: of the handed container", handed, (copy as TwoRegisterInstruction).registerB)
        }
    }

    /** The extension's [REEL_ITEM_ID] stub casts its parameter to the reel item [itemType] and answers its getId. */
    private fun assertReelIdAsked(what: String, context: BytecodePatchContext, itemType: String) {
        val code = context.method(REEL_ITEM_READER, "id").implementation!!.instructions.toList()
        assertEquals("$what: the stub's cast", listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), code.take(4).map { it.opcode })
        assertEquals("$what: to the reel item", itemType, code[0].reference().toString())
        assertEquals("$what: its id", "$itemType->getId()Ljava/lang/String;", code[1].reference().toString())
    }

    /** The bar's listener calls [PROGRESS] first, with `this` and the position, and only there. */
    private fun assertProgressHookedFirst(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { it.calls(PROGRESS) })
        val first = code.first()
        assertEquals("$what: the first instruction", Opcode.INVOKE_STATIC, first.opcode)
        assertTrue("$what: the first instruction", first.calls(PROGRESS))
        assertEquals("$what: this and the position", listOf(method.localRegisterCount(), method.parameterRegisterNumber(1)), first.arguments())
    }

    /** The strip's one setOnTouchListener call is now the call to [LISTEN] in its place, with the same strip and listener. */
    private fun assertTouchHandedOver(what: String, method: Method, site: TouchSite) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: Instagram's own listener calls left", 0, code.count { it.calls("Landroid/view/View;->setOnTouchListener") })
        val hooks = code.indices.filter { code[it].calls(LISTEN) }
        assertEquals("$what: the hook, in the call's place", listOf(site.at), hooks)
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC, code[site.at].opcode)
        assertEquals("$what: the strip and the listener", listOf(site.view, site.listener), code[site.at].arguments())
    }

    private fun assertNothingWritten(what: String, context: BytecodePatchContext) {
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                assertFalse(
                    "$what: ${classDef.type}->${method.name} was changed",
                    method.implementation?.instructions?.any { it.calls(REEL_SEEK_BAR) || it.calls(LISTEN) } == true,
                )
            }
        }
    }

    private fun Instruction.isLoadOf(flag: Long) = opcode == Opcode.CONST_WIDE && (this as WideLiteralInstruction).wideLiteral == flag

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.calls(hook: String) = (reference() as? MethodReference)?.toString()?.startsWith(hook) == true

    private fun Instruction.arguments(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private companion object {
        const val UTIL = "Lfixture/ClipsExperimentUtil;"
        const val PROGRESS_STATE = "Lfixture/ClipsProgressState;"
        const val ROW = "Lfixture/ClipsScrubberRow;"
        const val ITEM = "Lfixture/ClipsItemUseCase;"
        const val MIXED = "Lfixture/Mixed;"
        const val EXTRA = "Lfixture/Extra;"
        const val CONFIGS = "Lfixture/Configs;"
        const val WRAPPER = "Lfixture/MobileConfigs;"
        const val MODE = "Lfixture/ScrubberMode;"
        const val CONFIG = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val APPCOMPAT_SEEK_BAR = "Landroidx/appcompat/widget/AppCompatSeekBar;"
        const val ITEM_TYPE = "Lfixture/ClipsItem;"
        const val BINDER = "Lfixture/ScrubberBinder;"
        const val PRIMITIVE = "Lfixture/ScrubberPrimitive;"
        const val LEGACY_BINDER = "Lfixture/LegacyScrubberBinder;"
        const val LEGACY = "Lfixture/LegacyScrubberComponent;"
        const val POOL = "Lfixture/StringPool;"
        const val STRIP = "Lfixture/ClipsProgressController;"
        const val STRIP_LISTENER = "Lfixture/StripListener;"
        const val KIND_EXT = "Lfixture/MixedMediaTypeExt;"
        const val KIND_TYPE = "Lfixture/MixedMediaType;"
        const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"
        const val PLAYER = "Lfixture/ClipsVideoPlayerController;"

        const val SET_ON_TOUCH_CALL = "invoke-virtual {v2, v0}, Landroid/view/View;->setOnTouchListener(Landroid/view/View\$OnTouchListener;)V"

        /** The reel item's field saying it's an ad, which the row and the binder read. */
        const val AD_FIELD = "$ITEM_TYPE->A17:Z"

        /** The ads' minimum and hidden kind on 455 (454: 0x8208d0000013a4 and 0x8108d000153243), read in the same places as the ordinary reels'. */
        const val AD_MIN_SECONDS = 0x8208b900001361L
        const val AD_LAZY = 0x8108b9001531f7L

        val ORGANIC_MIN_SECONDS_HEX = "0x${ORGANIC_MIN_SECONDS.toString(16)}L"
        val AD_MIN_SECONDS_HEX = "0x${AD_MIN_SECONDS.toString(16)}L"
        val ORGANIC_LAZY_HEX = "0x${ORGANIC_LAZY.toString(16)}L"
        val AD_LAZY_HEX = "0x${AD_LAZY.toString(16)}L"

        val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

        fun context(): BytecodePatchContext = PatchContexts.of(standIns())

        fun standIns(): List<ClassDef> = listOf(
            util(), progressState(), row(), item(), seekBar(), classOf(APPCOMPAT_SEEK_BAR, superclass = "Landroid/widget/SeekBar;"),
            binder(), primitive(), strip(), itemType(), kindExt(), player(), ExtensionDex.classDef(REEL_ITEM_READER),
        )

        /** Shaped like 454's reel item, LX/03f3: it holds its post and answers its id. */
        fun itemType(medias: Int = 1, access: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value): ClassDef = ImmutableClassDef(
            ITEM_TYPE, access, "Ljava/lang/Object;", null, null, null,
            (0 until medias).map { ImmutableField(ITEM_TYPE, "A0g$it".removeSuffix("0"), MEDIA_TYPE, AccessFlags.PUBLIC.value, null, null, null) },
            listOf(
                method(
                    ITEM_TYPE, "getId", emptyList(), "Ljava/lang/String;", 2,
                    """
                        const/4 v0, 0x0
                        return-object v0
                    """,
                    static = false,
                ),
            ),
        )

        /** Shaped like 454's LX/087h->A00, which works out a post's kind of reel and reports a carousel it doesn't know. */
        fun kindExt(): ClassDef = classOf(
            KIND_EXT,
            method(
                KIND_EXT, "A00", listOf(MEDIA_TYPE), KIND_TYPE, 2,
                """
                    const-string v0, "$KIND_OF_REPORT"
                    const/4 v0, 0x0
                    return-object v0
                """,
            ),
        )

        /** Shaped like 456's LX/07Ft->A1U: the reel item, the position, the length and two more, logging a preview's end. */
        fun player(static: Boolean = false, parameters: List<String> = listOf(ITEM_TYPE, "I", "I", "I", "Z")): ClassDef = classOf(
            PLAYER,
            method(
                PLAYER, "A1M", parameters, "V", 10,
                """
                    const-string v0, "$PREVIEW_END"
                    invoke-static {v0}, $CONFIGS->trace(Ljava/lang/String;)V
                    return-void
                """,
                static = static,
            ),
        )

        /**
         * Shaped like 456's Reels progress controller, LX/098b on 386214574: it reports its strip's error
         * (A0D), and its one method setting a touch listener (A0I) sets a scrubbing lambda on the strip it keeps.
         */
        fun strip(
            type: String = STRIP,
            error: String = PROGRESS_CONTROLLER_ERROR,
            set: String = SET_ON_TOUCH_CALL,
            touchHead: String = "",
            current: String = "invoke-static {p0}, $type->A07($type)$ITEM_TYPE",
            touchTail: String = "move-result v0\nreturn v0",
            adRead: String = "iget-boolean v0, v0, $AD_FIELD",
            stripTop: String = "",
            lengthRead: String = "invoke-static {p0}, $type->A03($type)J\nmove-result-wide v4",
            stripHead: String = "",
            more: List<Method> = emptyList(),
        ): ClassDef = classOf(
            type,
            method(
                type, "A0D", listOf(type), "V", 2,
                """
                    const-string v0, "$error"
                    invoke-static {v0}, $CONFIGS->trace(Ljava/lang/String;)V
                    return-void
                """,
            ),
            method(
                type, "A0L", emptyList(), "V", 4,
                """
                    iget-object v2, p0, $type->A0A:Landroid/view/View;
                    if-eqz v2, :done
                    new-instance v0, $STRIP_LISTENER
                    invoke-direct {v0, p0}, $STRIP_LISTENER-><init>(Ljava/lang/Object;)V
                    $set
                    :done
                    return-void
                """,
                static = false,
            ),
            // Shaped like 454's LX/06OS->A07, the reel on screen, or null.
            method(
                type, "A07", listOf(type), ITEM_TYPE, 2,
                """
                    iget-object v0, p0, $type->A0B:$ITEM_TYPE
                    return-object v0
                """,
            ),
            // Shaped like 456's LX/098b->A0O, its one static check of itself: whether the reel's bar shows only while touched, from its length.
            method(
                type, "A0R", listOf(type), "Z", 5,
                """
                    $touchHead
                    $current
                    move-result-object v0
                    if-nez v0, :item
                    const/4 v0, 0x0
                    return v0
                    :item
                    invoke-static {p0}, $type->A03($type)J
                    move-result-wide v2
                    iget-object v1, p0, $type->A0c:Ljava/lang/Object;
                    $adRead
                    invoke-static {v1, v2, v3, v0}, $UTIL->A0N(Ljava/lang/Object;JZ)Z
                    $touchTail
                """,
            ),
            // Shaped like 456's LX/098b->A1B, its one instance check of a reel item: whether the reel gets the strip you drag
            // from, past Instagram's own checks by its length, which letting go of a hold asks before showing the bar again.
            method(
                type, "A1C", listOf(ITEM_TYPE), "Z", 10,
                """
                    const/4 v3, 0x0
                    $stripTop
                    iget-boolean v0, p0, $type->A0Q:Z
                    if-nez v0, :no
                    $lengthRead
                    $stripHead
                    invoke-static {p1}, $CONFIGS->kind(Ljava/lang/Object;)Z
                    move-result v6
                    const-wide/16 v1, 0x0
                    cmp-long v0, v4, v1
                    if-lez v0, :no
                    const/4 v3, 0x1
                    :no
                    return v3
                """,
                static = false,
            ),
            *more.toTypedArray(),
        )

        fun method(
            type: String,
            name: String,
            parameters: List<String>,
            returnType: String,
            registers: Int,
            body: String,
            static: Boolean = true,
        ): Method = MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
                null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

        fun classOf(type: String, vararg methods: Method, superclass: String = "Ljava/lang/Object;"): ClassDef =
            ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null,
                methods.toList(),
            )

        /**
         * Shaped like 449's ClipsExperimentUtil: A0L, the hidden-kind check, and A0M, the attached
         * bar check, static and taking [attached]. Each picks the ordinary reel's minimum or the ad's in a
         * branch and reads whichever it picked in one place.
         */
        fun util(attached: List<String> = listOf(SESSION, "J", "Z", "Z"), attachedStatic: Boolean = true): ClassDef = classOf(
            UTIL,
            method(
                UTIL, "A0L", listOf(SESSION, "J", "Z"), "Z", 9,
                """
                    const/4 v4, 0x0
                    invoke-static {p0, p1, p2, p3}, $UTIL->A0K(${SESSION}JZ)Z
                    move-result v0
                    if-eqz v0, :none
                    const/4 v3, 0x1
                    xor-int/lit8 v0, p3, 0x1
                    invoke-static {p0}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v2
                    if-eqz v0, :ad
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    :read
                    check-cast v2, $CONFIG
                    invoke-interface {v2, v0, v1}, $CONFIG->CbE(J)J
                    move-result-wide v1
                    cmp-long v0, p1, v1
                    if-gez v0, :none
                    return v3
                    :ad
                    const-wide v0, $AD_MIN_SECONDS_HEX
                    goto :read
                    :none
                    return v4
                """,
            ),
            method(
                UTIL, "A0M", attached, "Z", if (attachedStatic) 10 else 11,
                """
                    const/4 v4, 0x1
                    const/4 v3, 0x0
                    invoke-static {p0}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v2
                    if-eqz p3, :ad
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    :read
                    check-cast v2, $CONFIG
                    invoke-interface {v2, v0, v1}, $CONFIG->CbE(J)J
                    move-result-wide v1
                    cmp-long v0, p1, v1
                    if-gez v0, :yes
                    return v3
                    :yes
                    return v4
                    :ad
                    const-wide v0, $AD_MIN_SECONDS_HEX
                    goto :read
                """,
                static = attachedStatic,
            ),
        )

        /**
         * Shaped like 449's progress controller limits: the minimum read on its own, through a static wrapper,
         * then (456's scrubber config builder) the two reads of whether a sideways drag scrubs. [dragFlag] is
         * the second of those.
         */
        fun progressState(dragFlag: Long = ATTACHED_PASS_THROUGH): ClassDef = classOf(
            PROGRESS_STATE,
            method(
                PROGRESS_STATE, "A00", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;", 8,
                """
                    invoke-static {p0}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v3
                    const-wide v0, $ORGANIC_MIN_SECONDS_HEX
                    invoke-static {v3, v0, v1}, $WRAPPER->A08(Ljava/lang/Object;J)J
                    move-result-wide v5
                    const-wide v0, 0x${UNIVERSAL_PASS_THROUGH.toString(16)}L
                    invoke-static {v3, v0, v1}, $WRAPPER->A1H(Ljava/lang/Object;J)Z
                    move-result v2
                    const-wide v0, 0x${dragFlag.toString(16)}L
                    invoke-static {v3, v0, v1}, $WRAPPER->A1H(Ljava/lang/Object;J)Z
                    move-result v4
                    return-object v3
                """,
            ),
        )

        /**
         * Shaped like 449's scrubber row state: taking the viewer's settings ([config]) and the reel item,
         * it reads whether the item is an ad, asks A0L with that, then reads the ad's hidden-kind flag or,
         * when the item isn't an ad, after a goto, the ordinary reel's, in one place. [adRead] reads the
         * item's field and [adTest] tests it on the way to the two flags.
         */
        fun row(
            config: String = VIEWER_CONFIG,
            calls: String = "A0L",
            lazyFlag: Long = ORGANIC_LAZY,
            adRead: String = "iget-boolean v5, p1, $AD_FIELD",
            adTest: String = "if-eqz v5, :ordinary",
        ): ClassDef = classOf(
            ROW,
            method(
                ROW, "A00", listOf(config, ITEM_TYPE, SESSION, "Z"),
                "Ljava/lang/Integer;", 11,
                """
                    $adRead
                    const-wide/16 v0, 0x5
                    invoke-static {p2, v0, v1, v5}, $UTIL->$calls(${SESSION}JZ)Z
                    move-result v0
                    if-eqz v0, :plain
                    invoke-static {p2}, $CONFIGS->A02(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v6
                    $adTest
                    const-wide v0, $AD_LAZY_HEX
                    :read
                    check-cast v6, $CONFIG
                    invoke-interface {v6, v0, v1}, $CONFIG->BXd(J)Z
                    move-result v0
                    if-eqz v0, :plain
                    sget-object v2, $MODE->LAZY:Ljava/lang/Integer;
                    return-object v2
                    :ordinary
                    const-wide v0, 0x${lazyFlag.toString(16)}L
                    goto :read
                    :plain
                    sget-object v2, $MODE->ATTACHED:Ljava/lang/Integer;
                    return-object v2
                """,
            ),
        )

        /**
         * Shaped like 449's seek bar container binder (063c.invoke): handed the container as its
         * second argument, it tags it clips_scrubber_ and the reel's id, then reads whether the
         * primitive's reel item is an ad and marks the container either way.
         */
        fun binder(
            type: String = BINDER,
            head: String = "",
            adRead: String = "iget-boolean v0, v0, $AD_FIELD",
            between: String = "",
            tagged: String = "p2",
            primitive: String = PRIMITIVE,
            tag: String = "const-string v0, \"$SCRUBBER_TAG\"",
        ): ClassDef = classOf(
            type,
            method(
                type, "invoke", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;", 6,
                """
                    check-cast p2, Landroid/view/ViewGroup;
                    const/4 v2, 0x0
                    $head
                    iget-boolean v0, p0, $type->A01:Z
                    if-eqz v0, :untagged
                    iget-object v1, p0, $type->A00:$primitive
                    iget-object v0, v1, $primitive->A04:$ITEM_TYPE
                    invoke-virtual {v0}, $ITEM_TYPE->getId()Ljava/lang/String;
                    move-result-object v1
                    $tag
                    invoke-static {v0, v1}, $CONFIGS->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    invoke-virtual {$tagged, v0}, Landroid/view/View;->setTag(Ljava/lang/Object;)V
                    :untagged
                    iget-object v1, p0, $type->A00:$primitive
                    iget-object v0, v1, $primitive->A04:$ITEM_TYPE
                    $adRead
                    $between
                    if-eqz v0, :organic
                    const v0, 0x7f0b2f0d
                    invoke-virtual {p2, v0, p1}, Landroid/view/View;->setTag(ILjava/lang/Object;)V
                    :done
                    return-object v2
                    :organic
                    const v0, 0x7f0b2f0d
                    invoke-virtual {p2, v0, v2}, Landroid/view/View;->setTag(ILjava/lang/Object;)V
                    goto :done
                """,
                static = false,
            ),
        )

        /** Shaped like 449's ScrubberSeekbarPrimitive: the class with the method that makes the bar. */
        fun primitive(returns: String = SEEK_BAR): ClassDef = classOf(
            PRIMITIVE,
            method(
                PRIMITIVE, "A01", listOf("Landroid/content/Context;"), returns, 2,
                """
                    const/4 v0, 0x0
                    return-object v0
                """,
            ),
        )

        /**
         * Shaped like 456's LX/08LX->A00, the check of whether a reel gets the bar: static, taking the viewer's
         * settings ([config]) and the reel item first, it returns what A0M answers.
         */
        fun item(config: String = VIEWER_CONFIG, calls: String = "A0M", tail: String = "move-result v0\nreturn v0"): ClassDef = classOf(
            ITEM,
            method(
                ITEM, "A00", listOf(config, ITEM_TYPE, SESSION, "J"), "Z", 9,
                """
                    const/4 v0, 0x1
                    invoke-static {p2, p3, p4, v0, v0}, $UTIL->$calls(${SESSION}JZZ)Z
                    $tail
                """,
            ),
        )

        /** Shaped like 449's VideoScrubberSeekBar.onProgressChanged: forward to the stored listener, if any. */
        fun seekBar(vararg more: Method): ClassDef = classOf(
            SEEK_BAR,
            method(
                SEEK_BAR, PROGRESS_CHANGED, listOf("Landroid/widget/SeekBar;", "I", "Z"), "V", 6,
                """
                    iget-object v0, p0, $SEEK_BAR->A00:Ljava/lang/Object;
                    if-eqz v0, :tail
                    invoke-virtual {v0}, Ljava/lang/Object;->hashCode()I
                    :tail
                    return-void
                """,
                static = false,
            ),
            *more,
            superclass = APPCOMPAT_SEEK_BAR,
        )
    }
}
