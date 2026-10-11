/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.zoom

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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

class LongPressZoomHookTest {
    private val media = "Lcom/instagram/feed/media/Media;"
    private val holder = "Lfixture/MediaTouch;"
    private val listener = "Lfixture/MediaGestures;"
    private val simple = "Landroid/view/GestureDetector\$SimpleOnGestureListener;"
    private val delegate = "Lfixture/Delegate;->longPress(Ljava/lang/Object;Ljava/lang/Object;)V"

    /** The press hook: read the holder, its frame and its Media, ask, test the answer, return. */
    private val pressHook = listOf(
        Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
        Opcode.RETURN_VOID,
    )

    /** The three hooks the patch writes or fills are in the LongPressZoom the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(LONG_PRESS_ZOOM).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(ZOOM_TOUCH, ZOOM_PRESS, "$LONG_PRESS_ZOOM->mediaType(Ljava/lang/Object;)I")) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The touch method hands its event over first. The long press method asks right after it notes the press time,
     * with the frame and the Media it reads from the holder, and returns on a yes; every other instruction keeps its
     * place, and the hook writes only the two locals it borrows. The media_type stub reads the Media's getter.
     */
    @Test
    fun theHooksGoInFirstAndAfterThePressTime() {
        val context = PatchContexts.of(classes())
        val touchBefore = context.method(holder, "onTouch").code().map { it.describe() }
        val pressBefore = context.method(listener, "onPress").code().map { it.describe() }

        val site = context.findLongPressZoomSite()
        assertEquals("the hook goes after the press time", 4, site.index)
        assertEquals("the locals nothing reads afterwards", listOf(1, 2), site.scratch)
        context.apply { applyLongPressZoom(site) }

        val touch = context.method(holder, "onTouch")
        val first = touch.code().first()
        assertEquals("the touch hook is first", ZOOM_TOUCH, first.referenceText())
        assertEquals("it hands over the event", listOf(touch.implementation!!.registerCount - 1), first.arguments())
        assertEquals("the touch method is otherwise untouched", touchBefore, touch.code().drop(1).map { it.describe() })

        val press = context.method(listener, "onPress")
        val code = press.code()
        assertEquals("the press hook", pressHook, code.subList(4, 4 + pressHook.size).map { it.opcode })
        assertEquals("the press time is written just before", Opcode.IPUT_WIDE, code[3].opcode)
        assertEquals("the hook asks", ZOOM_PRESS, code[7].referenceText())
        val p0 = press.implementation!!.registerCount - 3
        assertEquals("the holder is read from this", p0, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals("the frame", "$holder->frame:Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;", code[5].referenceText())
        assertEquals("the Media", "$holder->media:$media", code[6].referenceText())
        val written = code.subList(4, 4 + pressHook.size).mapNotNull { (it as? OneRegisterInstruction)?.registerA }.toSet()
        assertTrue("the hook wrote a register it didn't borrow: $written", written.all { it in site.scratch })
        assertEquals("the original instructions keep their order", pressBefore, code.take(4).map { it.describe() } + code.drop(4 + pressHook.size).map { it.describe() })
        val stock = press.implementation!!.instructions.toList()[9] as BuilderOffsetInstruction
        assertEquals("a no goes on with the frame read", 4 + pressHook.size, stock.target.location.index)

        val stub = context.mutableClassDefBy(LONG_PRESS_ZOOM).methods.single { it.name == "mediaType" }
        assertEquals("the stub reads media_type", "$media->mediaType()Ljava/lang/Integer;", stub.code()[1].referenceText())
        assertEquals("and answers its int", "Ljava/lang/Integer;->intValue()I", stub.code()[6].referenceText())
    }

    @Test
    fun withoutOneHolderThePatchFails() {
        for ((shape, found) in listOf(Shape.NO_LISTENER to "found 0", Shape.NO_DETECTOR to "found 0", Shape.TWO_HOLDERS to "found 2")) {
            val context = PatchContexts.of(classes(shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findLongPressZoomSite() }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(found))
        }
    }

    @Test
    fun aLongPressOfAnotherShapeFails() {
        val cases = listOf(
            Shape.TWO_PRESSES to "found 2",
            Shape.NO_CLOCK to "found 0",
            Shape.TIME_ELSEWHERE to "not $holder",
            Shape.NO_MEDIA_READ to "once, found 0",
            Shape.JUMP_PAST_TIME to "jumps past its press time",
            Shape.THIS_OVERWRITTEN to "writes over this",
            Shape.TOUCH_LOOPS to "jumps back to its start",
            Shape.NO_MEDIA_TYPE to "media_type",
        )
        for ((shape, message) in cases) {
            val context = PatchContexts.of(classes(shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findLongPressZoomSite() }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(message))
            assertTrue("$shape: something was written", context.method(listener, "onPress").code().none { it.referenceText() == ZOOM_PRESS })
        }
    }

    /**
     * In each of the version's seven builds the media touch holder is found once among the classes keeping a media
     * frame and a Media, its touch method and its listener's long press are found, and the hooks go in once each: the
     * touch hook first, the press hook right after the press time with two borrowed locals up to v15, and every other
     * instruction of the two classes in place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsLongPress() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("seven builds of the version", 7, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val keeping = mutableMapOf<String, List<String>>()
            FixtureDex.forEach(apk) { dex ->
                for (c in dex.classes) {
                    val types = c.instanceFields.map { it.type }
                    if (MEDIA_FRAME in types && media in types) keeping[c.type] = types
                }
            }
            assertTrue("$name: classes keeping a media frame and a Media", keeping.isNotEmpty())
            val wanted = keeping.flatMap { (type, fields) -> fields.filter { it.startsWith("LX/") } + type }.toSet() + media
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values + ExtensionDex.classDef(LONG_PRESS_ZOOM)
                val context = PatchContexts.of(classes)

                val site = context.findLongPressZoomSite()
                val owners = setOf(site.touch.definingClass, site.press.definingClass)
                assertTrue("$what: the touch holder keeps a media frame", site.touch.definingClass in keeping)
                assertEquals("$what: the listener keeps the holder", site.touch.definingClass, site.holderField.type)
                assertTrue("$what: the borrowed locals fit an invoke", site.scratch.all { it in 0..15 })
                val originals = owners.flatMap { context.mutableClassDefBy(it).methods }.filter { it.implementation != null }
                    .associateWith(::NeutralNativePath)
                context.apply { applyLongPressZoom(site) }

                val touching = context.callers(classes, ZOOM_TOUCH)
                assertEquals("$what: methods handing over touches", listOf(site.touch.definingClass to site.touch.name), touching.map { it.definingClass to it.name })
                val touch = touching.single()
                assertEquals("$what: the touch hook is first, once", listOf(0), touch.code().indices.filter { touch.code()[it].referenceText() == ZOOM_TOUCH })
                assertEquals("$what: it hands over the event", listOf(touch.implementation!!.registerCount - 1), touch.code()[0].arguments())

                val asking = context.callers(classes, ZOOM_PRESS)
                assertEquals("$what: methods asking", listOf(site.press.definingClass to site.press.name), asking.map { it.definingClass to it.name })
                val press = asking.single()
                val code = press.code()
                val ask = code.indices.single { code[it].referenceText() == ZOOM_PRESS }
                val start = ask - 3
                assertEquals("$what: the hook goes where the site says", site.index, start)
                assertEquals("$what: the press hook", pressHook, code.subList(start, start + pressHook.size).map { it.opcode })
                assertEquals("$what: right after the press time", Opcode.IPUT_WIDE, code[start - 1].opcode)
                val p0 = press.implementation!!.registerCount - 3
                assertEquals("$what: the holder is read from this", p0, (code[start] as TwoRegisterInstruction).registerB)
                assertEquals("$what: it reads the frame", site.frameField.text(), code[start + 1].referenceText())
                assertEquals("$what: and the Media", site.mediaField.text(), code[start + 2].referenceText())
                val written = code.subList(start, start + pressHook.size).mapNotNull { (it as? OneRegisterInstruction)?.registerA }
                assertTrue("$what: the hook wrote a register it didn't borrow: $written", written.all { it in site.scratch })
                assertEquals("$what: the stock read follows", Opcode.IGET_OBJECT, code[start + pressHook.size].opcode)

                for ((method, original) in originals) {
                    val added = when {
                        method.definingClass == touch.definingClass && method.name == touch.name -> setOf(0)
                        method.definingClass == press.definingClass && method.name == press.name -> (start until start + pressHook.size).toSet()
                        else -> emptySet()
                    }
                    original.assertPreserved("$what ${method.definingClass}->${method.name}", method, added)
                }
                val stub = context.mutableClassDefBy(LONG_PRESS_ZOOM).methods.single { it.name == "mediaType" }
                assertEquals("$what: the stub reads media_type", "$media->${site.mediaType.name}()Ljava/lang/Integer;", stub.code()[1].referenceText())
            }
        }
    }

    private enum class Shape {
        PLAIN, NO_LISTENER, NO_DETECTOR, TWO_HOLDERS, TWO_PRESSES, NO_CLOCK, TIME_ELSEWHERE, NO_MEDIA_READ, JUMP_PAST_TIME,
        THIS_OVERWRITTEN, TOUCH_LOOPS, NO_MEDIA_TYPE,
    }

    /**
     * A small app shaped like 450's: a touch holder keeping a media frame, a Media, the press time and a gesture listener,
     * making a GestureDetector, with one touch method; a listener keeping the holder, whose long press reads the clock,
     * writes the press time, reads the frame and the Media and hands them on; and a Media with a media_type getter.
     */
    private fun classes(shape: Shape = Shape.PLAIN): List<ClassDef> {
        val frame = "Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;"
        fun holderClass(type: String, listenerType: String): ClassDef {
            val fields = mutableListOf(
                ImmutableField(type, "frame", frame, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
                ImmutableField(type, "media", media, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
                ImmutableField(type, "time", "J", AccessFlags.PUBLIC.value, null, null, null),
            )
            if (shape != Shape.NO_LISTENER) {
                fields += ImmutableField(type, "gestures", listenerType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
            }
            val detector = if (shape == Shape.NO_DETECTOR) "Landroid/view/View;-><init>(Landroid/content/Context;)V" else
                "Landroid/view/GestureDetector;-><init>(Landroid/content/Context;Landroid/view/GestureDetector\$OnGestureListener;)V"
            val newType = detector.substringBefore("->")
            val arguments = if (shape == Shape.NO_DETECTOR) "v0, p1" else "v0, p1, p0"
            val make = method(type, "<init>", listOf("Landroid/content/Context;"), "V", 1, body = """
                new-instance v0, $newType
                invoke-direct { $arguments }, $detector
                return-void
            """, flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)
            val top = if (shape == Shape.TOUCH_LOOPS) ":top" else ""
            val loop = if (shape == Shape.TOUCH_LOOPS) "if-eqz v0, :top" else ""
            val touch = method(type, "onTouch", listOf("Landroid/view/MotionEvent;"), "Z", 1, body = """
                $top
                const/4 v0, 0x1
                $loop
                invoke-virtual { p1 }, Landroid/view/MotionEvent;->getActionMasked()I
                return v0
            """)
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, listOf(make, touch))
        }
        val timeOwner = if (shape == Shape.TIME_ELSEWHERE) "Lfixture/Elsewhere;" else holder
        val clock = if (shape == Shape.NO_CLOCK) "const-wide/16 v1, 0x0" else "invoke-static { }, Landroid/os/SystemClock;->uptimeMillis()J\nmove-result-wide v1"
        val thisBefore = if (shape == Shape.THIS_OVERWRITTEN) "const/4 p0, 0x0" else ""
        val jump = if (shape == Shape.JUMP_PAST_TIME) "if-eqz v0, :frame" else ""
        val mediaRead = if (shape == Shape.NO_MEDIA_READ) "const/4 v2, 0x0" else "iget-object v2, v0, $holder->media:$media"
        val timeWrite = if (shape == Shape.TIME_ELSEWHERE) "iput-wide v1, v0, $timeOwner->time:J" else "iput-wide v1, v0, $holder->time:J"
        val pressBody = """
            $thisBefore
            iget-object v0, p0, $listener->holder:$holder
            $clock
            $timeWrite
            :frame
            iget-object v1, v0, $holder->frame:$frame
            $mediaRead
            $jump
            invoke-static { v1, v2 }, $delegate
            return-void
        """
        val presses = mutableListOf(method(listener, "onPress", listOf("F", "F"), "V", 3, body = pressBody))
        if (shape == Shape.TWO_PRESSES) presses += method(listener, "onOtherPress", listOf("F", "F"), "V", 3, body = pressBody)
        // A long press of the same shape that never reads the clock: not the one.
        presses += method(listener, "onScroll", listOf("F", "F"), "V", 0, body = "return-void")
        val listenerClass = ImmutableClassDef(
            listener, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, simple, null, null, null,
            listOf(ImmutableField(listener, "holder", holder, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
            presses,
        )
        val getter = if (shape == Shape.NO_MEDIA_TYPE) "other_field" else "media_type"
        val mediaClass = ImmutableClassDef(
            media, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(method(media, "mediaType", emptyList(), "Ljava/lang/Integer;", 1, body = """
                const v0, ${getter.hashCode()}
                const/4 v0, 0x0
                return-object v0
            """)),
        )
        val found = listOf(holderClass(holder, listener), listenerClass, mediaClass, ExtensionDex.classDef(LONG_PRESS_ZOOM))
        return if (shape == Shape.TWO_HOLDERS) found + holderClass("Lfixture/OtherTouch;", listener) else found
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

    /** An instruction as text: its opcode, registers and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        referenceText()?.let { append(" $it") }
    }
}
