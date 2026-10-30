/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.emojirow

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the comment emoji row: the hook answers 451's comment sheet's emoji check, the status method
 * goes on, and the extension finds the older picker's views by the ids Instagram's layout gives them.
 */
class HideCommentEmojiRowPatchTest {
    private val views = listOf("comment_emoji_picker_v1_parent", "comment_emoji_picker_v1_emoji_container")

    /** The extension the bundle ships has the install the application hook calls. */
    @Test
    fun theExtensionHasTheInstall() {
        val install = ExtensionDex.classDef("$EXTENSION_PACKAGE/feed/CommentEmojiRow;").methods.single { it.name == "install" }
        assertTrue(AccessFlags.PUBLIC.isSet(install.accessFlags) && AccessFlags.STATIC.isSet(install.accessFlags))
    }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        hideCommentEmojiRowPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, COMMENT_EMOJI_ROW_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideComments").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** On each declared build the patch applies, and the build declares the ids the extension looks for. */
    @Test
    fun eachDeclaredBuildHasTheRow() {
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
    fun eachOtherBuildHasTheRowToo() {
        for (base in Fixtures.otherBuilds()) declares(base, base.parentFile.name)
    }

    /** The hook the patch writes is in the CommentEmojiRow the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HIDES_EMOJI_ROW.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDES_EMOJI_ROW is not in the extension: $declared", HIDES_EMOJI_ROW.substringAfter("->") in declared)
    }

    /**
     * In each declared build the comment sheet's above-the-box component is the one method loading both
     * keys, and the hook goes right before the branch its emoji check takes past the strip: the
     * check's answer in, the answer back in its register, and nothing jumping into the call. The ids
     * the extension hides aren't on this sheet, which is why the row stayed with only those. On 451's
     * 385713073 that's LX/0Fas.A0o, building LX/0Exx.
     */
    @Test
    fun eachBuildSkipsTheStripWhenAsked() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every ${Fixtures.VERSION} build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == REPLYING_TO_BAR } }) holders += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders)

            val site = context.findEmojiStrip()
            context.hideEmojiStrip(site)

            val patched = context.mutableClassDefBy(site.type).methods.single {
                it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
            }
            val code = patched.code()
            assertEquals("${bundle.name}: hooks", 1, code.count { it.referenceText() == HIDES_EMOJI_ROW })
            val call = code.indexOfFirst { it.referenceText() == HIDES_EMOJI_ROW }
            assertEquals("${bundle.name}: the emoji check", "Ljava/util/List;->isEmpty()Z", code[call - 2].referenceText())
            assertEquals("${bundle.name}: its answer", Opcode.MOVE_RESULT, code[call - 1].opcode)
            assertEquals("${bundle.name}: the call", Opcode.INVOKE_STATIC_RANGE, code[call].opcode)
            assertEquals("${bundle.name}: what it's handed", site.answer, (code[call] as RegisterRangeInstruction).startRegister)
            assertEquals("${bundle.name}: one register", 1, (code[call] as RegisterRangeInstruction).registerCount)
            assertEquals("${bundle.name}: the answer", Opcode.MOVE_RESULT, code[call + 1].opcode)
            assertEquals("${bundle.name}: the answer's register", site.answer, (code[call + 1] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: the way past the strip", Opcode.IF_NEZ, code[call + 2].opcode)
            assertEquals("${bundle.name}: what it tests", site.answer, (code[call + 2] as OneRegisterInstruction).registerA)
            val targets = patched.jumpTargets()
            assertFalse("${bundle.name}: a jump lands in or skips the call", call in targets || call + 1 in targets || call + 2 in targets)
        }
    }

    private fun declares(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(),
            ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), views))
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        hideCommentEmojiRowPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, COMMENT_EMOJI_ROW_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
