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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
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
        val rotates = phone.methods.single { it.name == "rotates" && it.parameterTypes.map(CharSequence::toString) == listOf("I") }
        assertEquals("Z", rotates.returnType)
        for (method in listOf(width, foldable, rotates)) {
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
        val widthCheck = requireNotNull(context.findWidthCheck(width, hinge)) { "no width check" }
        assertEquals("the width check", HELPER to "notCompact", widthCheck.type to widthCheck.name)
        val factoryBefore = context.method(WIDTH, "A00").code().map(::text)
        val checkBefore = context.method(HELPER, "notCompact").code().map(::text)
        val hingeBefore = context.method(HELPER, "hinge").code().map(::text)

        context.keepPhoneLayout(width, widthCheck, hinge)

        val inlined = context.method(HELPER, "notCompact").code()
        assertEquals("width check: the hook comes first", PHONE_WIDTH, inlined[0].referenceText())
        val sortingInlined = inlined[0] as RegisterRangeInstruction
        assertEquals("width check: the hook takes the width (p0 is v1)", 1 to 1, sortingInlined.startRegister to sortingInlined.registerCount)
        assertEquals("width check: the answer", Opcode.MOVE_RESULT, inlined[1].opcode)
        assertEquals("width check: the answer goes back in the width", 1, (inlined[1] as OneRegisterInstruction).registerA)
        assertEquals("width check: the rest stays", checkBefore, inlined.drop(2).map(::text))
        assertEquals("the helper's other static stays", helper().methods.single { it.name == "sorts" }.code().map(::text),
            context.method(HELPER, "sorts").code().map(::text))

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

    @Test
    fun aMissingOrDoubledWidthCheckFailsThePatch() {
        refuses("found 0", listOf(widthClass(), helper(widthChecks = 0, throughFactory = false)))
        refuses("found 2", listOf(widthClass(), helper(widthChecks = 2)))
        refuses("jumps to its first instruction", listOf(widthClass(), helper(checkJump = true)))
    }

    /** 454's builds 386014704 and 386014705 keep the check as a call to the factory: its hook covers them. */
    @Test
    fun aCheckCallingTheFactoryNeedsNoHookOfItsOwn() {
        val context = PatchContexts.of(listOf(widthClass(), helper(widthChecks = 0)))
        val width = context.findWidthClass()
        val hinge = context.findHingeCheck(width)
        assertEquals("no copy", null, context.findWidthCheck(width, hinge))
        val sortsBefore = context.method(HELPER, "sorts").code().map(::text)

        context.keepPhoneLayout(width, null, hinge)

        assertEquals("the factory's hook", PHONE_WIDTH, context.method(WIDTH, "A00").code()[0].referenceText())
        assertEquals("the check stays as it was", sortsBefore, context.method(HELPER, "sorts").code().map(::text))
    }

    /** In each declared build: the width class factory, the helper's own width check and its hinge check. */
    @Test
    fun eachDeclaredBuildKeepsThePhoneLayout() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                assertTrue("${bundle.name}: the helper has its own width check", builds(bundle, bundle.name))
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

    /**
     * The enums naming MEDIUM and every class loading the hinge feature, the helper among them. Says
     * whether the build's helper has its own copy of the width check.
     */
    private fun builds(bundle: File, label: String): Boolean {
        val enums = FixtureDex.classesLoading(bundle, MEDIUM).filter { it.superclass == "Ljava/lang/Enum;" }
        val hinges = FixtureDex.classesLoading(bundle, HINGE_FEATURE)
        turns(bundle, label, enums, hinges)
        val context = PatchContexts.of((enums + hinges).distinctBy { it.type })
        val width = context.findWidthClass()
        val hinge = context.findHingeCheck(width)
        val widthCheck = context.findWidthCheck(width, hinge)
        val factoryBefore = context.method(width.type, width.factory).code().map(::text)
        val checkBefore = widthCheck?.let { context.method(it.type, it.name).code().map(::text) }
        val hingeBefore = context.method(hinge.type, hinge.name).code().map(::text)

        context.keepPhoneLayout(width, widthCheck, hinge)

        val factory = context.method(width.type, width.factory).code()
        assertEquals("$label: the width hook first", PHONE_WIDTH, factory[0].referenceText())
        assertEquals("$label: the factory's rest stays", factoryBefore, factory.drop(2).map(::text))
        if (widthCheck != null) {
            val inlined = context.method(widthCheck.type, widthCheck.name).code()
            assertEquals("$label: the width check's hook first", PHONE_WIDTH, inlined[0].referenceText())
            assertEquals("$label: the width check's rest stays", checkBefore, inlined.drop(2).map(::text))
        }
        val check = context.method(hinge.type, hinge.name).code()
        assertEquals("$label: the hinge hook after the move-result", NOT_FOLDABLE, check[hinge.at + 1].referenceText())
        assertEquals("$label: one hinge hook", 1, check.count { it.referenceText() == NOT_FOLDABLE })
        assertEquals("$label: the hinge check's rest stays", hingeBefore,
            check.filterIndexed { at, _ -> at != hinge.at + 1 && at != hinge.at + 2 }.map(::text))
        return widthCheck != null
    }

    /**
     * Where the screen check picks an orientation, its answer goes to the extension: in the chooser that
     * posts a Runnable calling the orientation helper, and in a screen calling the helper itself (#700,
     * #714). A layout's call and a call whose answer is dropped stay as they were.
     */
    @Test
    fun theScreenCheckHandsItsAnswerOnWhereItPicksAnOrientation() {
        val context = PatchContexts.of(turning())
        val check = context.findScreenCheck(context.findHingeCheck(context.findWidthClass()))
        assertEquals("the screen check", HELPER to "screen", check.type to check.name)
        val sites = context.findOrientationSites(check)
        assertEquals("the sites", setOf(CHOOSER to 1, MODAL to 1),
            sites.mapTo(HashSet()) { it.type to it.at })
        assertEquals("the answers' registers", mapOf(CHOOSER to 0, MODAL to 1), sites.associate { it.type to it.register })
        val untouched = listOf(LAYOUT to "sort", MODAL to "onResume", TURN to "run", ORIENTATION to "set")
            .associateWith { (type, name) -> context.method(type, name).code().map(::text) }
        val chooserBefore = context.method(CHOOSER, "choose").code().map(::text)
        val modalBefore = context.method(MODAL, "onCreate").code().map(::text)

        context.letBigScreensTurn(sites)

        val chooser = context.method(CHOOSER, "choose").code()
        assertEquals("chooser: the hook after the move-result", ROTATES, chooser[2].referenceText())
        val call = chooser[2] as RegisterRangeInstruction
        assertEquals("chooser: the hook takes the answer", 0 to 1, call.startRegister to call.registerCount)
        assertEquals("chooser: the answer", Opcode.MOVE_RESULT, chooser[3].opcode)
        assertEquals("chooser: back in the same register", 0, (chooser[3] as OneRegisterInstruction).registerA)
        assertEquals("chooser: the test stays right after the hook", Opcode.IF_EQZ, chooser[4].opcode)
        assertEquals("chooser: a phone still skips the Runnable", 6, landsOn(chooser, 4))
        assertEquals("chooser: the rest stays", chooserBefore, chooser.filterIndexed { at, _ -> at != 2 && at != 3 }.map(::text))
        val modal = context.method(MODAL, "onCreate").code()
        assertEquals("modal: the hook after the move-result", ROTATES, modal[2].referenceText())
        assertEquals("modal: the hook takes v1", 1, (modal[2] as RegisterRangeInstruction).startRegister)
        assertEquals("modal: back in v1", 1, (modal[3] as OneRegisterInstruction).registerA)
        assertEquals("modal: the rest stays", modalBefore, modal.filterIndexed { at, _ -> at != 2 && at != 3 }.map(::text))
        for ((where, before) in untouched) {
            assertEquals("$where stays", before, context.method(where.first, where.second).code().map(::text))
        }
    }

    @Test
    fun aScreenCheckOfAnotherShapeFailsThePatch() {
        refusesTurning("sorting a screen's size in $HELPER, found 0", turning(screens = 0))
        refusesTurning("sorting a screen's size in $HELPER, found 2", turning(screens = 2))
        refusesTurning("found nowhere", turning(sites = false))
        refusesTurning("jumps onto the instruction after its screen check", turning(jump = true))
    }

    /** The width hooks change none of the code the orientation finder reads, so both find what they did alone. */
    @Test
    fun theOrientationSitesHoldAfterTheLayoutHooks() {
        val context = PatchContexts.of(turning())
        val width = context.findWidthClass()
        val hinge = context.findHingeCheck(width)
        val sites = context.findOrientationSites(context.findScreenCheck(hinge))
        context.keepPhoneLayout(width, context.findWidthCheck(width, hinge), hinge)
        context.letBigScreensTurn(sites)
        assertEquals(ROTATES, context.method(CHOOSER, "choose").code()[2].referenceText())
        assertEquals(PHONE_WIDTH, context.method(WIDTH, "A00").code()[0].referenceText())
    }

    /**
     * On a real build: the main activity's chooser and every other screen picking an orientation from the
     * screen check hand its answer on, and the check's layout callers keep theirs.
     */
    private fun turns(bundle: File, label: String, enums: List<ClassDef>, hinges: List<ClassDef>) {
        val sorting = PatchContexts.of((enums + hinges).distinctBy { it.type })
        val check = sorting.findScreenCheck(sorting.findHingeCheck(sorting.findWidthClass()))
        val callers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.methodSection.none { it.name == SET_ORIENTATION || (it.definingClass == check.type && it.name == check.name) ||
                    (it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY, "I")) }) return@forEach
            for (classDef in dex.classes) {
                val wanted = classDef.methods.any { method ->
                    method.code().any { instruction ->
                        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
                        called.name == SET_ORIENTATION || (called.definingClass == check.type && called.name == check.name) ||
                            (method.name == "run" && called.returnType == "V" &&
                                called.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY, "I"))
                    }
                }
                if (wanted) callers += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of((enums + hinges + callers).distinctBy { it.type })
        val sites = context.findOrientationSites(context.findScreenCheck(context.findHingeCheck(context.findWidthClass())))
        assertTrue("$label: the main activity's chooser picks an orientation",
            sites.any { it.parameters == listOf(ACTIVITY, "I", "I", "Z") && it.returnType == "V" })
        assertTrue("$label: a screen picking its own orientation, found ${sites.size} sites", sites.size >= 2)
        val methods = sites.map { Triple(it.type, it.name, it.parameters) }.toSet()
        val before = methods.associateWith { (type, name, parameters) -> context.method(type, name, parameters).code().map(::text) }
        val layouts = callers.flatMap { classDef ->
            classDef.methods.filter { method -> method.code().any { it.calls(check) } }
                .map { Triple(classDef.type, it.name, it.parameterTypes.map(CharSequence::toString)) }
        }.filter { it !in methods }
        assertTrue("$label: a layout asks the screen check too", layouts.isNotEmpty())

        context.letBigScreensTurn(sites)

        for ((where, stock) in before) {
            val mine = sites.filter { Triple(it.type, it.name, it.parameters) == where }.sortedBy { it.at }
            val code = context.method(where.first, where.second, where.third).code()
            for ((n, site) in mine.withIndex()) {
                val at = site.at + 1 + 2 * n
                assertEquals("$label ${where.second}: the hook after the move-result", ROTATES, code[at].referenceText())
                assertEquals("$label ${where.second}: the hook takes the answer", site.register, (code[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$label ${where.second}: back in the same register", site.register, (code[at + 1] as OneRegisterInstruction).registerA)
            }
            assertEquals("$label ${where.second}: the rest stays", stock, dropHookResults(code).map(::text))
        }
        for ((type, name, parameters) in layouts) {
            assertTrue("$label: $type->$name keeps the phone answer",
                context.method(type, name, parameters).code().none { it.referenceText() == ROTATES })
        }
    }

    /** [code] without each hook call and the move-result right after it. */
    private fun dropHookResults(code: List<Instruction>): List<Instruction> {
        val hooks = code.indices.filter { code[it].referenceText() == ROTATES }.toSet()
        return code.filterIndexed { at, _ -> at !in hooks && (at - 1) !in hooks }
    }

    private fun Instruction.calls(check: ScreenCheck): Boolean {
        val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return called.definingClass == check.type && called.name == check.name
    }

    /** The patch refuses the orientation hook for the reason given, and nothing has changed. */
    private fun refusesTurning(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) {
            context.letBigScreensTurn(context.findOrientationSites(context.findScreenCheck(context.findHingeCheck(context.findWidthClass()))))
        }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) {
            val width = context.findWidthClass()
            val hinge = context.findHingeCheck(width)
            context.keepPhoneLayout(width, context.findWidthCheck(width, hinge), hinge)
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

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>): Method =
        mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters }

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
        const val ORIENTATION = "Lfixture/FixedOrientationCompat;"
        const val TURN = "Lfixture/TurnRunnable;"
        const val CHOOSER = "Lfixture/OrientationChooser;"
        const val MODAL = "Lfixture/ModalActivity;"
        const val LAYOUT = "Lfixture/RailLayout;"
        const val ACTIVITY = "Landroid/app/Activity;"
        const val SET_ORIENTATION = "setRequestedOrientation"
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
         * [checks] instance hinge checks reading hasSystemFeature into v0 and testing it, with
         * [widthChecks] static `(I)Z` checks carrying the factory's comparisons. Without [kept] the
         * answer is left unread; with [jump], a goto after the returns lands right after the
         * move-result; with [checkJump], the width check's first test jumps back to its start. Without
         * [throughFactory] the static sorting through the factory takes a second int, so it's no check.
         */
        fun helper(
            checks: Int = 1,
            kept: Boolean = true,
            jump: Boolean = false,
            widthChecks: Int = 1,
            checkJump: Boolean = false,
            throughFactory: Boolean = true,
            screens: Int = 1,
        ): ClassDef {
            val takes = List(if (throughFactory) 1 else 2) { ImmutableMethodParameter("I", null, null) }
            val sorts = ImmutableMethod(HELPER, "sorts", takes, "Z", static, null, null,
                ImmutableMethodImplementation(1 + takes.size, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0,
                        ImmutableMethodReference(WIDTH, "A00", listOf("I"), WIDTH)),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ), null, null))
            val hinges = List(checks) { n -> hingeCheck(HELPER, if (n == 0) "hinge" else "hinge$n", kept, jump) }
            val inlined = List(widthChecks) { n -> widthCheck(if (n == 0) "notCompact" else "notCompact$n", checkJump) }
            val screenChecks = List(screens) { n -> screenCheck(if (n == 0) "screen" else "screen$n") }
            return ImmutableClassDef(HELPER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(sorts) + hinges + inlined + sizes() + screenChecks + splitScreen() + laterTest())
        }

        /** The helper's sorting of a size in pixels at a density. Its answer doesn't matter here. */
        fun sizes(): Method = ImmutableMethod(HELPER, "sizes", listOf("F", "I", "I").map { ImmutableMethodParameter(it, null, null) },
            "Z", static, null, null, ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ), null, null))

        /** The screen check: the activity's largest window handed to [sizes]. Four registers, so the activity is v3. */
        fun screenCheck(name: String): Method = ImmutableMethod(HELPER, name, listOf(ImmutableMethodParameter(ACTIVITY, null, null)),
            "Z", static, null, null, ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 0, 1, 2, 0, 0, ImmutableMethodReference(HELPER, "sizes", listOf("F", "I", "I"), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ), null, null))

        /**
         * Shaped like 457's third Activity check: it hands the window to [sizes] too, but answers from a
         * later test of its own, so it's no screen check.
         */
        fun laterTest(): Method = ImmutableMethod(HELPER, "laterTest", listOf(ImmutableMethodParameter(ACTIVITY, null, null)),
            "Z", static, null, null, ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 0, 1, 2, 0, 0, ImmutableMethodReference(HELPER, "sizes", listOf("F", "I", "I"), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 1),
                ImmutableInstruction11x(Opcode.RETURN, 1),
            ), null, null))

        /** The helper's other Activity check, asking only about split screen. */
        fun splitScreen(): Method = ImmutableMethod(HELPER, "splitScreen", listOf(ImmutableMethodParameter(ACTIVITY, null, null)),
            "Z", static, null, null, ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ), null, null))

        private val screen = ImmutableMethodReference(HELPER, "screen", listOf(ACTIVITY), "Z")
        private val turn = ImmutableMethodReference(ORIENTATION, "set", listOf(ACTIVITY, "I"), "V")

        /**
         * The width class and the helper with [screens] screen checks, the orientation helper, the Runnable
         * calling it and a layout asking the screen check. With [sites], the chooser posting the Runnable and
         * a modal screen calling the helper itself, whose onResume drops the check's answer; with [jump], a
         * goto in the chooser lands right after its check's move-result.
         */
        fun turning(screens: Int = 1, sites: Boolean = true, jump: Boolean = false): List<ClassDef> =
            listOf(widthClass(), helper(screens = screens), orientationCompat(), turnRunnable(), layout()) +
                if (sites) listOf(chooser(jump), modal()) else emptyList()

        /** Instagram's orientation helper: a static `(Activity, int)` handing the int to setRequestedOrientation. */
        fun orientationCompat(): ClassDef = ImmutableClassDef(ORIENTATION, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod(ORIENTATION, "set", listOf(ACTIVITY, "I").map { ImmutableMethodParameter(it, null, null) }, "V", static,
                null, null, ImmutableMethodImplementation(2, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
                        ImmutableMethodReference(ACTIVITY, SET_ORIENTATION, listOf("I"), "V")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), null, null))))

        /** The Runnable the chooser posts, calling the orientation helper. */
        fun turnRunnable(): ClassDef = ImmutableClassDef(TURN, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf("Ljava/lang/Runnable;"),
            null, null, null, listOf(ImmutableMethod(TURN, "run", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(3, listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11n(Opcode.CONST_4, 1, 1),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, turn),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), null, null))))

        /** Addresses 0, 3, 4, 6, 8, and 9 for the goto. Six registers, so the activity is v2 and the answer v0. */
        fun chooser(jump: Boolean): ClassDef {
            val code = mutableListOf<ImmutableInstruction>(
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, screen),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(TURN)),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            if (jump) code += ImmutableInstruction10t(Opcode.GOTO, -5)
            return ImmutableClassDef(CHOOSER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(ImmutableMethod(CHOOSER, "choose", listOf(ACTIVITY, "I", "I", "Z").map { ImmutableMethodParameter(it, null, null) },
                    "V", static, null, null, ImmutableMethodImplementation(6, code, null, null))))
        }

        /** A screen picking its own orientation in onCreate, with the answer in v1, and an onResume dropping the answer. */
        fun modal(): ClassDef {
            val onCreate = ImmutableMethod(MODAL, "onCreate", listOf(ImmutableMethodParameter("Landroid/os/Bundle;", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, screen),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 0, 0, 0, 0, turn),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), null, null))
            val onResume = ImmutableMethod(MODAL, "onResume", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(3, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, screen),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 0, 0, 0, 0, turn),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), null, null))
            return ImmutableClassDef(MODAL, AccessFlags.PUBLIC.value, ACTIVITY, null, null, null, null, listOf(onCreate, onResume))
        }

        /** A layout asking the screen check, which keeps the phone answer. */
        fun layout(): ClassDef = ImmutableClassDef(LAYOUT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod(LAYOUT, "sort", listOf(ImmutableMethodParameter(ACTIVITY, null, null)), "Z", static, null, null,
                ImmutableMethodImplementation(1, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, screen),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ), null, null))))

        /** The factory's comparisons with a boolean answer, as Redex built them into the helper. Two registers, so the width is v1. */
        fun widthCheck(name: String, jump: Boolean): Method =
            ImmutableMethod(HELPER, name, listOf(ImmutableMethodParameter("I", null, null)), "Z", static, null, null,
                ImmutableMethodImplementation(2, listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction22t(Opcode.IF_LT, 1, 0, if (jump) -1 else 10),
                    ImmutableInstruction21s(Opcode.CONST_16, 0, MEDIUM_WIDTH),
                    ImmutableInstruction22t(Opcode.IF_LT, 1, 0, 6),
                    ImmutableInstruction21s(Opcode.CONST_16, 0, EXPANDED_WIDTH),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ), null, null))

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
