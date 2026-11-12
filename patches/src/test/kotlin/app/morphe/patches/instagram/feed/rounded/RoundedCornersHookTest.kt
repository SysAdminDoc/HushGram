/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.rounded

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.zoom.MEDIA_FRAME
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
 * Rounded post corners' one call and one read: found by structure on stand-ins shaped like 450 and on
 * every one of the version's seven builds, the call right before each return of the touch holder's
 * constructor, every other instruction in place, and a build that differs stopping the patch before
 * anything changes.
 */
class RoundedCornersHookTest {
    private val holder = "Lfixture/MediaTouch;"

    /** The hook is in the RoundedCorners the bundle ships, and the read in RoundedTargets, public and static. */
    @Test
    fun theHookAndTheReadAreInTheExtension() {
        fun declared(type: String) = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$ROUNDED_MADE is not in the extension", ROUNDED_MADE.substringAfter("->") in declared(ROUNDED_CORNERS))
        assertTrue("frame is not in $ROUNDED_TARGETS", "frame(Ljava/lang/Object;)Ljava/lang/Object;" in declared(ROUNDED_TARGETS))
    }

    /** The call is right before the constructor's return, with the holder itself, nothing else changes, and the read checks the holder's type. */
    @Test
    fun theCallGoesBeforeTheReturnAndTheReadIsWritten() {
        val context = PatchContexts.of(classes())
        val before = context.method(holder, "<init>").code().map { it.describe() }
        val site = context.findRoundedSite()
        assertEquals("$holder-><init>", "${site.make.definingClass}->${site.make.name}")
        assertEquals("$holder->frame:$MEDIA_FRAME", site.frame.text())
        context.apply { applyRoundedCorners(site) }

        val make = context.method(holder, "<init>")
        val made = make.code().indices.filter { make.code()[it].referenceText() == ROUNDED_MADE }
        assertEquals("once, right before the return", listOf(make.code().size - 2), made)
        assertEquals(Opcode.RETURN_VOID, make.code().last().opcode)
        assertEquals("it hands over the holder itself", listOf(make.implementation!!.registerCount - 2), make.code()[made.single()].arguments())
        assertEquals(before, make.code().filterIndexed { at, _ -> at !in made }.map { it.describe() })

        val read = context.mutableClassDefBy(ROUNDED_TARGETS).methods.single { it.name == "frame" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), read.take(3).map { it.opcode })
        assertEquals(holder, read[0].referenceText())
        assertEquals(site.frame.text(), read[1].referenceText())
    }

    /** A constructor with two returns gets the call before each of them. */
    @Test
    fun everyReturnOfTheConstructorIsCovered() {
        val context = PatchContexts.of(classes())
        val site = context.findRoundedSite()
        context.method(holder, "<init>").addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :late
                return-void
                :late
                nop
            """,
        )
        context.apply { applyRoundedCorners(site) }
        val code = context.method(holder, "<init>").code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertEquals(2, returns.size)
        for (at in returns) assertEquals("before the return at $at", ROUNDED_MADE, code[at - 1].referenceText())
        assertEquals(2, code.count { it.referenceText() == ROUNDED_MADE })
    }

    @Test
    fun aBuildThatDiffersFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape.NO_HOLDER to "found 0",
            Shape.TWO_HOLDERS to "found 2",
            Shape.PRIVATE_HOLDER to "isn't public",
            Shape.PRIVATE_FRAME to "the media frame, isn't public",
            Shape.TWO_FRAMES to "in one field, found 2",
            Shape.TWO_MAKES to "to have one constructor, found 2",
            Shape.MAKE_JUMPS_TO_RETURN to "constructor jumps to a return",
            Shape.MAKE_WRITES_THIS to "writes the register holding this",
        )
        for ((shape, message) in cases) {
            val context = PatchContexts.of(classes(shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findRoundedSite() }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(message))
            val touched = context.classDefByOrNull(holder)?.let { context.mutableClassDefBy(it.type).methods }.orEmpty()
            assertTrue("$shape: something was written", touched.none { m -> m.code().any { it.referenceText() == ROUNDED_MADE } })
        }
    }

    /**
     * In each of the version's seven builds the touch holder is found once, the call goes in right before
     * each return of its one constructor with the holder itself, every other instruction of the class keeps
     * its place, and the read names the frame field found. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildTakesTheCall() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val detecting = mutableSetOf<String>()
            FixtureDex.forEach(apk) { dex ->
                for (c in dex.classes) {
                    val types = c.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }
                    if (MEDIA_FRAME !in types || MEDIA !in types) continue
                    if (c.methods.any { m -> m.code().any { it.referenceText()?.startsWith("Landroid/view/GestureDetector;-><init>") == true } }) {
                        detecting += c.type
                    }
                }
            }
            assertEquals("$name: classes keeping a media frame and a Media that make a GestureDetector", 1, detecting.size)
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, detecting).values + ExtensionDex.classDef(ROUNDED_TARGETS)
                val context = PatchContexts.of(classes)
                val site = context.findRoundedSite()
                assertEquals("$what: the touch holder", detecting.single(), site.make.definingClass)
                assertEquals("$what: the frame field", MEDIA_FRAME, site.frame.type)
                val originals = context.mutableClassDefBy(site.make.definingClass).methods.filter { it.implementation != null }
                    .associateWith(::NeutralNativePath)
                context.apply { applyRoundedCorners(site) }

                val makers = classes.filter { !it.type.startsWith("Lapp/hushgram/") }.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == ROUNDED_MADE } }
                }
                assertEquals("$what: methods handing the holder over", listOf(site.make.definingClass to "<init>"), makers.map { it.definingClass to it.name })
                val make = makers.single()
                val code = make.code()
                val madeAt = code.indices.filter { code[it].referenceText() == ROUNDED_MADE }
                val returnsAt = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
                assertTrue("$what: a constructor that returns", returnsAt.isNotEmpty())
                assertEquals("$what: a call right before every return, once each", returnsAt.map { it - 1 }, madeAt)
                val self = make.implementation!!.registerCount - 1 - make.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                for (at in madeAt) assertEquals("$what: the holder itself", listOf(self), code[at].arguments())
                for ((method, original) in originals) {
                    val added = if (method.name == make.name && method.parameterTypes == make.parameterTypes) madeAt.toSet() else emptySet()
                    original.assertPreserved("$what ${method.definingClass}->${method.name}", method, added)
                }
                val frameRead = context.mutableClassDefBy(ROUNDED_TARGETS).methods.single { it.name == "frame" }.code()
                assertEquals("$what: the read", site.frame.text(), frameRead[1].referenceText())
            }
        }
    }

    private enum class Shape {
        PLAIN, NO_HOLDER, TWO_HOLDERS, PRIVATE_HOLDER, PRIVATE_FRAME, TWO_FRAMES, TWO_MAKES, MAKE_JUMPS_TO_RETURN, MAKE_WRITES_THIS,
    }

    /** A small app shaped like 450's: a touch holder keeping a media frame and a Media, making a GestureDetector. */
    private fun classes(shape: Shape = Shape.PLAIN): List<ClassDef> {
        val open = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        fun field(owner: String, name: String, type: String, flags: Int = open) = ImmutableField(owner, name, type, flags, null, null, null)
        fun holderClass(type: String): ClassDef {
            val fields = mutableListOf(
                field(type, "frame", MEDIA_FRAME, if (shape == Shape.PRIVATE_FRAME) AccessFlags.PRIVATE.value else open),
                field(type, "media", MEDIA),
                field(type, "detector", "Landroid/view/GestureDetector;"),
            )
            if (shape == Shape.TWO_FRAMES) fields += field(type, "other", MEDIA_FRAME)
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
            val touch = method(type, "onTouch", listOf("Landroid/view/MotionEvent;"), "Z", 1, body = """
                const/4 v0, 0x1
                return v0
            """)
            val flags = if (shape == Shape.PRIVATE_HOLDER) AccessFlags.FINAL.value else open
            val methods = if (shape == Shape.TWO_MAKES) listOf(make, another, touch) else listOf(make, touch)
            return ImmutableClassDef(type, flags, "Ljava/lang/Object;", null, null, null, fields, methods)
        }
        val found = mutableListOf(
            ImmutableClassDef(MEDIA, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null),
            ExtensionDex.classDef(ROUNDED_TARGETS),
        )
        if (shape != Shape.NO_HOLDER) found += holderClass(holder)
        if (shape == Shape.TWO_HOLDERS) found += holderClass("Lfixture/OtherTouch;")
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
