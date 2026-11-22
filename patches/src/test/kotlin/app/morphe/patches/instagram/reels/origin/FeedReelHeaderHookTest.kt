/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.origin

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Find the reel you came from, for a reel in the feed (#795): its author's profile opens from the
 * post's header, so the header tap hands the post's media to the extension right after reading it,
 * on every build, and nothing else of Instagram's changes.
 */
class FeedReelHeaderHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_ORIGIN).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("not in the extension: $declared", FEED_OPENING.substringAfter("->") in declared)
    }

    /**
     * The header tap's read of its post's media is hooked, and a method that loads the same string
     * but reads a Media from something carrying no User, as 0TSv does on 456, is left alone.
     */
    @Test
    fun theHeaderTapsReadIsHooked() {
        val context = PatchContexts.of(listOf(tap(), tap(type = OTHER, author = false), extension()))
        val before = tap().methods.single().code()

        val taps = context.findFeedHeaderTaps()
        assertEquals("one tap", listOf(TAP), taps.map { it.type })
        assertEquals("the read", READ, taps.single().read)
        assertEquals("its register", 0, taps.single().register)
        context.hookFeedHeaderTap(taps.single())

        assertHooked("stand-in", before, context.mutableClassDefBy(TAP).methods.single().code(), taps.single())
        assertTrue("the other method was written", context.mutableClassDefBy(OTHER).methods.single().code().none { it.referenceText() == FEED_OPENING })
    }

    /**
     * A build the patch can't read fails at patch time, saying what it found, before anything is
     * written, and the patch goes on for reels opened from Reels with the log saying why.
     */
    @Test
    fun aTapThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            listOf(tap(key = "source_media_username"), extension()) to "no method loading",
            listOf(tap(author = false), extension()) to "no method loading",
            listOf(tap(lookup = false), extension()) to "no method loading",
            listOf(tap(twice = true), extension()) to "2 times, not once",
            listOf(tap(jumpPast = true), extension()) to "jumps to just past",
            listOf(tap()) to "extension has no public static openingFromFeed",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findFeedHeaderTaps() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val warnings = PatchLogCapture.warnings { assertTrue("$expected: found some", context.findFeedHeaderTapsOrWarn().isEmpty()) }
            assertEquals("$expected: $warnings", 1, warnings.size)
            assertTrue(warnings.single(), warnings.single().endsWith("$REEL_ORIGIN_PATCH goes in for reels opened from Reels alone."))
            assertTrue("$expected: something was written", context.mutableClassDefBy(TAP).methods.single().code().none { it.referenceText() == FEED_OPENING })
        }
    }

    @Test
    fun eachDeclaredBuildHooksBothFeedsHeaderTaps() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                // The main feed's tap, which puts the profile on the feed's tab, and the other feeds', which open it in an activity.
                assertEquals("${bundle.name}: header taps", 2, builds(bundle, bundle.name))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksAHeaderTapToo() {
        for (base in Fixtures.otherBuilds()) assertTrue("${base.parentFile.name}: no header tap", builds(base, base.parentFile.name) >= 1)
    }

    /** On a real build each tap reads the post's media once, beside the event's User, and only those reads are hooked, once each. */
    private fun builds(bundle: File, label: String): Int {
        val holders = FixtureDex.classesLoading(bundle, FEED_HEADER)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders) + ExtensionDex.classDef(REEL_ORIGIN))
        val taps = context.findFeedHeaderTaps()
        for (tap in taps) {
            val method = context.mutableClassDefBy(tap.type).methods.single { it.name == tap.name && it.parameterTypes.map(Any::toString) == tap.parameters }
            val before = method.code()
            val field = (before[tap.read] as ReferenceInstruction).reference as FieldReference
            assertEquals("$label: ${tap.type} reads the post's media", MEDIA, field.type)
            assertTrue("$label: ${tap.type} reads the event's User too", before.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.let { it.definingClass == field.definingClass && it.type == POST_AUTHOR } == true
            })
            context.hookFeedHeaderTap(tap)
            assertHooked(label, before, method.code(), tap)
        }
        assertEquals(
            "$label: methods hooked", taps.size,
            holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == FEED_OPENING } } },
        )
        return taps.size
    }

    /** The call sits right after the read, hands over only the register the read wrote, and Instagram's own code is kept. */
    private fun assertHooked(label: String, before: List<Instruction>, after: List<Instruction>, tap: FeedHeaderTap) {
        assertEquals("$label: the read", before[tap.read].opcode, after[tap.read].opcode)
        val call = after[tap.read + 1]
        assertEquals("$label: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$label: the hook", FEED_OPENING, call.referenceText())
        assertEquals("$label: what it's handed", tap.register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, call.registerCount)
        assertEquals("$label: called once", 1, after.count { it.referenceText() == FEED_OPENING })
        val kept = (after.take(tap.read + 1) + after.drop(tap.read + 2)).filter { it.opcode != Opcode.NOP }.map { it.kind() }
        assertEquals("$label: Instagram's own code is kept", before.filter { it.opcode != Opcode.NOP }.map { it.kind() }, kept)
    }

    /**
     * A stand-in for a feed post's header tap: it reads the post's media out of the tap's event, hands
     * it to MediaExtKt's look-up of the author, reads the event's User, and names the entry point.
     */
    private fun tap(
        type: String = TAP,
        key: String = FEED_HEADER,
        author: Boolean = true,
        lookup: Boolean = true,
        twice: Boolean = false,
        jumpPast: Boolean = false,
    ): ClassDef {
        val read = "iget-object v0, p1, $EVENT->media:$MEDIA"
        val look = if (lookup) "invoke-static { v1, v0 }, $LOOKUP" else "invoke-static { v1, v0 }, $AUTHOR_LOOKUPS->A0H($SESSION$MEDIA)$MEDIA"
        val body = listOfNotNull(
            "check-cast p1, $EVENT",
            if (jumpPast) "if-eqz p1, :past" else "nop",
            read,
            if (jumpPast) ":past" else null,
            "iget-object v1, p0, $type->session:$SESSION",
            look,
            "move-result-object v2",
            if (twice) read else null,
            if (twice) look else null,
            if (twice) "move-result-object v2" else null,
            if (author) "iget-object v3, p1, $EVENT->author:$POST_AUTHOR" else "iget-object v3, p1, $EVENT->tag:Ljava/lang/String;",
            "const-string v4, \"$key\"",
            "return-void",
        ).joinToString("\n")
        val method = MutableMethod(
            ImmutableMethod(
                type, "Fhr", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, ImmutableMethodImplementation(7, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(0, body)
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(method)))
    }

    /** The extension's ReelOrigin as far as the patch reads it: openingFromFeed. */
    private fun extension(): ClassDef {
        val method = MutableMethod(
            ImmutableMethod(
                REEL_ORIGIN, "openingFromFeed", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(1, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(0, "return-void")
        return ImmutableClassDef(REEL_ORIGIN, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(method)))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** An instruction's kind, a widened goto read as the goto it was. */
    private fun Instruction.kind(): Opcode = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private companion object {
        const val TAP = "Lfixture/HeaderTap;"
        const val OTHER = "Lfixture/OtherTap;"
        const val EVENT = "Lfixture/HeaderEvent;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val LOOKUP = "$AUTHOR_LOOKUPS->A0h($SESSION$MEDIA)$POST_AUTHOR"

        /** Where the stand-in reads the post's media: check-cast, nop, then the read. */
        const val READ = 2
    }
}
