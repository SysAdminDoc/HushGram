/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.phonelayout

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phone layout on big screens: the width class factory hands its width to the extension first
 * thing, and the hinge check hands on what hasSystemFeature answered right after it's moved out.
 * Anything the patch can't tell apart fails it before an instruction changes.
 */
class PhoneLayoutHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val phone = ExtensionDex.classDef(PHONE_LAYOUT)
        val width = phone.methods.single { it.name == "width" && it.parameterTypes.map(CharSequence::toString) == listOf("I") }
        assertEquals("I", width.returnType)
        val foldable = phone.methods.single { it.name == "foldable" && it.parameterTypes.map(CharSequence::toString) == listOf("I") }
        assertEquals("Z", foldable.returnType)
        for (method in listOf(width, foldable)) {
            assertTrue(method.name, AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }

    @Test
    fun bothChecksHandTheirAnswersOn() {
        val context = PatchContexts.of(listOf(widthClass(), decoyEnum(), helper(), camera()))
        val width = context.findWidthClass()
        assertEquals("the width class", WIDTH to "A00", width.type to width.factory)
        val hinge = context.findHingeCheck(width)
        assertEquals("the hinge check", HELPER to "hinge", hinge.type to hinge.name)
        assertEquals("its answer", 4 to 0, hinge.at to hinge.register)
        val factoryBefore = context.method(WIDTH, "A00").code().map(::text)
        val hingeBefore = context.method(HELPER, "hinge").code().map(::text)

        context.keepPhoneLayout(width, hinge)

        val factory = context.method(WIDTH, "A00").code()
        assertEquals("factory: the hook comes first", PHONE_WIDTH, factory[0].referenceText())
        val sorting = factory[0] as RegisterRangeInstruction
        assertEquals("factory: the hook takes the width (p0 is v1)", 1 to 1, sorting.startRegister to sorting.registerCount)
        assertEquals("factory: the answer", Opcode.MOVE_RESULT, factory[1].opcode)
        assertEquals("factory: the answer goes back in the width", 1, (factory[1] as OneRegisterInstruction).registerA)
        assertEquals("factory: the rest stays", factoryBefore, factory.drop(2).map(::text))
        assertEquals("factory: a narrow width still lands on COMPACT", 12, landsOn(factory, 3))

        val check = context.method(HELPER, "hinge").code()
        assertEquals("hinge: the hook after the move-result", NOT_FOLDABLE, check[5].referenceText())
        val call = check[5] as RegisterRangeInstruction
        assertEquals("hinge: the hook takes the answer", 0 to 1, call.startRegister to call.registerCount)
        assertEquals("hinge: the answer", Opcode.MOVE_RESULT, check[6].opcode)
        assertEquals("hinge: back in the same register", 0, (check[6] as OneRegisterInstruction).registerA)
        assertEquals("hinge: the test stays right after the hook", Opcode.IF_EQZ, check[7].opcode)
        assertEquals("hinge: no hinge still answers false", 10, landsOn(check, 7))
        assertEquals("hinge: the rest stays", hingeBefore, check.filterIndexed { at, _ -> at != 5 && at != 6 }.map(::text))
        assertEquals("the camera's own hinge check stays", camera().methods.single().code().map(::text),
            context.method(CAMERA, "hinge").code().map(::text))
    }

    @Test
    fun aMissingOrDoubledWidthClassFailsThePatch() {
        refuses("found 0", listOf(widthClass(names = listOf(COMPACT, EXPANDED)), helper()))
        refuses("found 0", listOf(widthClass(enum = false), helper()))
        refuses("found 0", listOf(widthClass(literals = listOf(600, 900)), helper()))
        refuses("found 2", listOf(widthClass(), widthClass(type = "Lfixture/OtherWidth;"), helper()))
        refuses("found 2", listOf(widthClass(factories = 2), helper()))
    }

    @Test
    fun aJumpToTheFactoryStartFailsThePatch() {
        refuses("jumps to its first instruction", listOf(widthClass(jump = true), helper()))
    }

    @Test
    fun aHingeCheckOfAnotherShapeFailsThePatch() {
        refuses("found 0", listOf(widthClass(), helper(checks = 0), camera()))
        refuses("found 2", listOf(widthClass(), helper(checks = 2)))
        refuses("drops what hasSystemFeature answers", listOf(widthClass(), helper(kept = false)))
        refuses("jumps onto the instruction after its hinge check", listOf(widthClass(), helper(jump = true)))
    }

    /** In each declared build: the one width class factory and the big screen helper's hinge check. */
    @Test
    fun eachDeclaredBuildKeepsThePhoneLayout() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildKeepsThePhoneLayoutToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** The enums naming MEDIUM and every class loading the hinge feature, the helper among them. */
    private fun builds(bundle: File, label: String) {
        val enums = FixtureDex.classesLoading(bundle, MEDIUM).filter { it.superclass == "Ljava/lang/Enum;" }
        val context = PatchContexts.of((enums + FixtureDex.classesLoading(bundle, HINGE_FEATURE)).distinctBy { it.type })
        val width = context.findWidthClass()
        val hinge = context.findHingeCheck(width)
        val factoryBefore = context.method(width.type, width.factory).code().map(::text)
        val hingeBefore = context.method(hinge.type, hinge.name).code().map(::text)

        context.keepPhoneLayout(width, hinge)

        val factory = context.method(width.type, width.factory).code()
        assertEquals("$label: the width hook first", PHONE_WIDTH, factory[0].referenceText())
        assertEquals("$label: the factory's rest stays", factoryBefore, factory.drop(2).map(::text))
        val check = context.method(hinge.type, hinge.name).code()
        assertEquals("$label: the hinge hook after the move-result", NOT_FOLDABLE, check[hinge.at + 1].referenceText())
        assertEquals("$label: one hinge hook", 1, check.count { it.referenceText() == NOT_FOLDABLE })
        assertEquals("$label: the hinge check's rest stays", hingeBefore,
            check.filterIndexed { at, _ -> at != hinge.at + 1 && at != hinge.at + 2 }.map(::text))
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) {
            val width = context.findWidthClass()
            context.keepPhoneLayout(width, context.findHingeCheck(width))
        }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The index of the instruction the branch at [at] lands on. */
    private fun landsOn(code: List<Instruction>, at: Int): Int {
        val offsets = code.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
        return offsets.indexOf(offsets[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method =
        mutableClassDefBy(type).methods.single { it.name == name }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name // opcode-name-ok
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference" // opcode-name-ok
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private companion object {
        const val PHONE_LAYOUT = "Lapp/hushgram/extension/instagram/misc/PhoneLayout;"
        const val WIDTH = "Lfixture/WidthSizeClass;"
        const val HELPER = "Lfixture/LargeScreenUtil;"
        const val CAMERA = "Lfixture/CameraHinge;"
        const val CONTEXT = "Landroid/content/Context;"
        const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"

        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

        /**
         * Shaped like Instagram's window width class: an enum whose static initializer names
         * [names], and [factories] static `(I)` methods sorting a width by [literals]. Two
         * registers, so the width is v1. With [jump], the first test jumps back to the start.
         */
        fun widthClass(
            type: String = WIDTH,
            enum: Boolean = true,
            names: List<String> = listOf(COMPACT, MEDIUM, EXPANDED),
            literals: List<Int> = listOf(MEDIUM_WIDTH, EXPANDED_WIDTH),
            factories: Int = 1,
            jump: Boolean = false,
        ): ClassDef {
            val clinit = ImmutableMethod(type, "<clinit>", emptyList(), "V", static or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(1, names.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                    ImmutableInstruction10x(Opcode.RETURN_VOID), null, null))
            fun value(name: String) = ImmutableFieldReference(type, name, type)
            // Addresses 0, 1, 3, 5, 7, 9, 11, 13, 14, 16, 17, 19.
            val code = listOf<ImmutableInstruction>(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction22t(Opcode.IF_LT, 1, 0, if (jump) -1 else 16),
                ImmutableInstruction21s(Opcode.CONST_16, 0, literals[0]),
                ImmutableInstruction22t(Opcode.IF_LT, 1, 0, 12),
                ImmutableInstruction21s(Opcode.CONST_16, 0, literals[1]),
                ImmutableInstruction22t(Opcode.IF_GE, 1, 0, 5),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, value("medium")),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, value("expanded")),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, value("compact")),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            val parameters = listOf(ImmutableMethodParameter("I", null, null))
            val sorting = List(factories) { n ->
                ImmutableMethod(type, if (n == 0) "A00" else "A0$n", parameters, type, static, null, null,
                    ImmutableMethodImplementation(2, code, null, null))
            }
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.ENUM.value,
                if (enum) "Ljava/lang/Enum;" else "Ljava/lang/Object;", null, null, null, null, listOf(clinit) + sorting)
        }

        /** Another enum naming COMPACT and EXPANDED, as a caption snippet's does, with no factory. */
        fun decoyEnum(): ClassDef {
            val clinit = ImmutableMethod("Lfixture/Snippet;", "<clinit>", emptyList(), "V", static or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(1, listOf("SNIPPET", COMPACT, EXPANDED).map {
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it))
                } + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null))
            return ImmutableClassDef("Lfixture/Snippet;", AccessFlags.PUBLIC.value, "Ljava/lang/Enum;", null, null, null, null, listOf(clinit))
        }

        /**
         * Shaped like Instagram's big screen helper: a static sorting a width through the factory and
         * [checks] instance hinge checks reading hasSystemFeature into v0 and testing it. Without
         * [kept] the answer is left unread; with [jump], a goto after the returns lands right after
         * the move-result.
         */
        fun helper(checks: Int = 1, kept: Boolean = true, jump: Boolean = false): ClassDef {
            val sorts = ImmutableMethod(HELPER, "sorts", listOf(ImmutableMethodParameter("I", null, null)), "Z", static, null, null,
                ImmutableMethodImplementation(2, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0,
                        ImmutableMethodReference(WIDTH, "A00", listOf("I"), WIDTH)),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ), null, null))
            val hinges = List(checks) { n -> hingeCheck(HELPER, if (n == 0) "hinge" else "hinge$n", kept, jump) }
            return ImmutableClassDef(HELPER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(sorts) + hinges)
        }

        /** A camera helper with the same hinge check that never sorts a width. */
        fun camera(): ClassDef = ImmutableClassDef(CAMERA, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(hingeCheck(CAMERA, "hinge", kept = true, jump = false)))

        /** Addresses 0, 3, 4, 6, 9, 10, 12, 13, 14, 15, and 16 for the goto. */
        fun hingeCheck(type: String, name: String, kept: Boolean, jump: Boolean): Method {
            val code = mutableListOf<ImmutableInstruction>(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0,
                    ImmutableMethodReference(CONTEXT, "getPackageManager", emptyList(), PACKAGE_MANAGER)),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(HINGE_FEATURE)),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0,
                    ImmutableMethodReference(PACKAGE_MANAGER, "hasSystemFeature", listOf("Ljava/lang/String;"), "Z")),
                if (kept) ImmutableInstruction11x(Opcode.MOVE_RESULT, 0) else ImmutableInstruction10x(Opcode.NOP),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                ImmutableInstruction11x(Opcode.RETURN, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            )
            if (jump) code += ImmutableInstruction10t(Opcode.GOTO, -6)
            return ImmutableMethod(type, name, listOf(ImmutableMethodParameter(CONTEXT, null, null)), "Z", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(4, code, null, null))
        }
    }
}
