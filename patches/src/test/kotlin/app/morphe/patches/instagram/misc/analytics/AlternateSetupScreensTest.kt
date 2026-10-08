/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.analytics.AlternateSetupFixture.code
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

class AlternateSetupScreensTest {
    @Test
    fun theSharedVoidPresenterAsksWithTheSecondStringAndKeepsEveryNativeOperation() {
        val classes = AlternateSetupFixture.classes()
        val context = PatchContexts.of(classes)
        assertNull(context.skipSetupScreens(SETUP_SCREEN))
        val original = classes.single { it.type == AlternateSetupFixture.PRESENTER }.methods.single()
        val changed = context.classDefBy(original.definingClass).methods.single()
        AlternateSetupFixture.assertGuard(changed.code(), changed.implementation!!.registerCount - 5,
            "${AlternateSetupFixture.MODEL}->appId:Ljava/lang/String;")
        AlternateSetupFixture.assertBodies(classes, context, mapOf(changed.toString() to 7,
            "${AlternateSetupFixture.OPENER}->open(Landroid/content/Context;${AlternateSetupFixture.CONFIG})V" to 6))
    }

    @Test
    fun aCopiedModelCanCrossBranchesWhenEveryPathKeepsTheSameObject() {
        val classes = AlternateSetupFixture.classes("safe branch")
        val context = PatchContexts.of(classes)
        assertNull(context.skipSetupScreens(SETUP_SCREEN))
        AlternateSetupFixture.assertGuard(context.classDefBy(AlternateSetupFixture.PRESENTER).methods.single().code(), 14,
            "${AlternateSetupFixture.MODEL}->appId:Ljava/lang/String;")
    }

    @Test
    fun theGuardProofRejectsWrongFieldsWrongModelsAndMissingOrMisplacedCalls() {
        val context = PatchContexts.of(AlternateSetupFixture.classes())
        assertNull(context.skipSetupScreens(SETUP_SCREEN))
        val correct = context.classDefBy(AlternateSetupFixture.PRESENTER).methods.single().code()
        val field = "${AlternateSetupFixture.MODEL}->appId:Ljava/lang/String;"
        AlternateSetupFixture.assertGuard(correct, 14, field)
        val mutants = listOf(
            correct.toMutableList().also { it[0] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, 13) },
            correct.toMutableList().also { it[2] = ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 0,
                ImmutableFieldReference(AlternateSetupFixture.MODEL, "title", "Ljava/lang/String;")) },
            correct.toMutableList().also { it[1] = ImmutableInstruction21t(Opcode.IF_EQZ, 0, AlternateSetupFixture.offset(it, 1, 8)) },
            correct.toMutableList().also { it[3] = it.last() },
            correct + correct[3],
        )
        for ((index, code) in mutants.withIndex()) {
            assertTrue("guard mutant $index survived", runCatching { AlternateSetupFixture.assertGuard(code, 14, field) }.exceptionOrNull() is AssertionError)
        }
    }
}

@RunWith(Parameterized::class)
class AlternateSetupScreensRefusalTest(private val variant: String) {
    @Test
    fun unsupportedRoutesLeaveBothOpenerAndPresenterUntouched() {
        val classes = AlternateSetupFixture.classes(variant)
        val context = PatchContexts.of(classes)
        val before = AlternateSetupFixture.snapshot(classes)
        assertNotNull(variant, context.skipSetupScreens(SETUP_SCREEN))
        assertEquals(variant, before, AlternateSetupFixture.snapshot(classes.map { context.classDefBy(it.type) }))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            "no action", "duplicate action", "missing action marker", "duplicate presenter", "missing presenter marker",
            "another presenter", "required presenter result", "no presenter local", "no screen data", "nonpublic screen data",
            "no screen data constructor", "ambiguous screen data constructor", "first String kept instead", "conditional app id field",
            "two app id fields", "app id field overwritten", "private app id field", "mutable app id field", "static app id field",
            "wrong checked app id", "checked app id overwritten", "reversed app id check", "app id check bypasses construction",
            "duplicate construction", "construction after presentation", "allocation overwritten", "another presented object",
            "presented alias overwritten", "conditional model copy", "construction bypassed", "constructor handler bypass",
        ).map { arrayOf(it) }
    }
}

