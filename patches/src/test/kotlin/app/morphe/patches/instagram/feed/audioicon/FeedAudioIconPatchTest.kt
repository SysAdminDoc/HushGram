/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.audioicon

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the audio icon on posts switches the status method on and hooks the one store of the post
 * state's hide-the-audio-icon flag (#464), found by the label the state's toString prints before it.
 */
class FeedAudioIconPatchTest {
    /** The hook the patch writes is in the FeedAudioIcon the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_AUDIO_ICON).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDES_AUDIO_ICON is not in the extension: $declared", HIDES_AUDIO_ICON.substringAfter("->") in declared)
    }

    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes())
        feedAudioIconPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, FEED_AUDIO_ICON_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideComments").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test
    fun theStoreIsFound() {
        val found = PatchContexts.of(classes()).findFeedAudioIcon()

        assertEquals(STATE, found.type)
        assertEquals("hidden", found.field)
        assertEquals(2, found.index)
        assertEquals(0, found.register)
    }

    /** The hook sits between the move and the store, on the stored register, and nothing else is touched. */
    @Test
    fun theHookSitsInFrontOfTheStore() {
        val context = PatchContexts.of(classes())
        val found = context.findFeedAudioIcon()
        val before = context.constructor().size

        found.write(context)

        val code = context.constructor()
        assertEquals("two instructions in", before + 2, code.size)
        assertHooked("stand-in", code, found.index, found.register)
        assertEquals("the other field's store is kept", Opcode.IPUT_OBJECT, code.last { it.opcode != Opcode.RETURN_VOID }.opcode)
    }

    @Test
    fun noStateFailsThePatch() = refused("found 0", classes(leaveOut = true))

    @Test
    fun twoStatesFailThePatch() = refused("found 2", classes(second = true))

    @Test
    fun aStoreNotFedByAMoveFailsThePatch() = refused("found 0", classes(variant = "const"))

    @Test
    fun twoStoresFailThePatch() = refused("found 2", classes(doubleConstructor = true))

    @Test
    fun aFieldOfAnotherClassFailsThePatch() = refused("belongs to", classes(variant = "foreign"))

    /** On each declared build the patch applies, and the one store is hooked. */
    @Test
    fun eachDeclaredBuildHidesTheIcon() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hidesIcon(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHidesTheIconToo() {
        var checked = 0
        for (base in Fixtures.otherBuilds()) {
            hidesIcon(base, base.parentFile.name)
            checked++
        }
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, checked)
    }

    /**
     * Slices the fixture the way the finder reads it, every class loading the label with the string
     * pools they call, and runs the whole patch over it: exactly one constructor store is hooked.
     */
    private fun hidesIcon(bundle: File, label: String) {
        val holders = FixtureDex.classesLoading(bundle, AUDIO_ICON_LABEL)
        assertTrue("$label: no class prints the label", holders.isNotEmpty())
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + FixtureDex.withStringPools(bundle, holders))
        val found = context.findFeedAudioIcon()

        feedAudioIconPatch.execute(context)

        val status = context.method(SETTINGS_STATUS, FEED_AUDIO_ICON_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val hooked = holders.flatMap { holder ->
            context.mutableClassDefBy(holder.type).methods.filter { method ->
                method.code().any { it.reference()?.toString() == HIDES_AUDIO_ICON }
            }
        }
        assertEquals("$label: one method hooked", 1, hooked.size)
        assertEquals("$label: the constructor", "<init>", hooked.single().name)
        val code = hooked.single().code()
        val at = code.indexOfFirst { it.reference()?.toString() == HIDES_AUDIO_ICON }
        assertHooked("$label ${found.type}", code.filter { it.opcode != Opcode.NOP }, at, found.register)
    }

    /** invoke/range on the stored register, its answer back into the same register, then the store. */
    private fun assertHooked(what: String, code: List<Instruction>, at: Int, register: Int) {
        val calls = code.indices.filter { code[it].reference()?.toString() == HIDES_AUDIO_ICON }
        assertEquals("$what: one hook", listOf(at), calls)
        assertEquals("$what: a range call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
        assertEquals("$what: with one register", 1, (code[at] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: the stored one", register, (code[at] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: in the same register", register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: then the store", Opcode.IPUT_BOOLEAN, code[at + 2].opcode)
        assertEquals("$what: of that register", register, (code[at + 2] as OneRegisterInstruction).registerA)
    }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findFeedAudioIcon() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private fun BytecodePatchContext.constructor(): List<Instruction> =
        mutableClassDefBy(STATE).methods.single { it.name == "<init>" }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val STATE = "Lfixture/State;"
        const val SB = "Ljava/lang/StringBuilder;"

        fun build(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, smali: String): Method {
            val method = MutableMethod(
                ImmutableMethod(
                    owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                    ImmutableMethodImplementation(6, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali.trimIndent())
            return ImmutableMethod.of(method)
        }

        /** Shaped like 453's state: the flag is stored from a moved parameter, and toString prints the label then the flag. */
        fun state(type: String, variant: String, doubleConstructor: Boolean): ClassDef {
            val stored = if (variant == "foreign") "Lfixture/Other;" else type
            val first = when (variant) {
                "const" -> "const/4 v0, 0x1"
                else -> "move v0, p1"
            }
            val constructor = """
                invoke-direct {p0}, $OBJECT-><init>()V
                $first
                iput-boolean v0, p0, $stored->hidden:Z
                iput-object p2, p0, $type->other:$OBJECT
                return-void
            """
            val toString = """
                iget-boolean v1, p0, $stored->hidden:Z
                new-instance v0, $SB
                invoke-direct {v0}, $SB-><init>()V
                const-string v2, "$AUDIO_ICON_LABEL"
                invoke-virtual {v0, v2}, $SB->append(Ljava/lang/String;)$SB
                invoke-virtual {v0, v1}, $SB->append(Z)$SB
                invoke-virtual {v0}, $SB->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
            val methods = mutableListOf(
                build(type, "<init>", listOf("Z", OBJECT), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, constructor),
                build(type, "toString", emptyList(), "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, toString),
            )
            if (doubleConstructor) {
                methods += build(type, "<init>", listOf("Z"), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, """
                    invoke-direct {p0}, $OBJECT-><init>()V
                    move v0, p1
                    iput-boolean v0, p0, $type->hidden:Z
                    return-void
                """)
            }
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, methods)
        }

        fun classes(
            leaveOut: Boolean = false,
            second: Boolean = false,
            variant: String = "ok",
            doubleConstructor: Boolean = false,
        ): List<ClassDef> {
            val states = if (leaveOut) emptyList() else listOf(state(STATE, variant, doubleConstructor)) +
                (if (second) listOf(state("Lfixture/State2;", "ok", false)) else emptyList())
            return states + ImmutableClassDef("Lfixture/Unrelated;", AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, emptyList())
        }
    }
}
