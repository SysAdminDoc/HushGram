/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.commentkeyboard

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open comments without the keyboard: the hook goes in front of the one block that brings the
 * keyboard up as the comment sheet starts, on that block's label, and a yes takes the block's goto.
 */
class CommentKeyboardPatchTest {
    /** The patch switches its status method on and leaves the others as they were, warning when it can't find the block. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        commentKeyboardPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, COMMENT_KEYBOARD_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "commentEmojiRow").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** The hook the patch writes is in the CommentKeyboard the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(KEEPS_KEYBOARD_DOWN.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$KEEPS_KEYBOARD_DOWN is not in the extension: $declared", KEEPS_KEYBOARD_DOWN.substringAfter("->") in declared)
    }

    /**
     * In each 452 build the hook asks first thing in the block that brings the keyboard up: every
     * branch that went to the block now lands on the call, its answer goes to a free register, a yes
     * jumps to where the block's goto went, and a no falls into the block as before. On 385810270
     * that's LX/0FzY.A1E, reading LX/0GLz->A03.
     */
    @Test
    fun eachBuildSkipsTheKeyboardWhenAsked() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val enums = FixtureDex.classesLoading(bundle, KEYBOARD_AUTO_SHOW)
            val enumTypes = enums.filter { e -> e.methods.any { it.name == "<clinit>" } }.mapTo(HashSet()) { it.type }
            val readers = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.typeSection.none { it in enumTypes }) return@forEach
                for (classDef in dex.classes) {
                    if (classDef.type in enumTypes) continue
                    val reads = classDef.methods.any { m ->
                        m.code().any { it.opcode == Opcode.SGET_OBJECT && it.field()?.definingClass in enumTypes }
                    }
                    if (reads) readers += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, enums + readers))

            val site = context.findAutoShow()
            val before = context.classDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }.code()
            context.keepKeyboardDown(site)

            val patched = context.mutableClassDefBy(site.type).methods.single {
                it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
            }
            val code = patched.code()
            assertEquals("${bundle.name}: hooks", 1, code.count { it.method() == KEEPS_KEYBOARD_DOWN })
            val call = code.indexOfFirst { it.method() == KEEPS_KEYBOARD_DOWN }
            assertEquals("${bundle.name}: at the block's start", site.start, call)
            assertEquals("${bundle.name}: the answer", Opcode.MOVE_RESULT, code[call + 1].opcode)
            assertEquals("${bundle.name}: its register", site.register, (code[call + 1] as OneRegisterInstruction).registerA)
            val branch = code[call + 2]
            assertEquals("${bundle.name}: the way past the block", Opcode.IF_NEZ, branch.opcode)
            assertEquals("${bundle.name}: what it tests", site.register, (branch as OneRegisterInstruction).registerA)
            val landing = (branch as BuilderOffsetInstruction).target.location.index
            assertEquals("${bundle.name}: lands where the block's goto went", before[site.skip].opcode, code[landing].opcode)
            assertEquals("${bundle.name}: the same instruction", before[site.skip].text(), code[landing].text())
            assertEquals("${bundle.name}: then the block as it was", before[site.start].text(), code[call + 3].text())
            val reason = code.indexOfFirst { it.opcode == Opcode.SGET_OBJECT && it.field()?.name == before[site.read].field()?.name && it.field()?.definingClass in enumTypes }
            assertEquals("${bundle.name}: the reason still follows the block", site.read + 3, reason)
            val targets = patched.jumpTargets()
            assertTrue("${bundle.name}: branches to the block land on the call", call in targets)
            assertFalse("${bundle.name}: no branch skips the call", call + 1 in targets || call + 2 in targets || call + 3 in targets)
        }
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Instruction.method(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.text(): String = "$opcode ${(this as? ReferenceInstruction)?.reference}"
}
