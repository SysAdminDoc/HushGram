/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.zoom.LONG_PRESS_ZOOM
import app.morphe.patches.instagram.feed.zoom.ZOOM_TOUCH
import app.morphe.patches.instagram.feed.zoom.applyLongPressZoom
import app.morphe.patches.instagram.feed.zoom.findLongPressZoomSite
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hold to download's two calls and four reads: found by structure on stand-ins shaped like 450 and on
 * every one of the version's three builds, each call first in its touch method (after Long press to
 * zoom's, in either order of the two patches), every other instruction in place, and a build that
 * differs stopping the patch before anything changes.
 */
class HoldToDownloadHookTest {
    private val frame = "Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;"
    private val holder = "Lfixture/MediaTouch;"
    private val feedState = "Lfixture/FeedState;"
    private val detector = "Lfixture/ClipsGestures;"
    private val item = "Lfixture/ClipsItem;"
    private val marker = "android_purge_26_q3_$REEL_TOUCH_MARKER"

    /** The hooks are in the HoldDownload the bundle ships, and the four reads in HoldTargets, public and static. */
    @Test
    fun theHooksAndReadsAreInTheExtension() {
        fun declared(type: String) = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HOLD_FEED_TOUCH, HOLD_FEED_MADE, HOLD_REEL_TOUCH)) {
            assertTrue("$hook is not in the extension", hook.substringAfter("->") in declared(HOLD_DOWNLOAD))
        }
        for (read in listOf("feedMedia", "feedState", "feedFrame", "reelMedia")) {
            assertTrue("$read is not in $HOLD_TARGETS", "$read(Ljava/lang/Object;)Ljava/lang/Object;" in declared(HOLD_TARGETS))
        }
    }

    /**
     * The feed call is first in the touch holder's touch method with the holder and the event, the reel
     * call first in the detector's marked method with the detector, the view and the event, every other
     * instruction keeps its place, and each read checks its owner's type and reads its field.
     */
    @Test
    fun theCallsGoFirstAndTheReadsAreWritten() {
        val context = PatchContexts.of(classes())
        val feedBefore = context.method(holder, "onTouch").code().map { it.describe() }
        val reelBefore = context.method(detector, "onTouch").code().map { it.describe() }
        val makeBefore = context.method(holder, "<init>").code().map { it.describe() }

        val sites = context.findHoldSites(feedState)
        assertEquals("$holder-><init>", "${sites.feedMake.definingClass}->${sites.feedMake.name}")
        assertEquals("$holder->media:$MEDIA", sites.feedMedia.text())
        assertEquals("$holder->state:$feedState", sites.feedState.text())
        assertEquals("$holder->frame:$frame", sites.feedFrame.text())
        assertEquals("$detector->item:$item", sites.reelItem.text())
        assertEquals("$item->media:$MEDIA", sites.reelMedia.text())
        context.apply { addHoldToDownload(sites) }

        val feed = context.method(holder, "onTouch")
        assertEquals(HOLD_FEED_TOUCH, feed.code()[0].referenceText())
        val feedRegisters = feed.implementation!!.registerCount
        assertEquals("it hands over the holder and the event", listOf(feedRegisters - 2, feedRegisters - 1), feed.code()[0].arguments())
        assertEquals(feedBefore, feed.code().drop(1).map { it.describe() })

        val reel = context.method(detector, "onTouch")
        assertEquals(HOLD_REEL_TOUCH, reel.code()[0].referenceText())
        val reelRegisters = reel.implementation!!.registerCount
        assertEquals("it hands over the detector, the view and the event", (reelRegisters - 3 until reelRegisters).toList(), reel.code()[0].arguments())
        assertEquals(reelBefore, reel.code().drop(1).map { it.describe() })

        // The constructor keeps the frame: the holder goes to the hook right before the return, and nothing else changed.
        val make = context.method(holder, "<init>")
        val made = make.code().indices.filter { make.code()[it].referenceText() == HOLD_FEED_MADE }
        assertEquals("once, right before the return", listOf(make.code().size - 2), made)
        assertEquals(Opcode.RETURN_VOID, make.code().last().opcode)
        val self = make.implementation!!.registerCount - 2
        assertEquals("it hands over the holder itself", listOf(self), make.code()[made.single()].arguments())
        assertEquals(makeBefore, make.code().filterIndexed { at, _ -> at !in made }.map { it.describe() })

        assertReads(context, sites)
    }

    /** A constructor with two returns gets the call before each of them. */
    @Test
    fun everyReturnOfTheConstructorKeepsTheFrame() {
        val context = PatchContexts.of(classes())
        val sites = context.findHoldSites(feedState)
        val make = context.method(holder, "<init>")
        make.addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :late
                return-void
                :late
                nop
            """,
        )
        val returnsBefore = make.code().count { it.opcode == Opcode.RETURN_VOID }
        assertEquals(2, returnsBefore)
        context.apply { addHoldToDownload(sites) }
        val code = make.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertEquals(2, returns.size)
        for (at in returns) assertEquals("before the return at $at", HOLD_FEED_MADE, code[at - 1].referenceText())
        assertEquals(2, code.count { it.referenceText() == HOLD_FEED_MADE })
    }

    /** Long press to zoom's call stays first whichever of the two patches goes first, and this one comes right after it. */
    @Test
    fun withLongPressToZoomEitherOrderLeavesTheZoomFirst() {
        for (zoomFirst in listOf(true, false)) {
            val context = PatchContexts.of(classes())
            val before = context.method(holder, "onTouch").code().map { it.describe() }
            val sites = context.findHoldSites(feedState)
            if (zoomFirst) context.method(holder, "onTouch").addInstructions(0, "invoke-static/range { p1 .. p1 }, $ZOOM_TOUCH")
            context.apply { addHoldToDownload(sites) }
            if (!zoomFirst) context.method(holder, "onTouch").addInstructions(0, "invoke-static/range { p1 .. p1 }, $ZOOM_TOUCH")
            val code = context.method(holder, "onTouch").code()
            assertEquals("zoom first: $zoomFirst", listOf(ZOOM_TOUCH, HOLD_FEED_TOUCH), code.take(2).map { it.referenceText() })
            assertEquals(before, code.drop(2).map { it.describe() })
        }
    }

    @Test
    fun aBuildThatDiffersFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape.NO_HOLDER to "found 0",
            Shape.TWO_HOLDERS to "found 2",
            Shape.NO_STATE to "the feed state",
            Shape.PRIVATE_MEDIA to "isn't public",
            Shape.PRIVATE_HOLDER to "isn't public",
            Shape.FEED_LOOPS to "jumps back to its start",
            Shape.NO_MARKER to "marker, found 0",
            Shape.TWO_MARKED to "marker, found 2",
            Shape.REEL_SHAPE to "doesn't take a View and a MotionEvent",
            Shape.NO_ITEM to "reel item holding a Media, found 0",
            Shape.PRIVATE_ITEM to "isn't public",
            Shape.TWO_MAKES to "to have one constructor, found 2",
            Shape.MAKE_JUMPS_TO_RETURN to "constructor jumps to a return",
            Shape.MAKE_WRITES_THIS to "writes the register holding this",
        )
        for ((shape, message) in cases) {
            val context = PatchContexts.of(classes(shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findHoldSites(feedState) }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(message))
            val touches = listOf(holder, detector).mapNotNull { type -> context.classDefByOrNull(type) }
                .flatMap { context.mutableClassDefBy(it.type).methods }
            assertTrue("$shape: something was written", touches.none { method ->
                method.code().any { it.referenceText() in listOf(HOLD_FEED_TOUCH, HOLD_FEED_MADE, HOLD_REEL_TOUCH) }
            })
        }
    }

    /**
     * In each of the version's three builds the feed's touch holder and the reel detector are found once,
     * the holder keeps exactly one field of the type the carousel page is read from, both calls go in first
     * once each (the feed one right after Long press to zoom's, in either order), every other instruction of
     * the two classes keeps its place, and the reads name the fields found. Read as the patcher reads an
     * APK, and copied.
     */
    @Test
    fun eachDeclaredBuildTakesBothCalls() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every 452 build", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val keeping = mutableMapOf<String, List<String>>()
            val detecting = mutableSetOf<String>()
            val reels = mutableMapOf<String, List<String>>()
            val pageTypes = mutableSetOf<String>()
            FixtureDex.forEach(apk) { dex ->
                for (c in dex.classes) {
                    val types = c.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }
                    if (frame !in types || MEDIA !in types) continue
                    keeping[c.type] = types
                    if (c.methods.any { m -> m.code().any { it.referenceText()?.startsWith("Landroid/view/GestureDetector;-><init>") == true } }) {
                        detecting += c.type
                    }
                }
                if (dex.stringSection.any { PURGE_MARKER.find(it)?.groupValues?.get(1) == REEL_TOUCH_MARKER }) {
                    for (c in dex.classes) {
                        if (c.methods.any { REEL_TOUCH_MARKER in it.markers() }) {
                            reels[c.type] = c.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }
                        }
                    }
                }
                if (dex.methodSection.any { it.definingClass == MEDIA_EXT }) {
                    for (c in dex.classes) for (m in c.methods) {
                        m.pageReads().mapNotNullTo(pageTypes) { read -> read.field?.takeIf { it.type == "I" }?.definingClass }
                    }
                }
            }
            assertEquals("$name: classes holding the reel detector's marker", 1, reels.size)
            assertEquals("$name: classes keeping a media frame and a Media that make a GestureDetector", 1, detecting.size)
            val states = keeping.getValue(detecting.single()).filter { it in pageTypes }.toSet()
            assertEquals("$name: the touch holder's feed state, the type a carousel page is read from", 1, states.size)
            val state = states.single()
            val itemTypes = reels.values.flatten().filter { it.startsWith("L") }
            val wanted = keeping.flatMap { (type, fields) -> fields.filter { it.startsWith("LX/") } + type }.toSet() +
                reels.keys + itemTypes + MEDIA

            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val zoomFirst = read == "as read"
                val classes = classesOf(apk, wanted).values + ExtensionDex.classDef(HOLD_TARGETS) + ExtensionDex.classDef(LONG_PRESS_ZOOM)
                val context = PatchContexts.of(classes)

                val sites = context.findHoldSites(state)
                assertTrue("$what: the touch holder keeps a media frame", sites.feedTouch.definingClass in keeping)
                assertEquals("$what: the feed state", state, sites.feedState.type)
                assertEquals("$what: the reel's post", MEDIA, sites.reelMedia.type)
                val owners = setOf(sites.feedTouch.definingClass, sites.reelTouch.definingClass)
                val originals = owners.flatMap { context.mutableClassDefBy(it).methods }.filter { it.implementation != null }
                    .associateWith(::NeutralNativePath)
                if (zoomFirst) context.apply { applyLongPressZoom(findLongPressZoomSite()) }
                context.apply { addHoldToDownload(sites) }
                if (!zoomFirst) context.apply { applyLongPressZoom(findLongPressZoomSite()) }

                val feeds = context.callers(classes, HOLD_FEED_TOUCH)
                assertEquals("$what: methods handing touches over", listOf(sites.feedTouch.definingClass to sites.feedTouch.name), feeds.map { it.definingClass to it.name })
                val feed = feeds.single()
                assertEquals("$what: the zoom's call, then this one, once", listOf(ZOOM_TOUCH, HOLD_FEED_TOUCH), feed.code().take(2).map { it.referenceText() })
                assertEquals("$what: once", 1, feed.code().count { it.referenceText() == HOLD_FEED_TOUCH })
                val feedRegisters = feed.implementation!!.registerCount
                assertEquals("$what: the holder and the event", listOf(feedRegisters - 2, feedRegisters - 1), feed.code()[1].arguments())

                val reelCallers = context.callers(classes, HOLD_REEL_TOUCH)
                assertEquals("$what: methods handing reel touches over", listOf(sites.reelTouch.definingClass to sites.reelTouch.name), reelCallers.map { it.definingClass to it.name })
                val reel = reelCallers.single()
                assertEquals("$what: the reel call is first, once", listOf(0), reel.code().indices.filter { reel.code()[it].referenceText() == HOLD_REEL_TOUCH })
                val reelRegisters = reel.implementation!!.registerCount
                assertEquals("$what: the detector, the view and the event", (reelRegisters - 3 until reelRegisters).toList(), reel.code()[0].arguments())

                val makers = context.callers(classes, HOLD_FEED_MADE)
                assertEquals("$what: methods keeping a frame", listOf(sites.feedMake.definingClass to "<init>"), makers.map { it.definingClass to it.name })
                val make = makers.single()
                val makeCode = make.code()
                val madeAt = makeCode.indices.filter { makeCode[it].referenceText() == HOLD_FEED_MADE }
                val returnsAt = makeCode.indices.filter { makeCode[it].opcode == Opcode.RETURN_VOID }
                assertTrue("$what: a constructor that returns", returnsAt.isNotEmpty())
                assertEquals("$what: a call right before every return, once each", returnsAt.map { it - 1 }, madeAt)
                val self = make.implementation!!.registerCount - 1 - make.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                for (at in madeAt) assertEquals("$what: the holder itself", listOf(self), makeCode[at].arguments())

                for ((method, original) in originals) {
                    val added = when {
                        method.definingClass == make.definingClass && method.name == make.name -> madeAt.toSet()
                        method.definingClass == feed.definingClass && method.name == feed.name -> setOf(0, 1)
                        method.definingClass == reel.definingClass && method.name == reel.name -> setOf(0)
                        else -> emptySet()
                    }
                    original.assertPreserved("$what ${method.definingClass}->${method.name}", method, added)
                }
                assertReads(context, sites)
            }
        }
    }

    /** Each read checks its owner's type and reads the field found, the reel's through its item. */
    private fun assertReads(context: BytecodePatchContext, sites: HoldSites) {
        val reads = context.mutableClassDefBy(HOLD_TARGETS).methods.associateBy { it.name }
        for ((name, field) in listOf("feedMedia" to sites.feedMedia, "feedState" to sites.feedState, "feedFrame" to sites.feedFrame)) {
            val code = reads.getValue(name).code()
            assertEquals(name, listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), code.take(3).map { it.opcode })
            assertEquals(name, sites.feedTouch.definingClass, code[0].referenceText())
            assertEquals(name, field.text(), code[1].referenceText())
        }
        val reel = reads.getValue("reelMedia").code()
        assertEquals(sites.reelTouch.definingClass, reel[0].referenceText())
        assertEquals(sites.reelItem.text(), reel[1].referenceText())
        assertEquals(Opcode.IF_EQZ, reel[2].opcode)
        assertEquals(sites.reelMedia.text(), reel[3].referenceText())
    }

    private enum class Shape {
        PLAIN, NO_HOLDER, TWO_HOLDERS, NO_STATE, PRIVATE_MEDIA, PRIVATE_HOLDER, FEED_LOOPS, NO_MARKER, TWO_MARKED, REEL_SHAPE,
        NO_ITEM, PRIVATE_ITEM, TWO_MAKES, MAKE_JUMPS_TO_RETURN, MAKE_WRITES_THIS,
    }

    /**
     * A small app shaped like 450's: a touch holder keeping a media frame, a Media and the feed state,
     * making a GestureDetector, with one touch method; a reel gesture detector whose touch method loads
     * the marker, keeping a reel item that keeps a Media.
     */
    private fun classes(shape: Shape = Shape.PLAIN): List<ClassDef> {
        val open = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        fun field(owner: String, name: String, type: String, flags: Int = open) = ImmutableField(owner, name, type, flags, null, null, null)
        fun holderClass(type: String): ClassDef {
            val fields = mutableListOf(
                field(type, "frame", frame),
                field(type, "media", MEDIA, if (shape == Shape.PRIVATE_MEDIA) AccessFlags.PRIVATE.value else open),
                field(type, "detector", "Landroid/view/GestureDetector;"),
            )
            if (shape != Shape.NO_STATE) fields += field(type, "state", feedState)
            val jump = if (shape == Shape.MAKE_JUMPS_TO_RETURN) "if-eqz p1, :done" else ""
            val done = if (shape == Shape.MAKE_JUMPS_TO_RETURN) ":done" else ""
            val clobber = if (shape == Shape.MAKE_WRITES_THIS) "move-object p0, v0" else ""
            val make = method(type, "<init>", listOf("Landroid/content/Context;"), "V", 1, body = """
                new-instance v0, Landroid/view/GestureDetector;
                invoke-direct { v0, p1, p0 }, Landroid/view/GestureDetector;-><init>(Landroid/content/Context;Landroid/view/GestureDetector${'$'}OnGestureListener;)V
                $jump
                $clobber
                $done
                return-void
            """, flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)
            val another = method(type, "<init>", listOf("Landroid/content/Context;", "I"), "V", 1, body = """
                return-void
            """, flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)
            val top = if (shape == Shape.FEED_LOOPS) ":top" else ""
            val loop = if (shape == Shape.FEED_LOOPS) "if-eqz v0, :top" else ""
            val touch = method(type, "onTouch", listOf("Landroid/view/MotionEvent;"), "Z", 1, body = """
                $top
                const/4 v0, 0x1
                $loop
                invoke-virtual { p1 }, Landroid/view/MotionEvent;->getActionMasked()I
                return v0
            """)
            val flags = if (shape == Shape.PRIVATE_HOLDER) AccessFlags.FINAL.value else open
            val methods = if (shape == Shape.TWO_MAKES) listOf(make, another, touch) else listOf(make, touch)
            return ImmutableClassDef(type, flags, "Ljava/lang/Object;", null, null, null, fields, methods)
        }
        fun detectorClass(type: String): ClassDef {
            val loads = if (shape == Shape.NO_MARKER) "android_purge_26_q3_ClipsItemGestureDetector_onScroll" else marker
            val parameters = if (shape == Shape.REEL_SHAPE) listOf("Landroid/view/MotionEvent;") else listOf("Landroid/view/View;", "Landroid/view/MotionEvent;")
            val event = if (shape == Shape.REEL_SHAPE) "p1" else "p2"
            val touch = method(type, "onTouch", parameters, "Z", 1, body = """
                const-string v0, "$loads"
                invoke-static { v0 }, Lfixture/Trace;->mark(Ljava/lang/String;)V
                invoke-virtual { $event }, Landroid/view/MotionEvent;->getActionMasked()I
                const/4 v0, 0x1
                return v0
            """)
            val itemType = if (shape == Shape.NO_ITEM) "Lfixture/Other;" else item
            return ImmutableClassDef(type, open, "Landroid/view/GestureDetector${'$'}SimpleOnGestureListener;", null, null, null,
                listOf(field(type, "item", itemType), field(type, "view", "Landroid/view/View;")), listOf(touch))
        }
        val itemFlags = if (shape == Shape.PRIVATE_ITEM) AccessFlags.FINAL.value else open
        val found = mutableListOf(
            ImmutableClassDef(feedState, open, "Ljava/lang/Object;", null, null, null, listOf(field(feedState, "page", "I")), null),
            ImmutableClassDef(item, itemFlags, "Ljava/lang/Object;", null, null, null, listOf(field(item, "media", MEDIA), field(item, "id", "Ljava/lang/String;")), null),
            ImmutableClassDef("Lfixture/Other;", open, "Ljava/lang/Object;", null, null, null, listOf(field("Lfixture/Other;", "id", "Ljava/lang/String;")), null),
            ImmutableClassDef(MEDIA, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null),
            detectorClass(detector),
            ExtensionDex.classDef(HOLD_TARGETS),
        )
        if (shape != Shape.NO_HOLDER) found += holderClass(holder)
        if (shape == Shape.TWO_HOLDERS) found += holderClass("Lfixture/OtherTouch;")
        if (shape == Shape.TWO_MARKED) found += detectorClass("Lfixture/OtherGestures;")
        return found
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value,
    ): Method {
        val total = registers + 1 + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun BytecodePatchContext.method(owner: String, name: String): MutableMethod =
        mutableClassDefBy(owner).methods.single { it.name == name }

    private fun BytecodePatchContext.callers(classes: Collection<ClassDef>, hook: String): List<MutableMethod> =
        classes.filter { !it.type.startsWith("Lapp/hushgram/") }.flatMap { c ->
            mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == hook } }
        }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) it.text() else it.toString()
    }

    private fun FieldReference.text(): String = "$definingClass->$name:$type"

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, its arguments and its reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        referenceText()?.let { append(" $it") }
    }
}
