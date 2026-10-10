/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide ads (audit A04): Instagram's one ad-insert method answers that nothing went in from its
 * first instruction while the switch is on, and an anchor the patch can't tell apart fails it
 * before an instruction changes.
 */
class HideAdsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HIDE_ADS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDE_ADS is not in the extension: $declared", HIDE_ADS.substringAfter("->") in declared)
    }

    @Test
    fun theInsertAsksTheExtensionFirst() {
        val context = PatchContexts.of(listOf(injector()))

        val found = context.findAdInjector()
        context.guardAdInjector(found)

        assertEquals(INJECTOR, found.type)
        assertAnswersNoFirst(INJECTOR, context.mutableClassDefBy(INJECTOR).methods.single { it.name == "A00" })
    }

    /** A method holding one of the two strings is some other ad code. */
    @Test
    fun aMethodWithOneStringFailsThePatch() {
        for (string in AD_INJECTOR_STRINGS) {
            refuses("0 methods") { PatchContexts.of(listOf(injector(strings = listOf(string)))).findAdInjector() }
        }
    }

    @Test
    fun twoInsertersFailThePatch() {
        val context = PatchContexts.of(listOf(injector(), injector("Lfixture/OtherInjector;")))
        refuses("2 methods") { context.findAdInjector() }
    }

    /** Both strings in an instance method, one with other arguments or one answering nothing aren't the insert. */
    @Test
    fun aMethodOfAnotherShapeFailsThePatch() {
        refuses("0 methods") { PatchContexts.of(listOf(injector(static = false))).findAdInjector() }
        refuses("0 methods") { PatchContexts.of(listOf(injector(parameters = PARAMETERS.take(2)))).findAdInjector() }
        refuses("0 methods") { PatchContexts.of(listOf(injector(returns = "V"))).findAdInjector() }
    }

    /** With no local register to spare, the early answer has nowhere to go. */
    @Test
    fun anInsertWithoutALocalFailsThePatch() {
        val context = PatchContexts.of(listOf(injector(registers = PARAMETERS.size)))
        val found = context.findAdInjector()
        refuses("needs 1") { context.guardAdInjector(found) }
    }

    /** In each declared build: the one ad-insert method, asking the extension first. */
    @Test
    fun eachDeclaredBuildHidesAds() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, AD_INJECTOR_STRINGS.first()))

                val found = context.findAdInjector()
                context.guardAdInjector(found)

                val method = context.mutableClassDefBy(found.type).methods.single {
                    it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
                }
                assertAnswersNoFirst("${bundle.name} ${found.type}->${found.name}", method)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** The hook, its answer in v0, a branch past the early return to the insert's own first instruction, and that return of no. */
    private fun assertAnswersNoFirst(what: String, method: Method) {
        val code = method.instructions()
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$what: the hook", HIDE_ADS, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer's register", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the branch's register", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch's target", 5, (method.implementation!!.instructions.toList()[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: no", Opcode.CONST_4, code[3].opcode)
        assertEquals("$what: no's value", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: the early return", Opcode.RETURN, code[4].opcode)
        assertEquals("$what: the register returned", 0, (code[4] as OneRegisterInstruction).registerA)
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == HIDE_ADS })
    }

    private companion object {
        const val INJECTOR = "Lfixture/AdInjector;"
        val PARAMETERS = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;")

        /** Shaped like 449's and 450's: static, three arguments, a boolean back, both strings loaded. */
        fun injector(
            type: String = INJECTOR,
            strings: List<String> = AD_INJECTOR_STRINGS,
            static: Boolean = true,
            parameters: List<String> = PARAMETERS,
            returns: String = "Z",
            registers: Int = 8,
        ): ClassDef {
            val code = strings.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                if (returns == "V") listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
                else listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0))
            val access = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A00", parameters.map { ImmutableMethodParameter(it, null, null) }, returns, access, null, null,
                        ImmutableMethodImplementation(registers, code, null, null),
                    ),
                ),
            )
        }
    }
}
