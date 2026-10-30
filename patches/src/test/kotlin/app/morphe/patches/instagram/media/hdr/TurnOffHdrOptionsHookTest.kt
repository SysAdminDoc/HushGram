/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.hdr

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.LongEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableArrayPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Instagram's boolean option read asks HdrBoost first, with the specifier and the table indexes of
 * "SDR super white on HDR content" and "Ultra HDR always on", and answers no when HdrBoost says so (#85).
 */
class TurnOffHdrOptionsHookTest {
    private val params = "Lfixture/Params;"
    private val reader = "Lfixture/OptionReader;"

    /**
     * An option table of 1200 ids in two arrays, the super white option at 7 and Ultra HDR always on
     * at 1100, the second array's hundredth.
     */
    private fun table(type: String = "Lfixture/Options;"): ClassDef {
        val ids = tableIds()
        return initializerListing(type, ids.take(1000), ids.drop(1000))
    }

    private fun tableIds(): List<Int> {
        val ids = (0 until 1200).map { 200000 + it }.toMutableList()
        ids[7] = SUPER_WHITE_OPTION
        ids[1100] = ULTRA_HDR_OPTION
        return ids
    }

    /**
     * The same table as the ARMv7 builds lay it out: the initializer calls a same-class helper that
     * fills and answers the first 1000 ids, then fills the last 200 itself, so its own array is too
     * short to be the table and comes second.
     */
    private fun helperTable(type: String = "Lfixture/Options;"): ClassDef {
        val ids = tableIds()
        fun static(name: String, returnType: String, code: List<Instruction>) = ImmutableMethod(
            type, name, emptyList(), returnType,
            AccessFlags.STATIC.value or (if (name == "<clinit>") AccessFlags.CONSTRUCTOR.value else 0), null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
        val helper = ImmutableMethodReference(type, "A01", emptyList<String>(), "[I")
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                static(
                    "A01", "[I",
                    listOf(
                        ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 4),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ImmutableArrayPayload(4, ids.take(1000)),
                    ),
                ),
                static(
                    "<clinit>", "V",
                    listOf(
                        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, helper),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                        ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 4),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ImmutableArrayPayload(4, ids.drop(1000)),
                    ),
                ),
            ),
        )
    }

    private fun initializerListing(type: String, vararg arrays: List<Int>): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(
                    1,
                    listOf<Instruction>(ImmutableInstruction10x(Opcode.RETURN_VOID)) + arrays.map { ImmutableArrayPayload(4, it) },
                    null, null,
                ),
            ),
        ),
    )

    private fun method(name: String, parameters: List<String>, returnType: String, registers: Int, code: List<Instruction>) =
        ImmutableMethod(
            reader, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(registers, code, null, null),
        )

    /**
     * The reader as Instagram has it: a String read holding the marker that takes the reader type,
     * and the boolean read taking that type, the specifier in v4 and v5 and the default in v6, which
     * it answers. A boolean read of another first type sits beside it and stays.
     */
    private fun optionReader(): ClassDef = ImmutableClassDef(
        reader, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            method(
                "name", listOf(params, "Ljava/lang/String;", "J"), "Ljava/lang/String;", 6,
                listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(OPTION_READER_MARKER)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ),
            ),
            method("read", listOf(params, "J", "Z"), "Z", 7, readBody()),
            method("readOther", listOf("Lfixture/Other;", "J", "Z"), "Z", 7, readBody()),
        ),
    )

    private fun readBody() = listOf(ImmutableInstruction12x(Opcode.MOVE, 0, 6), ImmutableInstruction11x(Opcode.RETURN, 0))

    @Test
    fun theTableGivesEachOptionsPlace() {
        val context = PatchContexts.of(listOf(initializerListing("Lfixture/Short;", listOf(ULTRA_HDR_OPTION, SUPER_WHITE_OPTION)), table()))
        assertEquals("a short list of the same ids isn't the table", listOf(7, 1100), context.optionIndexes(SUPER_WHITE_OPTION, ULTRA_HDR_OPTION))
        assertEquals(listOf(-1), PatchContexts.of(listOf(table())).optionIndexes(68886))
    }

    /** On the ARMv7 builds the helper's ids come first, then the ones the initializer fills. */
    @Test
    fun aHelpersIdsComeFirst() {
        assertEquals(listOf(7, 1100), PatchContexts.of(listOf(helperTable())).optionIndexes(SUPER_WHITE_OPTION, ULTRA_HDR_OPTION))
    }

    @Test
    fun theBooleanReadAsksHdrBoostFirst() {
        val context = PatchContexts.of(listOf(table(), optionReader()))

        val read = context.holdHdrOptionsOff()

        assertEquals("read", read?.name)
        val code = context.mutableClassDefBy(reader).methods.single { it.name == "read" }.instructions()
        assertEquals(
            listOf(Opcode.CONST, Opcode.CONST, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN, Opcode.MOVE, Opcode.RETURN),
            code.map { it.opcode },
        )
        assertEquals(7, (code[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(1100, (code[1] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(OPTION_OFF, (code[2] as ReferenceInstruction).reference.toString())
        assertEquals("the specifier, then the two indexes", listOf(4, 5, 0, 1), code[2].registers())
        assertEquals("answers no", 0, (code[5] as NarrowLiteralInstruction).narrowLiteral)
        val other = context.mutableClassDefBy(reader).methods.single { it.name == "readOther" }.instructions()
        assertEquals("a read of another type stays", listOf(Opcode.MOVE, Opcode.RETURN), other.map { it.opcode })
    }

    /** Without the table or the read the rest of the patch still works, so the hold steps aside. */
    @Test
    fun withoutTheTableOrTheReadNothingChanges() {
        val noTable = PatchContexts.of(listOf(optionReader()))
        assertNull(noTable.holdHdrOptionsOff())
        assertEquals(2, noTable.mutableClassDefBy(reader).methods.single { it.name == "read" }.instructions().size)

        assertNull(PatchContexts.of(listOf(table())).holdHdrOptionsOff())
    }

    /** The method the hook calls is a public static method of the HdrBoost the bundle ships. */
    @Test
    fun theHookHasItsMethod() {
        val declared = ExtensionDex.classDef(HDR_BOOST).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HDR_BOOST->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("HdrBoost declares no $OPTION_OFF: $declared", OPTION_OFF in declared)
    }

    /**
     * On each declared build, ARMv7 ones too, the table lists both options, a boolean specifier in the build names
     * Ultra HDR always on's index, which shows the index is the one specifiers carry, and the hook
     * goes into the one boolean read, the one at least two of its class's other boolean reads end in.
     */
    @Test
    fun theDeclaredBuildHoldsBothOptions() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                build(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) build(base, base.parentFile.name)
    }

    private fun build(bundle: File, label: String) {
        val kept = mutableListOf<ClassDef>()
        val booleanSpecifiers = HashSet<Int>()
        fun specifier(value: Long) {
            if ((value ushr 48) and 0xffL == 0x81L) booleanSpecifiers += ((value ushr 32) and 0xffffL).toInt()
        }
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                classDef.staticFields.forEach { field -> (field.initialValue as? LongEncodedValue)?.let { specifier(it.value) } }
                var keep = false
                for (method in classDef.methods) {
                    for (instruction in method.instructions()) {
                        when (instruction) {
                            is WideLiteralInstruction -> specifier(instruction.wideLiteral)
                            is ArrayPayload -> if (instruction.elementWidth == 8) instruction.arrayElements.forEach { specifier(it.toLong()) }
                            is ReferenceInstruction -> if ((instruction.reference as? StringReference)?.string == OPTION_READER_MARKER) keep = true
                        }
                    }
                }
                val listed = classDef.methods.sumOf { method -> method.instructions().filterIsInstance<ArrayPayload>().filter { it.elementWidth == 4 }.sumOf { it.arrayElements.size } }
                if (listed >= 1000 && classDef.methods.any { it.name == "<clinit>" }) keep = true
                if (keep) kept += ImmutableClassDef.of(classDef)
            }
        }

        val context = PatchContexts.of(kept)
        val (superWhite, ultraHdr) = context.optionIndexes(SUPER_WHITE_OPTION, ULTRA_HDR_OPTION)
        assertTrue("$label: the table lists $SUPER_WHITE_OPTION", superWhite >= 0)
        assertTrue("$label: the table lists $ULTRA_HDR_OPTION", ultraHdr >= 0)
        assertNotEquals(superWhite, ultraHdr)
        assertTrue("$label: a boolean specifier names index $ultraHdr", ultraHdr in booleanSpecifiers)

        val read = context.holdHdrOptionsOff() ?: throw AssertionError("$label: the hook didn't go in")
        val owner = kept.single { it.type == read.definingClass }
        val callers = owner.methods.filter { method ->
            method.returnType == "Z" && method.instructions().any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.name == read.name && it.definingClass == read.definingClass } == true
            }
        }
        assertTrue("$label: ${read.definingClass}->${read.name} is called by ${callers.size} boolean reads", callers.size >= 2)
        val code = read.instructions()
        assertEquals("$label: the hook", OPTION_OFF, (code[2] as ReferenceInstruction).reference.toString())
        assertEquals("$label: super white index", superWhite, (code[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: Ultra HDR index", ultraHdr, (code[1] as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.registers(): List<Int> = (this as FiveRegisterInstruction).let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    }
}
