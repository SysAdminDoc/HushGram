/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.seekbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.feed.zoom.MEDIA_FRAME
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

/** Double tap to skip on feed videos (#562): the hook first thing in the feed post gesture listener's double tap. */
class FeedDoubleTapHookTest {
    private val media = "Lcom/instagram/feed/media/Media;"
    private val holder = "Lfixture/MediaTouch;"
    private val listener = "Lfixture/MediaGestures;"
    private val simple = "Landroid/view/GestureDetector\$SimpleOnGestureListener;"
    private val like = "Lfixture/Delegate;->like(Ljava/lang/Object;Ljava/lang/Object;)V"

    /** Read the holder and its frame, ask with the frame and the tap, and answer true on a yes. */
    private val hook = listOf(
        Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16,
        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN,
    )

    /** The call the patch writes is in the FeedDoubleTapSkip the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_DOUBLE_TAP_SKIP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_DOUBLE_TAP_HOOK is not in the extension: $declared", FEED_DOUBLE_TAP_HOOK.substringAfter("->") in declared)
    }

    /**
     * The hook goes in first, reads the holder from this and the frame from the holder, hands over the frame and the
     * tap, and answers true on a yes; a no and a missing holder both go on with the stock code, which keeps its order.
     */
    @Test
    fun theHookGoesInFirst() {
        val context = PatchContexts.of(classes())
        val before = context.method(listener, "onDoubleTap").code().map { it.describe() }
        val site = context.findFeedDoubleTap()
        assertEquals("$listener->holder:$holder", site.holderField.text())
        assertEquals("$holder->frame:$MEDIA_FRAME", site.frameField.text())
        context.hookFeedDoubleTap(site)

        val tap = context.method(listener, "onDoubleTap")
        val code = tap.code()
        assertEquals("the hook", hook, code.take(hook.size).map { it.opcode })
        val registers = tap.implementation!!.registerCount
        assertEquals("this", registers - 2, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("the holder", site.holderField.text(), code[1].referenceText())
        assertEquals("the frame", site.frameField.text(), code[3].referenceText())
        assertEquals("the tap", registers - 1, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals("the ask", FEED_DOUBLE_TAP_HOOK, code[5].referenceText())
        val call = code[5] as FiveRegisterInstruction
        assertEquals("it hands over the frame and the tap", listOf(0, 1), listOf(call.registerC, call.registerD))
        val written = code.take(hook.size).mapNotNull { (it as? OneRegisterInstruction)?.registerA }.toSet()
        assertEquals("the hook writes only the two locals it borrows", setOf(0, 1), written)
        val stock = tap.implementation!!.instructions.toList()
        for (at in listOf(2, 7)) assertEquals("branch $at goes on with the stock code", hook.size, (stock[at] as BuilderOffsetInstruction).target.location.index)
        assertEquals("the stock code keeps its order", before, code.drop(hook.size).map { it.describe() })
    }

    @Test
    fun aSecondRunRefuses() {
        val context = PatchContexts.of(classes())
        context.hookFeedDoubleTap(context.findFeedDoubleTap())
        val failure = assertThrows(PatchException::class.java) { context.findFeedDoubleTap() }
        assertTrue(failure.message, failure.message!!.contains("already calls $FEED_DOUBLE_TAP_SKIP"))
    }

    @Test
    fun aDoubleTapOfAnotherShapeFails() {
        val cases = listOf(
            Shape.NO_LISTENER to "found 0",
            Shape.NO_DOUBLE_TAP to "one onDoubleTap, found 0",
            Shape.LOOPS to "jumps back to its start",
            Shape.ONE_LOCAL to "the hook borrows two",
            Shape.NO_FRAME_READ to "field of $holder, found 0",
        )
        for ((shape, message) in cases) {
            val context = PatchContexts.of(classes(shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findFeedDoubleTap() }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(message))
        }
    }

    /**
     * In each build the feed post's gesture listener is found through the holder Long press to zoom finds, its
     * onDoubleTap reads the holder and the media frame, and the hook goes in first, once, borrowing v0 and v1, with
     * every other instruction of the listener and the holder in place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachBuildHooksItsFeedDoubleTap() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
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
                val classes = classesOf(apk, wanted).values
                val context = PatchContexts.of(classes)

                val site = context.findFeedDoubleTap()
                assertEquals("$what: the listener's double tap", "onDoubleTap", site.tap.name)
                assertTrue("$what: the holder keeps a media frame", site.holderField.type in keeping)
                assertEquals("$what: the frame", MEDIA_FRAME, site.frameField.type)
                val owners = setOf(site.tap.definingClass, site.holderField.type)
                val originals = owners.flatMap { context.mutableClassDefBy(it).methods }.filter { it.implementation != null }
                    .associateWith(::NeutralNativePath)
                context.hookFeedDoubleTap(site)

                val asking = classes.filter { !it.type.startsWith("Lapp/hushgram/") }.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == FEED_DOUBLE_TAP_HOOK } }
                }
                assertEquals("$what: methods asking", listOf(site.tap.definingClass to "onDoubleTap"), asking.map { it.definingClass to it.name })
                val tap = asking.single()
                val code = tap.code()
                assertEquals("$what: the hook is first, once", listOf(5), code.indices.filter { code[it].referenceText() == FEED_DOUBLE_TAP_HOOK })
                assertEquals("$what: the hook", hook, code.take(hook.size).map { it.opcode })
                assertEquals("$what: it reads the holder", site.holderField.text(), code[1].referenceText())
                assertEquals("$what: and its frame", site.frameField.text(), code[3].referenceText())
                for ((method, original) in originals) {
                    val added = if (method.definingClass == tap.definingClass && method.name == tap.name) hook.indices.toSet() else emptySet()
                    original.assertPreserved("$what ${method.definingClass}->${method.name}", method, added)
                }
            }
        }
    }

    private enum class Shape { PLAIN, NO_LISTENER, NO_DOUBLE_TAP, LOOPS, ONE_LOCAL, NO_FRAME_READ }

    /**
     * A small app shaped like 454's: a touch holder keeping a media frame, a Media and a gesture listener and making a
     * GestureDetector; and the listener keeping the holder, whose double tap reads the holder, its frame and its Media
     * and likes the post.
     */
    private fun classes(shape: Shape = Shape.PLAIN): List<ClassDef> {
        val fields = mutableListOf(
            ImmutableField(holder, "frame", MEDIA_FRAME, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
            ImmutableField(holder, "media", media, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
        )
        if (shape != Shape.NO_LISTENER) {
            fields += ImmutableField(holder, "gestures", listener, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
        }
        val make = method(holder, "<init>", listOf("Landroid/content/Context;"), "V", 1, body = """
            new-instance v0, Landroid/view/GestureDetector;
            invoke-direct { v0, p1, p0 }, Landroid/view/GestureDetector;-><init>(Landroid/content/Context;Landroid/view/GestureDetector${'$'}OnGestureListener;)V
            return-void
        """, flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)
        val holderClass = ImmutableClassDef(holder, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, listOf(make))

        val top = if (shape == Shape.LOOPS) ":top" else ""
        val frameRead = if (shape == Shape.NO_FRAME_READ) "const/4 v1, 0x0" else "iget-object v1, v0, $holder->frame:$MEDIA_FRAME"
        val loop = if (shape == Shape.LOOPS) "if-eqz v1, :top" else ""
        val tap = if (shape == Shape.ONE_LOCAL) {
            method(listener, "onDoubleTap", listOf("Landroid/view/MotionEvent;"), "Z", 1, body = """
                const/4 v0, 0x1
                return v0
            """)
        } else {
            method(listener, "onDoubleTap", listOf("Landroid/view/MotionEvent;"), "Z", 3, body = """
                $top
                iget-object v0, p0, $listener->holder:$holder
                $frameRead
                $loop
                iget-object v2, v0, $holder->media:$media
                invoke-static { v1, v2 }, $like
                const/4 v0, 0x1
                return v0
            """)
        }
        val methods = mutableListOf<Method>()
        if (shape != Shape.NO_DOUBLE_TAP) methods += tap
        // A tap of the same listener: not the double tap.
        methods += method(listener, "onSingleTapConfirmed", listOf("Landroid/view/MotionEvent;"), "Z", 1, body = """
            const/4 v0, 0x0
            return v0
        """)
        val listenerClass = ImmutableClassDef(
            listener, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, simple, null, null, null,
            listOf(ImmutableField(listener, "holder", holder, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
            methods,
        )
        return listOf(holderClass, listenerClass)
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
        val total = registers + 1 + parameters.size
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

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) it.text() else it.toString()
    }

    private fun FieldReference.text(): String = "$definingClass->$name:$type"

    /** An instruction as text: its mnemonic, registers and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name) // opcode-name-ok
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        if (this@describe is FiveRegisterInstruction) append(listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount).joinToString(prefix = " {", postfix = "}") { "v$it" })
        referenceText()?.let { append(" $it") }
    }
}
