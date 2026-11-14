/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.abovecomments

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.lock.HIDDEN_CHATS_TAP
import app.morphe.patches.instagram.direct.lock.findHiddenChatsTouch
import app.morphe.patches.instagram.direct.lock.hookHiddenChatsTouch
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Control the reel above comments (#665): the sheet's hooks, the touch hook and the filled stubs. */
class ReelAboveCommentsHookTest {
    private val manager = "Lfixture/WatchAndCommentViewManager;"
    private val controller = "Lfixture/ClipsVideoPlayerController;"
    private val player = "Lfixture/VideoPlayer;"
    private val purge = "Lfixture/Purge;->mark(Ljava/lang/String;)V"
    private val view = "Landroid/view/View;"
    private val hooks = listOf(SHEET_MOVED, SHEET_TAPPED, SHEET_TOUCH)
    private val stubs = listOf(
        "mediaView(Ljava/lang/Object;)Landroid/view/View;",
        "sheetOpen(Ljava/lang/Object;)I",
        "controllerOf(Ljava/lang/Object;)Ljava/lang/Object;",
        "playingNow(Ljava/lang/Object;)I",
        "pausePlayer(Ljava/lang/Object;)V",
        "resumePlayer(Ljava/lang/Object;)V",
        "setPlayerSpeed(Ljava/lang/Object;F)V",
    )

    private fun marker(name: String) = "android_purge_26_q3_$name"

    /** Every hook the patch writes is in the ReelAboveComments the bundle ships, public and static, and so is every stub. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_ABOVE_COMMENTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (signature in hooks.map { it.substringAfter("->") } + stubs) {
            assertTrue("$signature is not in the extension: $declared", signature in declared)
        }
    }

    /**
     * The sheet tells the extension it moved first thing; each answer of the tap callback goes through
     * the tap hook, with a branch onto a return landing on the hook; every touch reaches the touch hook
     * first thing in the activity; and the stubs read the manager's fields and call the controller with
     * the arguments Instagram's own tap uses.
     */
    @Test
    fun everyHookGoesInAndEveryStubIsFilled() {
        val context = PatchContexts.of(classes())

        context.reelAboveComments()

        val height = context.method(manager, "heightChanged")
        // Four locals, then this and the height.
        assertCall("the sheet-moved hook", height[0], SHEET_MOVED, listOf(4))

        val tap = context.method(manager, "tapConfirmed")
        val sites = tap.indices.filter { tap[it].referenceText() == SHEET_TAPPED }
        assertEquals("a tap hook in front of each return", 2, sites.size)
        for (site in sites) {
            // Three locals, then this and the event; the answer is v0.
            assertCall("the tap hook", tap[site], SHEET_TAPPED, listOf(0, 3, 4))
            assertEquals(Opcode.MOVE_RESULT, tap[site + 1].opcode)
            assertEquals(0, (tap[site + 1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN, tap[site + 2].opcode)
        }
        val branch = tap.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals("the branch onto the first return lands on its hook", sites[0], tap.target(branch))
        assertTrue("a branch lands on a return past its hook", tap.branchTargets().none { tap[it].opcode == Opcode.RETURN })

        val touch = context.method(FRAGMENT_ACTIVITY, "dispatchTouchEvent")
        assertCall("the touch hook", touch[0], SHEET_TOUCH, listOf(1, 2))

        val media = context.method(REEL_ABOVE_COMMENTS, "mediaView")
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), media.take(3).map { it.opcode })
        assertEquals("$manager->media:$view", media[1].referenceText())
        val open = context.method(REEL_ABOVE_COMMENTS, "sheetOpen")
        assertEquals(
            listOf("$manager->open:Z", "$manager->dismissing:Z"),
            open.filter { it.opcode == Opcode.IGET_BOOLEAN }.map { it.referenceText() },
        )
        val controllerOf = context.method(REEL_ABOVE_COMMENTS, "controllerOf")
        assertEquals("$manager->controller:$controller", controllerOf[1].referenceText())
        val playing = context.method(REEL_ABOVE_COMMENTS, "playingNow")
        assertEquals(
            listOf("$controller->current()$player", "$controller->playing($player)Z"),
            playing.mapNotNull { it.methodRef()?.text() },
        )
        val pause = context.method(REEL_ABOVE_COMMENTS, "pausePlayer")
        assertEquals(listOf(TAP_PAUSE_REASON), pause.mapNotNull { it.string() })
        assertEquals("$controller->pause(Ljava/lang/String;ZZ)V", pause.single { it.opcode == Opcode.INVOKE_VIRTUAL }.methodRef()?.text())
        val resume = context.method(REEL_ABOVE_COMMENTS, "resumePlayer")
        assertEquals(listOf(TAP_RESUME_REASON), resume.mapNotNull { it.string() })
        val resumeCall = resume.single { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        assertEquals("$controller->resume(Ljava/lang/String;JZZZ)V", resumeCall.methodRef()?.text())
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), resumeCall.registers())
        val speed = context.method(REEL_ABOVE_COMMENTS, "setPlayerSpeed")
        assertEquals("$controller->speed(Lkotlin/jvm/functions/Function1;F)V", speed.single { it.opcode == Opcode.INVOKE_VIRTUAL }.methodRef()?.text())
    }

    /**
     * Tap to play's TapClock stays first in the activity and the touch hook goes right after it. When
     * Hidden chats' double tap, a hook that can answer for the touch, is already in, the touch hook
     * goes ahead of it, and either way each hook goes in once.
     */
    @Test
    fun theTouchHookFollowsTapToPlay() {
        val watched = setOf(TAP_CLOCK, SHEET_TOUCH, HIDDEN_CHATS_TAP)
        val first = PatchContexts.of(classes(Shape(tapClock = true)))
        hookHiddenChatsTouch(first.findHiddenChatsTouch())
        first.reelAboveComments()
        val before = first.method(FRAGMENT_ACTIVITY, "dispatchTouchEvent").mapNotNull { it.referenceText() }.filter { it in watched }
        assertEquals(listOf(TAP_CLOCK, SHEET_TOUCH, HIDDEN_CHATS_TAP), before)

        val second = PatchContexts.of(classes(Shape(tapClock = true)))
        second.reelAboveComments()
        assertEquals(listOf(TAP_CLOCK, SHEET_TOUCH), second.method(FRAGMENT_ACTIVITY, "dispatchTouchEvent").take(2).map { it.referenceText() })
        hookHiddenChatsTouch(second.findHiddenChatsTouch())
        val after = second.method(FRAGMENT_ACTIVITY, "dispatchTouchEvent").mapNotNull { it.referenceText() }.filter { it in watched }
        assertEquals("TapClock stays first", TAP_CLOCK, after.first())
        assertEquals("each hook goes in once", watched, after.toSet())
        assertEquals(3, after.size)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape(heightChanged = 0) to "marked $SHEET_HEIGHT_CHANGED, found 0",
            Shape(heightChanged = 2) to "marked $SHEET_HEIGHT_CHANGED, found 2",
            Shape(managerPublic = false) to "the manager $manager isn't public",
            Shape(heightLoops = true) to "jumps back to its first instruction",
            Shape(tapElsewhere = true) to "isn't in the manager $manager",
            Shape(controllerPublic = false) to "the controller $controller isn't public",
            Shape(resumeTakesInt = true) to "isn't an instance (Ljava/lang/String;JZZZ)V method",
            Shape(pausePublic = false) to "isn't public, so the extension can't call it",
            Shape(controllerFields = 2) to "has 2 $controller fields, expected one",
            Shape(controllerFieldPublic = false) to "isn't public, so the extension can't read it",
            Shape(openedPauses = false) to "doesn't pause through",
            Shape(currentGetters = 2) to "makes 2 calls on the controller returning $player",
            Shape(mediaMarked = false) to "isn't marked $SHEET_MEDIA_VIEW",
            Shape(mediaElsewhere = true) to "somewhere other than a View field of the manager",
            Shape(mediaPublic = false) to "->media isn't a public instance field",
            Shape(openCleared = false) to "doesn't clear open",
            Shape(dismissSetsOpenFirst = true) to "sets the open flag open first",
            Shape(eventOverwritten = true) to "writes over parameter 0",
            Shape(thisOverwritten = true) to "writes over this",
            Shape(tapRegisters = 20) to "past v15",
            Shape(activity = false) to "this build has no $FRAGMENT_ACTIVITY",
        )
        for ((shape, expected) in cases) {
            val classes = classes(shape)
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.reelAboveComments() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Control the reel above comments: "))
            val written = classes.map { it.type }.distinct().filter { it != REEL_ABOVE_COMMENTS }
                .flatMap { type -> context.classDefByOrNull(type)?.methods?.toList().orEmpty() }
                .filter { method -> method.code().any { it.referenceText() in hooks } }
            assertTrue("$expected: something was written to ${written.map { it.name }}", written.isEmpty())
            val filled = context.classDefBy(REEL_ABOVE_COMMENTS).methods
                .filter { stub -> "${stub.name}(${stub.parameterTypes.joinToString("")})${stub.returnType}" in stubs }
                .filter { stub -> stub.code().any { it.opcode == Opcode.CHECK_CAST } }
            assertTrue("$expected: a stub was filled: ${filled.map { it.name }}", filled.isEmpty())
        }
    }

    /**
     * In each declared build every hook goes where the stand-ins put it: the sheet-moved hook first,
     * the tap hook in front of each return of the tap callback with no branch skipping it, and the
     * touch hook first in the activity; and the stubs reach the manager's fields and the controller.
     */
    @Test
    fun eachDeclaredBuildGetsEveryHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGetsEveryHookToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, name: String) {
        val markers = setOf(
            SHEET_HEIGHT_CHANGED, SHEET_TAP_CONFIRMED, SHEET_DISMISS_STARTED, SHEET_DISMISSED, SHEET_FULLY_OPENED,
            PLAYER_PAUSE, PLAYER_RESUME, PLAYER_SPEED,
        )
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { string ->
                string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in markers
            }
            if (!holds) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.markers().any { it in markers } }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val extra = FixtureDex.classes(bundle, setOf(FRAGMENT_ACTIVITY)).values
        val context = PatchContexts.of((holders + extra + ExtensionDex.classDef(REEL_ABOVE_COMMENTS)).distinctBy { it.type })
        val methods = holders.flatMap { it.methods }
        val height = methods.single { SHEET_HEIGHT_CHANGED in it.markers() }
        val tap = methods.single { SHEET_TAP_CONFIRMED in it.markers() }
        val touch = extra.single().methods.single {
            it.name == "dispatchTouchEvent" && it.returnType == "Z" &&
                it.parameterTypes.map(Any::toString) == listOf("Landroid/view/MotionEvent;") && it.implementation != null
        }

        context.reelAboveComments()

        val after = { method: Method -> context.method(method.definingClass, method.name, method.parameterTypes.map(Any::toString)) }
        val newHeight = after(height)
        assertEquals("$name: the sheet-moved hook", SHEET_MOVED, newHeight[0].referenceText())
        assertEquals("$name: onBottomSheetHeightChanged grew by the hook", height.code().counted() + 1, newHeight.counted())

        val returns = tap.code().count { it.opcode == Opcode.RETURN }
        val newTap = after(tap)
        assertEquals("$name: the tap callback grew by the hooks", tap.code().counted() + 2 * returns, newTap.counted())
        val sites = newTap.indices.filter { newTap[it].referenceText() == SHEET_TAPPED }
        assertEquals("$name: a tap hook in front of each return", returns, sites.size)
        for (site in sites) {
            val answer = newTap[site].registers().first()
            assertEquals("$name: the answer comes back", Opcode.MOVE_RESULT, newTap[site + 1].opcode)
            assertEquals("$name: into the answer's register", answer, (newTap[site + 1] as OneRegisterInstruction).registerA)
            assertEquals("$name: and is returned", answer, (newTap[site + 2] as OneRegisterInstruction).registerA)
            assertEquals("$name: the return follows", Opcode.RETURN, newTap[site + 2].opcode)
        }
        assertTrue("$name: a branch lands on a return past its hook", newTap.branchTargets().none { newTap[it].opcode == Opcode.RETURN })

        val newTouch = after(touch)
        assertEquals("$name: the touch hook", SHEET_TOUCH, newTouch[0].referenceText())

        val managerType = height.definingClass
        val controllerType = methods.single { PLAYER_SPEED in it.markers() }.definingClass
        assertEquals("$name: the reel view is the manager's", managerType, context.method(REEL_ABOVE_COMMENTS, "mediaView")[1].fieldRef()?.definingClass)
        val flags = context.method(REEL_ABOVE_COMMENTS, "sheetOpen").filter { it.opcode == Opcode.IGET_BOOLEAN }.mapNotNull { it.fieldRef() }
        assertEquals("$name: two flags of the manager", listOf(managerType, managerType), flags.map { it.definingClass })
        assertNotEquals("$name: the open and closing flags differ", flags[0].name, flags[1].name)
        assertEquals("$name: the controller field", controllerType, context.method(REEL_ABOVE_COMMENTS, "controllerOf")[1].fieldRef()?.type)
        for (stub in listOf("playingNow", "pausePlayer", "resumePlayer", "setPlayerSpeed")) {
            val calls = context.method(REEL_ABOVE_COMMENTS, stub).mapNotNull { it.methodRef() }
            assertTrue("$name: $stub calls the controller", calls.isNotEmpty() && calls.all { it.definingClass == controllerType })
        }
    }

    // ---- stand-ins shaped like Instagram 454's -------------------------------------------------

    /** What each stand-in looks like; the defaults are 454's shape, and each case changes one thing. */
    private data class Shape(
        val heightChanged: Int = 1,
        val managerPublic: Boolean = true,
        val heightLoops: Boolean = false,
        val tapElsewhere: Boolean = false,
        val controllerPublic: Boolean = true,
        val resumeTakesInt: Boolean = false,
        val pausePublic: Boolean = true,
        val controllerFields: Int = 1,
        val controllerFieldPublic: Boolean = true,
        val openedPauses: Boolean = true,
        val currentGetters: Int = 1,
        val mediaMarked: Boolean = true,
        val mediaElsewhere: Boolean = false,
        val mediaPublic: Boolean = true,
        val openCleared: Boolean = true,
        val dismissSetsOpenFirst: Boolean = false,
        val eventOverwritten: Boolean = false,
        val thisOverwritten: Boolean = false,
        val tapRegisters: Int = 5,
        val activity: Boolean = true,
        val tapClock: Boolean = false,
    )

    /**
     * The watch-and-comment manager with its sheet callbacks: the height change, which reads and sets
     * the open flag and stores the reel view it finds after its getClipsMediaContentView marker; the
     * tap callback, which keeps a tap on the audio button and answers false otherwise; the dismissal's
     * start, which sets the closing flag; the dismissal, which clears both; and the full open, which
     * pauses a playing reel through the controller. The Reels player controller with its speed setter,
     * pause, resume, current player and playing check. IgFragmentActivity's dispatchTouchEvent.
     */
    private fun classes(shape: Shape = Shape()): List<ClassDef> {
        val heights = (0 until shape.heightChanged).map { copy ->
            method(manager, if (copy == 0) "heightChanged" else "heightChangedToo", listOf("I"), "V", 6, """
                ${if (shape.heightLoops) ":top" else ""}
                const-string v0, "${marker(SHEET_HEIGHT_CHANGED)}"
                invoke-static { v0 }, $purge
                ${if (shape.heightLoops) "if-eqz p1, :top" else ""}
                iget-boolean v0, p0, $manager->open:Z
                if-nez v0, :done
                const/4 v1, 0x1
                iput-boolean v1, p0, $manager->open:Z
                const-string v0, "${marker(if (shape.mediaMarked) SHEET_MEDIA_VIEW else "WatchAndCommentViewManager_setupMediaContentView")}"
                invoke-static { v0 }, $purge
                iget-object v2, p0, $manager->root:$view
                const v0, 0x7f0b0bbd
                invoke-virtual { v2, v0 }, $view->findViewById(I)$view
                move-result-object v0
                iput-object v0, ${if (shape.mediaElsewhere) "v2, Lfixture/Other;->media" else "p0, $manager->media"}:$view
                :done
                return-void
            """)
        }

        // With this past v15 the callback only answers false, since a 4-bit form can't name it.
        val tapBody = if (shape.tapRegisters > 17) """
                const/4 v0, 0x0
                const-string v1, "${marker(SHEET_TAP_CONFIRMED)}"
                invoke-static { v1 }, $purge
                return v0
            """ else """
                const/4 v0, 0x0
                const-string v1, "${marker(SHEET_TAP_CONFIRMED)}"
                invoke-static { v1 }, $purge
                iget-object v1, p0, $manager->audio:$view
                ${if (shape.eventOverwritten) "const/4 p1, 0x0" else ""}
                ${if (shape.thisOverwritten) "const/4 p0, 0x0" else ""}
                if-eqz v1, :answer
                invoke-virtual { v1 }, $view->getVisibility()I
                move-result v2
                if-eqz v2, :audio
                :answer
                return v0
                :audio
                const/4 v0, 0x1
                return v0
            """
        val tap = method(if (shape.tapElsewhere) "Lfixture/Other;" else manager, "tapConfirmed", listOf("Landroid/view/MotionEvent;"), "Z",
            shape.tapRegisters, tapBody)

        val dismissStarted = method(manager, "dismissStarted", listOf("Lfixture/Event;"), "V", 3, """
            const-string v0, "${marker(SHEET_DISMISS_STARTED)}"
            invoke-static { v0 }, $purge
            const/4 v0, 0x1
            ${if (shape.dismissSetsOpenFirst) "iput-boolean v0, p0, $manager->open:Z" else ""}
            iput-boolean v0, p0, $manager->dismissing:Z
            return-void
        """)
        val dismissed = method(manager, "dismissed", emptyList(), "V", 2, """
            const-string v0, "${marker(SHEET_DISMISSED)}"
            invoke-static { v0 }, $purge
            const/4 v0, 0x0
            ${if (shape.openCleared) "iput-boolean v0, p0, $manager->open:Z" else ""}
            iput-boolean v0, p0, $manager->dismissing:Z
            return-void
        """)
        val getters = (0 until shape.currentGetters).joinToString("\n") { copy ->
            "invoke-virtual { v1 }, $controller->${if (copy == 0) "current" else "currentToo"}()$player\nmove-result-object v0"
        }
        val fullyOpened = method(manager, "fullyOpened", emptyList(), "V", 5, """
            const-string v0, "${marker(SHEET_FULLY_OPENED)}"
            invoke-static { v0 }, $purge
            iget-object v1, p0, $manager->controller:$controller
            $getters
            if-eqz v0, :done
            invoke-virtual { v1, v0 }, $controller->playing($player)Z
            move-result v0
            if-eqz v0, :done
            const-string v0, "bottom_sheet"
            const/4 v2, 0x1
            const/4 v3, 0x0
            ${if (shape.openedPauses) "invoke-virtual { v1, v0, v2, v3 }, $controller->pause(Ljava/lang/String;ZZ)V" else ""}
            :done
            return-void
        """)
        val managerFields = listOf(
            field(manager, "open", "Z"),
            field(manager, "dismissing", "Z"),
            field(manager, "media", view, shape.mediaPublic),
            field(manager, "root", view),
            field(manager, "audio", view),
        ) + (0 until shape.controllerFields).map { copy ->
            field(manager, if (copy == 0) "controller" else "controllerToo", controller, shape.controllerFieldPublic)
        }
        val managerClass = ImmutableClassDef(
            manager, (if (shape.managerPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, managerFields,
            heights + listOfNotNull(tap.takeIf { !shape.tapElsewhere }) + listOf(dismissStarted, dismissed, fullyOpened),
        )

        val speed = method(controller, "speed", listOf("Lkotlin/jvm/functions/Function1;", "F"), "V", 4, """
            const-string v0, "${marker(PLAYER_SPEED)}"
            invoke-static { v0 }, $purge
            return-void
        """)
        val pause = method(controller, "pause", listOf("Ljava/lang/String;", "Z", "Z"), "V", 5, """
            const-string v0, "${marker(PLAYER_PAUSE)}"
            invoke-static { v0 }, $purge
            return-void
        """, if (shape.pausePublic) AccessFlags.PUBLIC.value or AccessFlags.FINAL.value else AccessFlags.PRIVATE.value or AccessFlags.FINAL.value)
        val resume = method(controller, "resume", listOf("Ljava/lang/String;", if (shape.resumeTakesInt) "I" else "J", "Z", "Z", "Z"), "V",
            if (shape.resumeTakesInt) 7 else 8, """
            const-string v0, "${marker(PLAYER_RESUME)}"
            invoke-static { v0 }, $purge
            return-void
        """)
        val current = (0 until shape.currentGetters).map { copy ->
            method(controller, if (copy == 0) "current" else "currentToo", emptyList(), player, 2, """
                iget-object v0, p0, $controller->player:$player
                return-object v0
            """)
        }
        val playing = method(controller, "playing", listOf(player), "Z", 3, """
            const/4 v0, 0x1
            return v0
        """)
        val controllerClass = ImmutableClassDef(
            controller, (if (shape.controllerPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, listOf(field(controller, "player", player)), listOf(speed, pause, resume, playing) + current,
        )

        return listOfNotNull(
            managerClass,
            controllerClass,
            if (shape.tapElsewhere) classDef("Lfixture/Other;", listOf(tap)) else null,
            if (shape.activity) activity(shape.tapClock) else null,
            ExtensionDex.classDef(REEL_ABOVE_COMMENTS),
        )
    }

    /** IgFragmentActivity's own dispatchTouchEvent: one local, then the activity and the event, after Tap to play's call when [tapClock]. */
    private fun activity(tapClock: Boolean): ClassDef {
        val touch = method(FRAGMENT_ACTIVITY, "dispatchTouchEvent", listOf("Landroid/view/MotionEvent;"), "Z", 3, """
            ${if (tapClock) "invoke-static/range { p0 .. p1 }, $TAP_CLOCK" else ""}
            invoke-super { p0, p1 }, Landroidx/appcompat/app/AppCompatActivity;->dispatchTouchEvent(Landroid/view/MotionEvent;)Z
            move-result v0
            return v0
        """, AccessFlags.PUBLIC.value)
        return ImmutableClassDef(
            FRAGMENT_ACTIVITY, AccessFlags.PUBLIC.value, "Landroidx/appcompat/app/AppCompatActivity;", null, null, null, null, listOf(touch),
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String, public: Boolean = true) = ImmutableField(
        owner, name, type, if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value, null, null, null,
    )

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun BytecodePatchContext.method(owner: String, name: String, parameters: List<String>? = null): List<Instruction> =
        mutableClassDefBy(owner).methods.single {
            it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters)
        }.code()

    private fun assertCall(what: String, instruction: Instruction, hook: String, registers: List<Int>) {
        assertEquals("$what: the hook", hook, instruction.referenceText())
        assertEquals("$what: the registers", registers, instruction.registers())
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        var address = 0
        val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
        val landing = addresses[index] + (this[index] as OffsetInstruction).codeOffset
        return addresses.indexOf(landing)
    }

    /** The indices every branch and goto lands on. */
    private fun List<Instruction>.branchTargets(): Set<Int> = indices
        .filter { this[it] is OffsetInstruction && this[it].opcode !in setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA) }
        .map { target(it) }
        .toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** The instructions but the payload alignment nops, which shift when code goes in. */
    private fun List<Instruction>.counted(): Int = count { it.opcode != Opcode.NOP }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) "${it.definingClass}->${it.name}:${it.type}" else it.toString()
    }

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.fieldRef(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
}
