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
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
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
 * Hide the tag icon on posts (#762): the bind of a post's corner tag indicator is found through the
 * enum of indicator kinds, and its state goes through FeedAudioIcon.tagIndicator before the bind
 * reads it, so a null answer takes the bind's own path that hides the indicator.
 */
class TagIndicatorHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_AUDIO_ICON).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$TAG_INDICATOR is not in the extension: $declared", TAG_INDICATOR.substringAfter("->") in declared)
    }

    @Test
    fun theBindIsFoundByItsNullTestedState() {
        val found = PatchContexts.of(TagIndicatorFixture.classes()).findTagIndicatorBind()

        assertEquals(TagIndicatorFixture.BINDER, found.type)
        assertEquals("bind", found.name)
        assertEquals(1, found.stateParameter)
        assertEquals(TagIndicatorFixture.STATE, found.stateType)
        assertEquals("kind", found.kindField)
        assertEquals(TagIndicatorFixture.KINDS, found.kindType)
    }

    /** The hook is the bind's first seven instructions, and the bind's own code follows it unchanged. */
    @Test
    fun theHookFrontsTheBind() {
        val context = PatchContexts.of(TagIndicatorFixture.classes())
        val stock = context.bind().code()
        val found = context.findTagIndicatorBind()

        found.write(context)

        val code = context.bind().code()
        assertHooked("fixture", code, stock, register = 5, found)
        for (decoy in listOf(TagIndicatorFixture.HOLDER_READER, TagIndicatorFixture.NO_LISTENER)) {
            for (method in context.mutableClassDefBy(decoy).methods) {
                assertTrue("$decoy->${method.name} changed", method.code().none { it.calls(TAG_INDICATOR) })
            }
        }
    }

    @Test
    fun withoutTheKindsNothingIsFound() = refused("found 0", TagIndicatorFixture.classes(kinds = false))

    @Test
    fun kindsWithoutThePeopleTagAreNotTheOnes() = refused("found 0", TagIndicatorFixture.classes(people = false))

    @Test
    fun aBindThatNeverTestsTheStateIsNotTheOne() = refused("found 0", TagIndicatorFixture.classes(nullTest = false))

    @Test
    fun twoBindsFailThePatch() = refused("found 2", TagIndicatorFixture.classes(second = true))

    /** On each build the one bind is found, takes a click listener, and is fronted by the hook. */
    @Test
    fun eachBuildFindsTheTagIndicator() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val label = "${bundle.parentFile.name}/${bundle.name}"
            val context = PatchContexts.of(TagIndicatorFixture.slice(bundle))
            val found = context.findTagIndicatorBind()
            assertTrue("$label: a click listener", "Landroid/view/View\$OnClickListener;" in found.parameters)
            val bind = context.mutableClassDefBy(found.type).methods.single {
                it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameters
            }
            val stock = bind.code()
            val register = bind.parameterRegisterNumber(found.stateParameter)

            found.write(context)

            assertHooked(label, bind.code(), stock, register, found)
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    private fun assertHooked(what: String, code: List<Instruction>, stock: List<Instruction>, register: Int, found: TagIndicatorBind) {
        assertEquals("$what: one call", 1, code.count { it.calls(TAG_INDICATOR) })
        assertEquals("$what: the state into v0", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: v0", 0, (code[0] as TwoRegisterInstruction).registerA)
        assertEquals("$what: from the state", register, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: no state skips the call", Opcode.IF_EQZ, code[1].opcode)
        assertEquals("$what: onto the bind's own first instruction", code.subList(1, 7).sumOf { it.codeUnits },
            (code[1] as OffsetInstruction).codeOffset)
        assertEquals("$what: the kind read", Opcode.IGET_OBJECT, code[2].opcode)
        val field = (code[2] as ReferenceInstruction).reference as FieldReference
        assertEquals("$what: the state's kind", "${found.stateType}->${found.kindField}:${found.kindType}",
            "${field.definingClass}->${field.name}:${field.type}")
        assertTrue("$what: the call", code[3].calls(TAG_INDICATOR))
        assertEquals("$what: the state and its kind", listOf(0, 1),
            (code[3] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[4].opcode)
        assertEquals("$what: cast back", found.stateType, ((code[5] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: into the state's register", Opcode.MOVE_OBJECT_FROM16, code[6].opcode)
        assertEquals("$what: the state's register", register, (code[6] as OneRegisterInstruction).registerA)
        assertEquals("$what: the bind's own code follows", stock.kinds(), code.drop(7).kinds())
    }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findTagIndicatorBind() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private fun BytecodePatchContext.bind(): Method =
        mutableClassDefBy(TagIndicatorFixture.BINDER).methods.single { it.name == "bind" }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.calls(method: String) = (this as? ReferenceInstruction)?.reference?.toString() == method

    /** Opcodes with payload alignment nops left out and every goto read as one, the way an insert can widen them. */
    private fun List<Instruction>.kinds() = filter { it.opcode != Opcode.NOP }.map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
    }
}

/** A stand-in for 456's indicator kinds, its state and its bind, shared with FeedAudioIconPatchTest. */
internal object TagIndicatorFixture {
    const val KINDS = "Lfixture/IndicatorKind;"
    const val STATE = "Lfixture/IndicatorState;"
    const val HOLDER = "Lfixture/IndicatorHolder;"
    const val BINDER = "Lfixture/IndicatorBinder;"
    const val HOLDER_READER = "Lfixture/HolderReader;"
    const val NO_LISTENER = "Lfixture/NoListener;"
    private const val OBJECT = "Ljava/lang/Object;"
    private const val LISTENER = "Landroid/view/View\$OnClickListener;"

    private fun build(owner: String, name: String, parameters: List<String>, flags: Int, registers: Int, smali: String): Method {
        val method = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(0, smali.trimIndent())
        return ImmutableMethod.of(method)
    }

    private fun kinds(people: Boolean): ClassDef {
        val names = listOf("NONE", if (people) "PEOPLE" else "PERSONS", "FB_USER", "PRODUCTS", INDICATOR_KIND_NAME)
        val initializer = names.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void"
        val methods = listOf(build(KINDS, "<clinit>", emptyList(), AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 1, initializer))
        return ImmutableClassDef(KINDS, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value,
            "Ljava/lang/Enum;", null, null, null, null, methods)
    }

    /** Shaped like 456's 03z8.A00: a null state takes its own path, and the holder keeps the last kind. */
    private fun binder(type: String, nullTest: Boolean): ClassDef {
        val test = if (nullTest) "if-nez p2, :bound" else "goto :bound"
        val bind = """
            iget-object v0, p3, $HOLDER->last:$KINDS
            $test
            const/4 v1, 0x0
            iput-object v1, p3, $HOLDER->last:$KINDS
            return-void
            :bound
            iget-object v1, p2, $STATE->kind:$KINDS
            iput-object v1, p3, $HOLDER->last:$KINDS
            return-void
        """
        val methods = listOf(build(type, "bind", listOf(LISTENER, STATE, HOLDER), AccessFlags.PUBLIC.value, 7, bind))
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, methods)
    }

    /** Reads the holder's kind with a listener at hand, but never tests the holder for null. */
    private fun holderReader(): ClassDef {
        val read = """
            iget-object v0, p2, $HOLDER->last:$KINDS
            return-void
        """
        val methods = listOf(build(HOLDER_READER, "read", listOf(LISTENER, HOLDER), AccessFlags.PUBLIC.value, 4, read))
        return ImmutableClassDef(HOLDER_READER, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, methods)
    }

    /** Tests a state for null and reads its kind, with no listener: not a bind of the indicator. */
    private fun noListener(): ClassDef {
        val read = """
            if-eqz p1, :none
            iget-object v0, p1, $STATE->kind:$KINDS
            :none
            return-void
        """
        val methods = listOf(build(NO_LISTENER, "read", listOf(STATE), AccessFlags.PUBLIC.value, 3, read))
        return ImmutableClassDef(NO_LISTENER, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, methods)
    }

    fun classes(kinds: Boolean = true, people: Boolean = true, nullTest: Boolean = true, second: Boolean = false): List<ClassDef> =
        (if (kinds) listOf(kinds(people)) else emptyList()) +
            binder(BINDER, nullTest) + holderReader() + noListener() +
            (if (second) listOf(binder("Lfixture/SecondBinder;", true)) else emptyList())

    /**
     * The fixture sliced the way the finder reads it: the classes loading the kind name with the
     * string pools they call, and every class reading a field of the kinds enum.
     */
    fun slice(bundle: File): List<ClassDef> {
        val holders = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, INDICATOR_KIND_NAME))
        val kind = PatchContexts.of(holders).tagIndicatorKinds().type
        val have = holders.mapTo(HashSet()) { it.type }
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it == kind }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.type in have) continue
                val reads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == kind
                    } == true
                }
                if (reads) {
                    readers += ImmutableClassDef.of(classDef)
                    have += classDef.type
                }
            }
        }
        return holders + readers
    }
}
