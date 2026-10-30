/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.feedfollow

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordinary Follow views keep their id coverage. Subscribe's current feed renderer instead
 * needs its native eligibility answer filtered before it builds the button.
 */
class HideFeedFollowPatchTest {
    private val views = listOf(
        "row_feed_profile_header", "inline_follow_button", "follow_in_header", "subscribe_button",
        "subscribe_shortcut_button", "subscribe_shortcut_stub",
    )

    /** The extension the bundle ships has the install the application hook calls. */
    @Test
    fun theExtensionHasTheInstall() {
        val install = ExtensionDex.classDef("$EXTENSION_PACKAGE/feed/FeedFollowButton;").methods.single { it.name == "install" }
        assertTrue(AccessFlags.PUBLIC.isSet(install.accessFlags) && AccessFlags.STATIC.isSet(install.accessFlags))
    }

    @Test
    fun theNativeSubscribeFilterIsPublicAndTakesAnInt() {
        val hook = ExtensionDex.classDef(FEED_BUTTON).methods.single {
            it.name == "showSubscribe" && it.parameterTypes.map(CharSequence::toString) == listOf("I") && it.returnType == "Z"
        }
        assertTrue(AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        hideFeedFollowPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, FEED_FOLLOW_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideComments").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** On each declared build the patch applies, and the build declares the ids the extension looks for. */
    @Test
    fun eachDeclaredBuildHasTheButtons() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                declares(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHasTheButtonsToo() {
        for (base in Fixtures.otherBuilds()) declares(base, base.parentFile.name)
    }

    /**
     * #248: a followed creator's Subscribe is a Litho child with no Subscribe view id. The native
     * header's two-boolean state gates just that child, whose tap and impression call the specific
     * subscription loggers. The patch must filter the first answer before its existing branch.
     * These are the actual vendor methods, with no account or subscription manufactured by the test.
     */
    @Test
    fun eachNativeFeedSubscribeGateIsFiltered() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }
        } + Fixtures.otherBuilds()
        assertEquals("every declared native build", Fixtures.BUILDS, bundles.size)
        val missed = mutableListOf<String>()
        for (bundle in bundles) {
            val label = if (bundle.name == "base.apk") bundle.parentFile.name else bundle.name
            val native = subscribeBuilds.entries.single { label.contains(it.key) }.value
            val classes = FixtureDex.classes(bundle, native.types()).values
            assertEquals("$label: the native subscription chain", native.types(), classes.map { it.type }.toSet())
            val header = classes.single { it.type == native.header }.methods.single {
                it.name == native.method && it.code().any { step -> step.string() == MEDIA_HEADER_STATE }
            }
            val before = header.code().filter { it.opcode != Opcode.NOP }
            val field = "${native.eligibility}->A00:Z"
            // 453's header reads the state twice; Subscribe's read is the one its label follows.
            val read = before.indices.single { at ->
                before[at].opcode == Opcode.IGET_BOOLEAN && before[at].referenceText() == field &&
                    before.drop(at).take(12).any { (it as? NarrowLiteralInstruction)?.narrowLiteral == native.label }
            }
            val answer = (before[read] as OneRegisterInstruction).registerA
            assertEquals("$label: only the native CTA's eligibility", Opcode.IF_EQZ, before[read + 1].opcode)
            assertEquals(answer, (before[read + 1] as OneRegisterInstruction).registerA)
            assertTrue("$label: Subscribe's own label", before.drop(read).take(12).any {
                (it as? NarrowLiteralInstruction)?.narrowLiteral == native.label
            })
            assertTrue("$label: Subscribe's click", before.drop(read).take(18).any {
                it.opcode == Opcode.NEW_INSTANCE && it.referenceText() == native.click
            })
            val eligibility = classes.single { it.type == native.eligibility }
            assertEquals("$label: the subscription state", 2, eligibility.fields.count { it.type == "Z" })
            val logger = classes.single { it.type == native.logger }
            assertTrue("$label: the CTA tap identifies its logger", logger.methods.any {
                it.code().any { step -> step.string() == "ig_fan_club_subscribe_cta_tap" }
            })
            assertTrue("$label: the CTA impression identifies the same logger", logger.methods.any {
                it.code().any { step -> step.string() == "ig_fan_club_subscribe_cta_impression" }
            })
            assertTrue("$label: the click belongs to this header", classes.single { it.type == native.click }.methods.any {
                val code = it.code()
                code.any { step -> step.opcode == Opcode.CHECK_CAST && step.referenceText() == native.header } &&
                    code.any { step -> (step.reference() as? MethodReference)?.let { call ->
                        call.definingClass == native.logger && call.name == "A07"
                    } == true }
            })

            // Keep the header's direct native dependencies available to the production finder.
            val references = classes.flatMap { owner -> owner.methods.flatMap { it.code() } }.mapNotNull { step ->
                when (val reference = step.reference()) {
                    is TypeReference -> reference.type
                    is FieldReference -> reference.definingClass
                    is MethodReference -> reference.definingClass
                    else -> null
                }
            }.toSet()
            val context = PatchContexts.of((classes + FixtureDex.classes(bundle, references).values +
                ExtensionDex.classDef(SETTINGS_STATUS) + ExtensionDex.classDef(FEED_BUTTON)).distinctBy { it.type })
            hideFeedFollowPatch.execute(context)
            val patched = context.mutableClassDefBy(native.header).methods.single {
                it.name == header.name && it.parameterTypes.map(CharSequence::toString) == header.parameterTypes.map(CharSequence::toString)
            }
            val code = patched.code().filter { it.opcode != Opcode.NOP }
            fun filtered(steps: List<Instruction>, at: Int): Boolean {
                val call = steps.getOrNull(at + 1)?.reference() as? MethodReference
                return steps[at].opcode == Opcode.IGET_BOOLEAN && steps[at].referenceText() == field && call != null &&
                    call.definingClass == FEED_BUTTON && call.parameterTypes.map(CharSequence::toString) == listOf("I") && call.returnType == "Z"
            }
            val changedRead = code.indices.singleOrNull { filtered(code, it) }
            if (changedRead == null || code.subList(0, changedRead).count { it.referenceText() == field } !=
                before.subList(0, read).count { it.referenceText() == field }
            ) {
                missed += label
                continue
            }
            assertEquals("$label: filters that native answer", Opcode.INVOKE_STATIC_RANGE, code[changedRead + 1].opcode)
            assertEquals(answer, (code[changedRead + 1] as RegisterRangeInstruction).startRegister)
            assertEquals(1, (code[changedRead + 1] as RegisterRangeInstruction).registerCount)
            assertEquals(Opcode.MOVE_RESULT, code[changedRead + 2].opcode)
            assertEquals(answer, (code[changedRead + 2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.IF_EQZ, code[changedRead + 3].opcode)
            assertEquals(answer, (code[changedRead + 3] as OneRegisterInstruction).registerA)
            val targets = patched.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
                .map { it.target.location.index }.toSet()
            val rawCode = patched.code()
            val rawRead = rawCode.indices.single { filtered(rawCode, it) }
            assertFalse("$label: no jump bypasses the answer filter", rawRead + 2 in targets || rawRead + 3 in targets)
            val unchanged = code.filterIndexed { index, _ -> index !in changedRead + 1..changedRead + 2 }
            assertEquals("$label: the other header controls stay native", before.map { it.opcode to it.referenceText() },
                unchanged.map { it.opcode to it.referenceText() })
        }
        assertEquals("native feed Subscribe gates still bypass Hide Follow", emptyList<String>(), missed)
    }

    private data class NativeSubscribe(
        val header: String, val method: String, val eligibility: String, val click: String, val logger: String, val label: Int,
    ) {
        fun types() = setOf(header, eligibility, click, logger)
    }

    /** 453.0.0.57.79's eight builds, found with SubscribeNames; the read is followed by Subscribe's label and click. */
    private val subscribeBuilds = mapOf(
        "385914503" to NativeSubscribe("LX/010I;", "A0o", "LX/00h3;", "LX/0SmM;", "LX/02UW;", 0x7f138132),
        "385914510" to NativeSubscribe("LX/010K;", "A0o", "LX/00h5;", "LX/0WZa;", "LX/02VL;", 0x7f138132),
        "385914517" to NativeSubscribe("LX/0023;", "A0o", "LX/08iK;", "LX/0QkJ;", "LX/01Eu;", 0x7f138130),
        "385914518" to NativeSubscribe("LX/0096;", "A0p", "LX/08iY;", "LX/0SkO;", "LX/01GQ;", 0x7f138132),
        "385914546" to NativeSubscribe("LX/0024;", "A0o", "LX/08iK;", "LX/0TnW;", "LX/01FQ;", 0x7f138130),
        "385914556" to NativeSubscribe("LX/010I;", "A0o", "LX/00h3;", "LX/0SmM;", "LX/02UW;", 0x7f138132),
        "385914557" to NativeSubscribe("LX/0023;", "A0o", "LX/08iK;", "LX/0QkP;", "LX/01FK;", 0x7f138130),
        "385914558" to NativeSubscribe("LX/0096;", "A0p", "LX/08iY;", "LX/0SkO;", "LX/01GQ;", 0x7f138132),
    )

    private val FEED_BUTTON = "$EXTENSION_PACKAGE/feed/FeedFollowButton;"
    private val MEDIA_HEADER_STATE = "null cannot be cast to non-null type instagram.features.feed.ui.rows.mediaheader.domain.uicontract.MediaHeaderUiState"

    private fun declares(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(),
            ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), views))
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        hideFeedFollowPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, FEED_FOLLOW_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string
}
