/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposerButtonsHookTest {
    @Test
    fun onlyTheVisibilityDispatcherGetsOneGuardBeforeTheNativeGetter() {
        val classes = listOf(buttonEnum(), lookups(), controller())
        val context = PatchContexts.of(classes)
        val site = context.findComposerButtonVisibility()
        val original = classes.single { it.type == site.type }.methods.single { it.name == site.name }
        context.holdComposerButtons(site)
        val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name }
        assertGuard(patched)
        assertNativeBody(original, patched)
        assertOtherMethods(classes, context, site)
        assertEquals(0, context.mutableClassDefBy(LOOKUP).methods.sumOf { method -> method.code().count { it.referenceText() == COMPOSER_BUTTON } })
        assertEquals(COMPOSER_BUTTON_IDS + ORDINARY_BUTTON, context.mutableClassDefBy(LOOKUP).methods.single().code()
            .mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.filter { it in COMPOSER_BUTTON_IDS || it == ORDINARY_BUTTON })
    }

    @Test
    fun duplicateButtonEnumsRefuseBeforeMutation() = refuses(buttonEnum("Lfixture/OtherButton;"))

    @Test
    fun duplicateOptionalLookupMethodsRefuseBeforeMutation() = refuses(lookups("Lfixture/OtherLookup;"))

    @Test
    fun duplicateVisibilityControllersRefuseBeforeMutation() = refuses(controller("Lfixture/OtherComposer;"))

    @Test
    fun aComposerWithoutTheVoiceLookupRefusesBeforeMutation() {
        refuses(replace = lookups(ids = COMPOSER_BUTTON_IDS.dropLast(1)))
    }

    @Test
    fun aLookupCheckingAnotherRegisterRefusesBeforeMutation() {
        refuses(replace = lookups(testRegister = 1))
    }

    @Test
    fun aLookupReturningTheViewWhenAbsentRefusesBeforeMutation() {
        refuses(replace = lookups(nullReturn = 0))
    }

    @Test
    fun aGetterReceivingAnotherShowFlagRefusesBeforeMutation() {
        refuses(replace = controller(getterFlag = 6))
    }

    @Test
    fun aVisibilityBranchTestingAnotherFlagRefusesBeforeMutation() {
        refuses(replace = controller(showFlag = 6))
    }

    @Test
    fun anAbsentViewThatDoesNotSkipTheSetterRefusesBeforeMutation() {
        refuses(replace = controller(absentTarget = 5))
    }

    @Test
    fun aFalseFlagThatLeavesTheViewInvisibleInsteadOfGoneRefusesBeforeMutation() {
        refuses(replace = controller(hidden = 4))
    }

    @Test
    fun aHideBranchThatRunsTheShowConstantRefusesBeforeMutation() {
        refuses(replace = controller(hideTarget = 4))
    }

    @Test
    fun aLayoutCallbackReceivingAnotherFlagRefusesBeforeMutation() {
        refuses(replace = controller(callbackFlag = 6))
    }

    /** 454's dispatcher, LX/05TH;->HsA on 386014758: a static getter handed `this`, then the view's own setVisibility. */
    @Test
    fun aStaticGetterAndTheViewsOwnSetterGetTheSameGuard() {
        val classes = listOf(buttonEnum(), lookups(), staticController())
        val context = PatchContexts.of(classes)
        val site = context.findComposerButtonVisibility()
        assertEquals(CONTROLLER, site.type)
        val original = classes.single { it.type == site.type }.methods.single { it.name == site.name }
        context.holdComposerButtons(site)
        val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name }
        assertGuard(patched)
        assertNativeBody(original, patched)
        assertOtherMethods(classes, context, site)
    }

    @Test
    fun aStaticGetterNotHandedThisRefusesBeforeMutation() = refuses(replace = staticController(thisRegister = 5))

    @Test
    fun aStaticGetterReceivingAnotherShowFlagRefusesBeforeMutation() = refuses(replace = staticController(getterFlag = 6))

    @Test
    fun aVisibilityRewrittenOnTheWayToTheViewsOwnSetterRefusesBeforeMutation() = refuses(replace = staticController(traceRegister = 1))

    @Test
    fun theViewsOwnSetterOnAnotherViewRefusesBeforeMutation() = refuses(replace = staticController(setterView = 3))

    @Test
    fun anOldAndANewDispatcherTogetherRefuseBeforeMutation() = refuses(staticController("Lfixture/OtherComposer;"))

    @Test
    fun extensionLookupsDoNotDuplicateTheNativeLookup() {
        val context = PatchContexts.of(listOf(buttonEnum(), lookups(), controller(), lookups("Lapp/hushgram/extension/instagram/metaai/Probe;")))
        assertEquals(CONTROLLER, context.findComposerButtonVisibility().type)
    }

    @Test
    fun guardVerificationRejectsMissingDuplicateWrongInputAndWrongOutputMutants() {
        val context = PatchContexts.of(listOf(buttonEnum(), lookups(), controller()))
        val site = context.findComposerButtonVisibility()
        context.holdComposerButtons(site)
        val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name }
        val code = patched.code()
        val guard = code.take(2)
        val reference = (code[0] as ReferenceInstruction).reference as MethodReference
        val wrongInput = listOf(ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 5, 2, reference)) + code.drop(1)
        val wrongOutput = listOf(code[0], ImmutableInstruction11x(Opcode.MOVE_RESULT, 6)) + code.drop(2)
        for (mutant in listOf(code.drop(2), guard + code, wrongInput, wrongOutput)) {
            assertThrows(AssertionError::class.java) {
                assertGuard(method(CONTROLLER, "updateVisibility", PARAMETERS, "V", 8, mutant))
            }
        }
    }

    @Test
    fun bodyVerificationRejectsANativeBranchRedirectedAfterTheGuard() {
        val original = controller().methods.single { it.name == "updateVisibility" }
        val context = PatchContexts.of(listOf(buttonEnum(), lookups(), controller()))
        val site = context.findComposerButtonVisibility()
        context.holdComposerButtons(site)
        val code = context.mutableClassDefBy(site.type).methods.single { it.name == site.name }.code().toMutableList()
        val branch = code[4] as OffsetInstruction
        code[4] = ImmutableInstruction21t(Opcode.IF_EQZ, 2, branch.codeOffset + 1)
        assertThrows(AssertionError::class.java) {
            assertNativeBody(original, method(CONTROLLER, "updateVisibility", PARAMETERS, "V", 8, code))
        }
    }

    @Test
    fun bodyVerificationRejectsAGuardInAnOrdinarySibling() {
        val classes = listOf(buttonEnum(), lookups(), controller())
        val context = PatchContexts.of(classes)
        val site = context.findComposerButtonVisibility()
        context.holdComposerButtons(site)
        val owner = context.mutableClassDefBy(site.type)
        val sibling = owner.methods.single { it.name == "getOptionalView" }
        sibling.addInstructions(0, """
            invoke-static/range { p2 .. p3 }, $COMPOSER_BUTTON
            move-result p3
        """)
        assertThrows(AssertionError::class.java) { assertOtherMethods(classes, context, site) }
    }

    /** The native lookups, null branch, hide branch and layout callback are all required on 449. */
    @Test
    fun eachDeclaredBuildUsesItsNativeComposerHidePath() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            builds(bundle, bundle.name)
            checked++
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildUsesItsNativeComposerHidePathToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                val keep = candidate.methods.any { method ->
                    val code = method.code()
                    (method.name == "<clinit>" && code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                        .containsAll(COMPOSER_BUTTON_NAMES)) ||
                        code.any { (it as? NarrowLiteralInstruction)?.narrowLiteral in COMPOSER_BUTTON_IDS } ||
                        (method.parameterTypes.size == 3 && method.parameterTypes.last().toString() == "Z" &&
                            method.returnType == "V" && (code.firstOrNull() as? ReferenceInstruction)?.reference.let {
                                it is MethodReference && it.returnType == VIEW
                            })
                }
                if (keep) holders += ImmutableClassDef.of(candidate)
            }
        }
        val context = PatchContexts.of(holders)
        val site = context.findComposerButtonVisibility()
        val original = holders.single { it.type == site.type }.methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        context.holdComposerButtons(site)
        val patched = context.mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        assertGuard(patched)
        assertNativeBody(original, patched)
        assertOtherMethods(holders, context, site)
    }

    private fun refuses(extra: ClassDef? = null, replace: ClassDef? = null) {
        val classes = listOf(buttonEnum(), lookups(), controller()).map { if (replace?.type == it.type) replace else it } + listOfNotNull(extra)
        val context = PatchContexts.of(classes)
        assertThrows(PatchException::class.java) { context.findComposerButtonVisibility() }
        for (candidate in classes) assertEquals("${candidate.type} changed before discovery finished", candidate.methods.map { it.code().map { instruction -> instruction.key() } },
            context.mutableClassDefBy(candidate.type).methods.map { it.code().map { instruction -> instruction.key() } })
    }

    private fun assertGuard(method: Method) {
        val code = method.code()
        assertEquals("one guard", 1, code.count { it.referenceText() == COMPOSER_BUTTON })
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals(COMPOSER_BUTTON, code[0].referenceText())
        val first = method.implementation!!.registerCount - 2
        assertEquals(first, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals(2, (code[0] as RegisterRangeInstruction).registerCount)
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(first + 1, (code[1] as OneRegisterInstruction).registerA)
        // 454 made the getter static: LX/05TH;->HsA calls LX/05TH;->A01 first on 386014758.
        assertTrue("native getter follows the guard", code[2].opcode == Opcode.INVOKE_DIRECT || code[2].opcode == Opcode.INVOKE_STATIC)
        assertEquals("native getter follows the guard", VIEW, ((code[2] as ReferenceInstruction).reference as MethodReference).returnType)
    }

    private fun assertNativeBody(original: Method, patched: Method) {
        assertEquals("native register frame", original.implementation!!.registerCount, patched.implementation!!.registerCount)
        assertEquals("native instructions and branch offsets", original.code().map { it.key() }, patched.code().drop(2).map { it.key() })
    }

    private fun assertOtherMethods(classes: List<ClassDef>, context: app.morphe.patcher.patch.BytecodePatchContext, site: ComposerVisibilitySite) {
        for (owner in classes) {
            val patchedOwner = context.mutableClassDefBy(owner.type)
            assertEquals(owner.methods.count(), patchedOwner.methods.size)
            for (original in owner.methods) {
                val patched = patchedOwner.methods.single { it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType }
                if (owner.type == site.type && original.name == site.name && original.parameterTypes.map(CharSequence::toString) == site.parameters) continue
                assertEquals("ordinary sibling ${original.name}", original.code().map { it.key() }, patched.code().map { it.key() })
            }
        }
    }

    private companion object {
        const val BUTTON_ENUM = "Lfixture/ComposerButton;"
        const val LOCATION = "Lfixture/ComposerLocation;"
        const val LOOKUP = "Lfixture/ComposerLookup;"
        const val CONTROLLER = "Lfixture/ComposerController;"
        const val VIEW = "Landroid/view/View;"
        const val ORDINARY_BUTTON = 0x7f0b3812
        val PARAMETERS = listOf(LOCATION, BUTTON_ENUM, "Z")

        fun buttonEnum(type: String = BUTTON_ENUM): ClassDef = clazz(type, listOf(method(type, "<clinit>", emptyList(), "V", 1,
            COMPOSER_BUTTON_NAMES.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } + ImmutableInstruction10x(Opcode.RETURN_VOID),
            static = true)), superclass = "Ljava/lang/Enum;")

        fun lookups(type: String = LOOKUP, ids: List<Int> = COMPOSER_BUTTON_IDS, testRegister: Int = 0, nullReturn: Int = 1): ClassDef {
            val code = mutableListOf<Instruction>()
            for (id in ids + ORDINARY_BUTTON) code += listOf(
                ImmutableInstruction31i(Opcode.CONST, 2, id),
                call(Opcode.INVOKE_VIRTUAL, listOf(3, 2), VIEW, "findViewById", listOf("I"), VIEW),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, testRegister, 0),
                call(Opcode.INVOKE_STATIC, listOf(0), "Lfixture/Wrapper;", "of", listOf(VIEW), "Ljava/lang/Object;"),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, nullReturn)
            for (index in code.indices.filter { code[it].opcode == Opcode.IF_EQZ }) code[index] = ImmutableInstruction21t(Opcode.IF_EQZ, testRegister, offset(code, index, code.lastIndex))
            return clazz(type, listOf(method(type, "invoke", emptyList(), "Ljava/lang/Object;", 4, code)))
        }

        fun controller(type: String = CONTROLLER, getterFlag: Int = 7, showFlag: Int = 7, absentTarget: Int = 9,
                       hidden: Int = 8, hideTarget: Int = 5, callbackFlag: Int = 7): ClassDef {
            val code = mutableListOf<Instruction>(
                call(Opcode.INVOKE_DIRECT, listOf(4, 5, 6, getterFlag), type, "getOptionalView", PARAMETERS, VIEW),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                ImmutableInstruction21t(Opcode.IF_EQZ, 2, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, showFlag, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction31i(Opcode.CONST, 0, 2026),
                call(Opcode.INVOKE_STATIC, listOf(2, 1, 0), "Lfixture/Views;", "visibility", listOf(VIEW, "I", "I"), "V"),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 4, ImmutableFieldReference(type, "callback", "Lfixture/LayoutCallback;")),
                call(Opcode.INVOKE_VIRTUAL, listOf(3, 5, 6, callbackFlag), "Lfixture/LayoutCallback;", "visibilityChanged", PARAMETERS, "V"),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
                ImmutableInstruction21s(Opcode.CONST_16, 1, hidden),
                ImmutableInstruction10t(Opcode.GOTO, 0),
            )
            code[2] = ImmutableInstruction21t(Opcode.IF_EQZ, 2, offset(code, 2, absentTarget))
            code[3] = ImmutableInstruction21t(Opcode.IF_EQZ, showFlag, offset(code, 3, 10))
            code[11] = ImmutableInstruction10t(Opcode.GOTO, offset(code, 11, hideTarget))
            return clazz(type, listOf(
                method(type, "<init>", listOf(VIEW), "V", 4, listOf(call(Opcode.INVOKE_DIRECT, listOf(0, 2, 1), LOOKUP, "<init>", listOf("Ljava/lang/Object;", "I"), "V"), ImmutableInstruction10x(Opcode.RETURN_VOID))),
                method(type, "getOptionalView", PARAMETERS, VIEW, 8, listOf(call(Opcode.INVOKE_INTERFACE, listOf(0), "Lfixture/OptionalView;", "getView", emptyList(), VIEW), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(type, "updateVisibility", PARAMETERS, "V", 8, code),
            ))
        }

        /**
         * 454's dispatcher: a static getter handed `this` among the three, then a trace id and
         * "setVisibility" logged ahead of the view's own setVisibility.
         */
        fun staticController(type: String = CONTROLLER, thisRegister: Int = 4, getterFlag: Int = 7, traceRegister: Int = 0, setterView: Int = 2): ClassDef {
            val getterParameters = listOf(LOCATION, BUTTON_ENUM, type, "Z")
            val code = mutableListOf<Instruction>(
                call(Opcode.INVOKE_STATIC, listOf(5, 6, thisRegister, getterFlag), type, "getOptionalView", getterParameters, VIEW),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                ImmutableInstruction21t(Opcode.IF_EQZ, 2, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 7, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction31i(Opcode.CONST, traceRegister, 2026),
                ImmutableInstruction21c(Opcode.CONST_STRING, 3, ImmutableStringReference("setVisibility")),
                call(Opcode.INVOKE_STATIC, listOf(2, 3, traceRegister), "Lfixture/Trace;", "log", listOf(VIEW, "Ljava/lang/String;", "I"), "V"),
                call(Opcode.INVOKE_VIRTUAL, listOf(setterView, 1), VIEW, "setVisibility", listOf("I"), "V"),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 4, ImmutableFieldReference(type, "callback", "Lfixture/LayoutCallback;")),
                call(Opcode.INVOKE_VIRTUAL, listOf(3, 5, 6, 7), "Lfixture/LayoutCallback;", "visibilityChanged", PARAMETERS, "V"),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
                ImmutableInstruction21s(Opcode.CONST_16, 1, 8),
                ImmutableInstruction10t(Opcode.GOTO, 0),
            )
            code[2] = ImmutableInstruction21t(Opcode.IF_EQZ, 2, offset(code, 2, 11))
            code[3] = ImmutableInstruction21t(Opcode.IF_EQZ, 7, offset(code, 3, 12))
            code[13] = ImmutableInstruction10t(Opcode.GOTO, offset(code, 13, 5))
            return clazz(type, listOf(
                method(type, "<init>", listOf(VIEW), "V", 4, listOf(call(Opcode.INVOKE_DIRECT, listOf(0, 2, 1), LOOKUP, "<init>", listOf("Ljava/lang/Object;", "I"), "V"), ImmutableInstruction10x(Opcode.RETURN_VOID))),
                method(type, "getOptionalView", getterParameters, VIEW, 4, listOf(call(Opcode.INVOKE_INTERFACE, listOf(0), "Lfixture/OptionalView;", "getView", emptyList(), VIEW), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), static = true),
                method(type, "updateVisibility", PARAMETERS, "V", 8, code),
            ))
        }

        fun call(opcode: Opcode, registers: List<Int>, owner: String, name: String, parameters: List<String>, returns: String): Instruction {
            val r = registers + List(5 - registers.size) { 0 }
            return ImmutableInstruction35c(opcode, registers.size, r[0], r[1], r[2], r[3], r[4], ImmutableMethodReference(owner, name, parameters, returns))
        }

        fun offset(code: List<Instruction>, from: Int, to: Int): Int = code.take(to).sumOf { it.codeUnits } - code.take(from).sumOf { it.codeUnits }
        fun clazz(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;"): ClassDef =
            ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods)
        fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, static: Boolean = false): Method =
            ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, null, null, ImmutableMethodImplementation(registers, code, null, null))
        fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
        fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
        fun Instruction.key(): List<Any?> = listOf(opcode, codeUnits,
            (this as? OneRegisterInstruction)?.registerA, (this as? TwoRegisterInstruction)?.registerB,
            (this as? ThreeRegisterInstruction)?.registerC, (this as? FiveRegisterInstruction)?.let {
                listOf(it.registerCount, it.registerC, it.registerD, it.registerE, it.registerF, it.registerG)
            }, (this as? RegisterRangeInstruction)?.let { listOf(it.startRegister, it.registerCount) },
            (this as? WideLiteralInstruction)?.wideLiteral, (this as? OffsetInstruction)?.codeOffset,
            (this as? SwitchPayload)?.switchElements?.map { listOf(it.key, it.offset) },
            (this as? ArrayPayload)?.let { listOf(it.elementWidth, it.arrayElements.map { number -> number.toLong() }) }, referenceText())
    }
}