/** Synthetic names keep version-specific identities out of the tests and the patch. */
internal object AlternateSetupFixture {
    const val OPENER = "Lfixture/ScreenOpener;"
    const val MODEL = "Lfixture/CdsScreenData;"
    const val PRESENTER = "Lfixture/CdsPresenter;"
    const val ACTION = "Lfixture/OpenScreenAction;"
    const val CONFIG = "Lcom/instagram/bloks/hosting/IgBloksScreenConfig;"
    private val constructorParameters = listOf("Landroid/util/SparseArray;", "Lfixture/Callback;", "Lfixture/Callback;", "Ljava/lang/Object;") +
        List(4) { "Ljava/lang/String;" } + listOf("Ljava/util/HashMap;", "Ljava/util/List;") + List(3) { "Ljava/util/Map;" } +
        listOf("I", "I", "J", "J", "Z", "Z")
    private val presenterParameters = listOf("Landroid/content/Context;", MODEL, CONFIG, "Lfixture/Options;", "Lfixture/Callback;", "I")

    fun classes(variant: String = "", opener: Boolean = true): List<ClassDef> {
        val constructorReference = "$MODEL-><init>(${constructorParameters.joinToString("")})V"
        val presenterOwner = if (variant == "another presenter") "Lfixture/OtherPresenter;" else PRESENTER
        val returns = if (variant == "required presenter result") "Ljava/lang/Object;" else "V"
        val presentation = "invoke-static/range { v3 .. v8 }, $presenterOwner->show(${presenterParameters.joinToString("")})$returns"
        var action = """
            const-string v0, "null_param_openScreenOptions"
            invoke-static { }, Lfixture/Options;->appId()Ljava/lang/String;
            move-result-object v8
            if-nez v8, :allocate
            const-string v0, "null_param_appId"
            const/4 v0, 0x0
            return-object v0
            :allocate
            new-instance v2, $MODEL
            invoke-direct/range { v2 .. v23 }, $constructorReference
            move-object v4, v2
            :present
            const/4 v8, 0x0
            const-string v0, "cds_push_invocation_start"
            $presentation
            const-string v0, "cds_push_invocation_end"
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()
        action = when (variant) {
            "missing action marker" -> action.replace("null_param_openScreenOptions", "unrelated_options")
            "wrong checked app id" -> action.replace("move-result-object v8", "move-result-object v7").replace("if-nez v8", "if-nez v7")
            "checked app id overwritten" -> action.replace("new-instance v2", "const/4 v8, 0x0\nnew-instance v2")
            "reversed app id check" -> action.replace("if-nez v8", "if-eqz v8")
            "app id check bypasses construction" -> action.replace("if-nez v8, :allocate", "if-nez v8, :present")
            "duplicate construction" -> action.replace("move-object v4", "invoke-direct/range { v2 .. v23 }, $constructorReference\nmove-object v4")
            "construction after presentation" -> action.replace("invoke-direct/range { v2 .. v23 }, $constructorReference", "nop")
                .replace("$presentation", "$presentation\ninvoke-direct/range { v2 .. v23 }, $constructorReference")
            "allocation overwritten" -> action.replace("invoke-direct/range", "const/4 v2, 0x0\ninvoke-direct/range")
            "another presented object" -> action.replace("move-object v4, v2", "move-object v4, v1")
            "presented alias overwritten" -> action.replace(":present", "const/4 v4, 0x0\n:present")
            "conditional model copy" -> action.replace("move-object v4, v2", "if-eqz v1, :present\nmove-object v4, v2")
            "construction bypassed" -> action.replace("new-instance v2", "if-eqz v1, :present\nnew-instance v2")
            "safe branch" -> action.replace("move-object v4, v2", "if-eqz v1, :copy\nconst/4 v0, 0x0\n:copy\nmove-object v4, v2")
            else -> action
        }
        var constructor = """
            invoke-direct { v11 }, Ljava/lang/Object;-><init>()V
            move-object/from16 v0, v17
            iput-object v0, v11, $MODEL->appId:Ljava/lang/String;
            move-object/from16 v1, v16
            iput-object v1, v11, $MODEL->title:Ljava/lang/String;
            return-void
        """.trimIndent()
        constructor = when (variant) {
            "first String kept instead" -> constructor.replace("v0, v17", "v0, v16")
            "conditional app id field" -> constructor.replace("move-object/from16 v0", "if-eqz v17, :done\nmove-object/from16 v0").replace("return-void", ":done\nreturn-void")
            "two app id fields" -> constructor.replace("move-object/from16 v1", "iput-object v0, v11, $MODEL->title:Ljava/lang/String;\nmove-object/from16 v1")
            "app id field overwritten" -> constructor.replace("return-void", "const/4 v0, 0x0\niput-object v0, v11, $MODEL->appId:Ljava/lang/String;\nreturn-void")
            else -> constructor
        }
        val modelFlags = if (variant == "nonpublic screen data") 0 else AccessFlags.PUBLIC.value
        val fieldFlags = when (variant) {
            "private app id field" -> AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
            "mutable app id field" -> AccessFlags.PUBLIC.value
            "static app id field" -> AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.STATIC.value
            else -> AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        }
        val modelMethods = mutableListOf<Method>()
        if (variant != "no screen data constructor") modelMethods += method(MODEL, "<init>", constructorParameters, "V", 33,
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, constructor)
        if (variant == "ambiguous screen data constructor") modelMethods += method(MODEL, "<init>", constructorParameters.toMutableList().also { it[1] = "Lfixture/OtherCallback;" }, "V", 33,
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, constructor)
        var presenterBody = DIRECT_SCREEN_PRESENTER.joinToString("\n") { "const-string v0, \"$it\"" } +
            "\ninvoke-static { p0, p1 }, Lfixture/NativeScreen;->show(Landroid/content/Context;$MODEL)V\n" +
            if (returns == "V") "return-void" else "const/4 v0, 0x0\nreturn-object v0"
        if (variant == "missing presenter marker") presenterBody = presenterBody.replace("foa_bottom_sheet_config", "unrelated_config")
        val classes = mutableListOf<ClassDef>()
        if (opener) classes += opener()
        if (variant != "no screen data") classes += clazz(MODEL, modelMethods, modelFlags, listOf(
            ImmutableField(MODEL, "appId", "Ljava/lang/String;", fieldFlags, null, null, null),
            ImmutableField(MODEL, "title", "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)))
        classes += clazz(PRESENTER, listOf(method(PRESENTER, "show", presenterParameters, returns,
            if (variant == "no presenter local") 6 else 19, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, presenterBody)))
        if (variant != "no action") {
            var actionMethod = method(ACTION, "open", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;", 30,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, action)
            if (variant == "constructor handler bypass") {
                val code = actionMethod.code()
                val constructorIndex = code.indexOfFirst { it.referenceText() == constructorReference }
                val modelCopy = code.indexOfFirst { it.opcode == Opcode.MOVE_OBJECT }
                actionMethod = ImmutableMethod(ACTION, "open", actionMethod.parameters, actionMethod.returnType, actionMethod.accessFlags, null, null,
                    ImmutableMethodImplementation(30, code, listOf(ImmutableTryBlock(code.take(constructorIndex).sumOf { it.codeUnits }, code[constructorIndex].codeUnits,
                        listOf(ImmutableExceptionHandler("Ljava/lang/Throwable;", code.take(modelCopy).sumOf { it.codeUnits })))), null))
            }
            classes += clazz(ACTION, listOf(actionMethod))
        }
        if (variant == "duplicate action") classes += clazz("Lfixture/OtherAction;", listOf(method("Lfixture/OtherAction;", "open", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            "Ljava/lang/Object;", 30, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, action)))
        if (variant == "duplicate presenter") classes += clazz("Lfixture/OtherPresenter;", listOf(method("Lfixture/OtherPresenter;", "show", presenterParameters,
            "V", 19, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, presenterBody)))
        if (variant == "another presenter") classes += clazz(presenterOwner, listOf(method(presenterOwner, "show", presenterParameters, "V", 19,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, "return-void")))
        return classes
    }

    private fun opener(): ClassDef = clazz(OPENER, listOf(
        method(OPENER, "<init>", listOf("Ljava/lang/String;", "Ljava/util/Map;", "Ljava/util/Map;"), "V", 4,
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, """
                invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                iput-object p1, p0, $OPENER->appId:Ljava/lang/String;
                return-void
            """),
        method(OPENER, "open", listOf("Landroid/content/Context;", CONFIG), "V", 7, AccessFlags.PUBLIC.value, """
            const-string v0, "$SCREEN_FETCH"
            invoke-static { p1, p2 }, Lfixture/NativeScreen;->open(Landroid/content/Context;$CONFIG)V
            return-void
        """)), fields = listOf(ImmutableField(OPENER, "appId", "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null, null)))

    /** The presenter's strings 450 still loads itself; it pools foa_bottom_sheet_config. */
    val PRESENTER_HELD = listOf(DIRECT_SCREEN_PRESENTER.first(), DIRECT_SCREEN_PRESENTER.last())

    fun nativeClasses(bundle: File): List<ClassDef> {
        val classes = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (listOf(SCREEN_FETCH, DIRECT_SCREEN_ACTION.first(), DIRECT_SCREEN_PRESENTER.first()).none { it in dex.stringSection }) return@forEach
            for (classDef in dex.classes) if (classDef.methods.any {
                val strings = it.strings()
                SCREEN_FETCH in strings || strings.containsAll(DIRECT_SCREEN_ACTION) || strings.containsAll(PRESENTER_HELD)
            }) classes += ImmutableClassDef.of(classDef)
        }
        val model = classes.flatMap { it.methods }.single { it.strings().containsAll(PRESENTER_HELD) }.parameterTypes[1].toString()
        FixtureDex.forEach(bundle) { dex -> dex.classes.singleOrNull { it.type == model }?.let { classes += ImmutableClassDef.of(it) } }
        return FixtureDex.withStringPools(bundle, classes.distinctBy { it.type })
    }

    fun assertGuard(code: List<Instruction>, modelRegister: Int, appId: String) {
        assertEquals(1, code.count { it.referenceText() == SETUP_SCREEN })
        assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.take(7).map { it.opcode })
        assertEquals(0, (code[0] as TwoRegisterInstruction).registerA)
        assertEquals(modelRegister, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(7, code.target(1))
        assertEquals(appId, code[2].referenceText())
        assertEquals(0, (code[2] as TwoRegisterInstruction).registerA)
        assertEquals(0, (code[2] as TwoRegisterInstruction).registerB)
        assertEquals(SETUP_SCREEN, code[3].referenceText())
        assertEquals(1, (code[3] as FiveRegisterInstruction).registerCount)
        assertEquals(0, (code[3] as FiveRegisterInstruction).registerC)
        assertEquals(0, (code[4] as OneRegisterInstruction).registerA)
        assertEquals(0, (code[5] as OneRegisterInstruction).registerA)
        assertEquals(7, code.target(5))
    }

    fun assertBodies(classes: List<ClassDef>, context: BytecodePatchContext, prefixes: Map<String, Int>) {
        for (classDef in classes) for (before in classDef.methods) {
            val after = context.classDefBy(classDef.type).methods.single { it.toString() == before.toString() }
            val prefix = prefixes[before.toString()] ?: 0
            val original = before.code()
            val patched = after.code()
            assertEquals(before.toString(), before.accessFlags, after.accessFlags)
            assertEquals(before.toString(), before.implementation?.registerCount, after.implementation?.registerCount)
            assertEquals(before.toString(), original.size + prefix, patched.size)
            for (index in original.indices) {
                assertEquals("$before instruction $index", original[index].key(false), patched[index + prefix].key(false))
                if (original[index] is OffsetInstruction) assertEquals("$before branch $index", original.target(index), patched.target(index + prefix) - prefix)
            }
            val shift = patched.take(prefix).sumOf { it.codeUnits }
            assertEquals(before.toString(), before.implementation?.tryBlocks?.map { block -> listOf(block.startCodeAddress, block.codeUnitCount,
                block.exceptionHandlers.map { listOf(it.exceptionType, it.handlerCodeAddress) }) }, after.implementation?.tryBlocks?.map { block ->
                listOf(block.startCodeAddress - shift, block.codeUnitCount, block.exceptionHandlers.map { listOf(it.exceptionType, it.handlerCodeAddress - shift) }) })
        }
    }

    fun snapshot(classes: List<ClassDef>) = classes.associate { clazz -> clazz.type to clazz.methods.associate { method ->
        method.toString() to listOf(method.accessFlags, method.implementation?.registerCount, method.code().map { it.key() },
            method.implementation?.tryBlocks?.map { block -> listOf(block.startCodeAddress, block.codeUnitCount, block.exceptionHandlers.map { listOf(it.exceptionType, it.handlerCodeAddress) }) })
    } }
    fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    fun Method.strings(): Set<String> = code().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
    fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Instruction.key(offset: Boolean = true): List<Any?> = listOf(opcode, codeUnits,
        (this as? OneRegisterInstruction)?.registerA, (this as? TwoRegisterInstruction)?.registerB,
        (this as? ThreeRegisterInstruction)?.registerC, (this as? FiveRegisterInstruction)?.let { listOf(it.registerCount, it.registerC, it.registerD, it.registerE, it.registerF, it.registerG) },
        (this as? RegisterRangeInstruction)?.let { listOf(it.startRegister, it.registerCount) }, (this as? WideLiteralInstruction)?.wideLiteral,
        if (offset) (this as? OffsetInstruction)?.codeOffset else null, (this as? SwitchPayload)?.switchElements?.map { listOf(it.key, it.offset) },
        (this as? ArrayPayload)?.let { listOf(it.elementWidth, it.arrayElements.map { number -> number.toLong() }) }, referenceText())
    private fun List<Instruction>.target(index: Int): Int {
        val address = take(index).sumOf { it.codeUnits } + (this[index] as OffsetInstruction).codeOffset
        var current = 0
        for (candidate in indices) { if (current == address) return candidate; current += this[candidate].codeUnits }
        throw AssertionError("branch $index has no instruction target")
    }
    fun offset(code: List<Instruction>, from: Int, to: Int) = code.take(to).sumOf { it.codeUnits } - code.take(from).sumOf { it.codeUnits }
    private fun clazz(type: String, methods: List<Method>, flags: Int = AccessFlags.PUBLIC.value, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, flags, "Ljava/lang/Object;", null, null, null, fields, methods)
    private fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val mutable = MutableMethod(ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null)))
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }
}
