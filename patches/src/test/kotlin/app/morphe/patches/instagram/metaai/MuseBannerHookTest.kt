/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hide Meta AI on profiles: the Muse banner builder answers null while the switch is on. */
class MuseBannerHookTest {
    private val owner = "Lfixture/ProfileBanners;"
    private val banner = "Lfixture/Banner;"
    private val context = "Landroid/content/Context;"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(MUSE_BANNER.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$MUSE_BANNER is not in the extension: $declared", MUSE_BANNER.substringAfter("->") in declared)
    }

    /** The builder is found by its two event names, and the check goes in ahead of its first instruction. */
    @Test
    fun theBuilderAnswersNullWhileTheSwitchIsOn() {
        val patch = PatchContexts.of(classes())
        val found = patch.findMuseBannerBuilder()
        assertEquals(owner, found.type)
        assertEquals("muse", found.name)
        val before = patch.mutableClassDefBy(owner).methods.single { it.name == "muse" }.code()

        patch.holdMuseBanner(found)

        val after = patch.mutableClassDefBy(owner).methods.single { it.name == "muse" }.code()
        assertAsked("synthetic", before, after)
        assertEquals("the other method is left alone", 2, patch.mutableClassDefBy(owner).methods.single { it.name == "other" }.code().size)
    }

    @Test
    fun noBuilderOrTwoFailThePatch() {
        for (case in listOf(classes(events = listOf("tap_muse_banner")), classes(builders = 2), classes(parameters = listOf(banner)))) {
            assertThrows(PatchException::class.java) { PatchContexts.of(case).findMuseBannerBuilder() }
        }
    }

    @Test
    fun aBuilderWithNoLocalsFailsThePatch() {
        val patch = PatchContexts.of(classes(registers = 2))
        val found = patch.findMuseBannerBuilder()
        assertThrows(PatchException::class.java) { patch.holdMuseBanner(found) }
    }

    /**
     * In each declared build, and in every other build of a declared version, one static method
     * takes a Context and answers a banner while naming both events, and the check goes in ahead of
     * its first instruction without changing the rest.
     */
    @Test
    fun eachBuildHasOneMuseBannerBuilder() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            builds(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = MUSE_BANNER_EVENTS.flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
        val patch = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))
        val found = patch.findMuseBannerBuilder()
        val before = holders.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()

        patch.holdMuseBanner(found)

        val after = patch.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()
        assertAsked(label, before, after)
    }

    /** The ask, its answer, the test, a null back, and then every original instruction in its order. */
    private fun assertAsked(what: String, before: List<Instruction>, after: List<Instruction>) {
        assertEquals("$what: the ask comes first", MUSE_BANNER, after[0].referenceText())
        assertEquals("$what: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$what: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$what: null", Opcode.CONST_4, after[3].opcode)
        assertEquals("$what: returned", Opcode.RETURN_OBJECT, after[4].opcode)
        assertEquals("$what: one ask", 1, after.count { it.referenceText() == MUSE_BANNER })
        assertEquals("$what: the rest is as it was", before.map { it.opcode }, after.drop(5).map { it.opcode })
    }

    private fun classes(
        events: List<String> = MUSE_BANNER_EVENTS,
        builders: Int = 1,
        parameters: List<String> = listOf(context, "Ljava/lang/Object;"),
        registers: Int = 6,
    ): List<ClassDef> {
        val strings = events.joinToString("\n") { "const-string v0, \"$it\"" }
        val body = """
            $strings
            const/4 v1, 0x0
            return-object v1
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val first = classDef(owner, listOf(
            method(owner, "muse", parameters, banner, registers, body, flags),
            method(owner, "other", listOf(context), banner, 2, "const/4 v0, 0x0\nreturn-object v0", flags),
        ))
        val extra = (1 until builders).map { copy ->
            val type = "Lfixture/MoreBanners$copy;"
            classDef(type, listOf(method(type, "muse", parameters, banner, registers, body, flags)))
        }
        return listOf(first) + extra
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
