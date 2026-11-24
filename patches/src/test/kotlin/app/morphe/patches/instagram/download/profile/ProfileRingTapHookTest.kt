/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A story ring opens the story (#823): the profile picture tap's one read of the flag that lets it
 * look up the account's story before falling back to the picture goes through
 * ProfileRingTap.storyFirst on the same register, right after the read, and nothing else in the tap
 * moves. Instagram's own checks on that answer (the ring, a story in its store) stay where they are.
 */
class ProfileRingTapHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(RING_TAP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$RING_TAP->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$RING_TAP_HOOK is not in the extension: $declared", RING_TAP_HOOK in declared)
    }

    @Test
    fun anExtensionWithoutTheHookFailsThePatch() {
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(tap())).findRingTap() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no $RING_TAP"))
    }

    @Test
    fun aBuildWithoutTheFlagLeavesTheRestOfThePatchIn() {
        assertNull(PatchContexts.of(listOf(ExtensionDex.classDef(RING_TAP))).findRingTap())
    }

    /** A read of the flag anywhere but the profile picture's tap is never answered. */
    @Test
    fun aReadOutsideTheTapIsLeftAlone() {
        assertNull(PatchContexts.of(listOf(tap(strings = emptyList()), ExtensionDex.classDef(RING_TAP))).findRingTap())
    }

    @Test
    fun theTapsReadIsAnsweredOnItsOwnRegister() {
        val context = PatchContexts.of(listOf(tap(), tap(type = OTHER, strings = emptyList()), ExtensionDex.classDef(RING_TAP)))
        val read = context.findRingTap() ?: throw AssertionError("the stand-in tap wasn't found")
        assertEquals(TAP, read.type)
        assertEquals(3, read.moveResult)
        assertEquals(1, read.register)

        context.applyRingTap(read)

        assertAnswered("stand-in", context.mutableClassDefBy(TAP).methods.single(), 3, 1)
        val other = context.mutableClassDefBy(OTHER).methods.single().code()
        assertTrue("the other read is left alone", other.none { it.referenceText() == RING_TAP_HOOK })
    }

    @Test
    fun eachDeclaredBuildAnswersTheTapsRead() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                answers(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAnswersTheTapsReadToo() {
        for (base in Fixtures.otherBuilds()) answers(base, base.parentFile.name)
    }

    private fun answers(bundle: File, label: String) {
        val classes = FixtureDex.classesLoadingAny(bundle, setOf(TAP_PICTURE, PROFILE_HEADER)) { classDef ->
            classDef.methods.any { method -> method.code().any { it.loadsFlag() } }
        }
        val byType = classes.associateBy { it.type }
        val taps = classes.flatMap { it.methods }.filter { method ->
            method.code().any { it.loadsFlag() } &&
                FixtureDex.loads(method, TAP_PICTURE, byType) && FixtureDex.loads(method, PROFILE_HEADER, byType)
        }
        val stock = taps.singleOrNull() ?: throw AssertionError("$label: expected one tap reading the flag, found ${taps.map { "${it.definingClass}->${it.name}" }}")
        val stockCode = stock.code()
        val load = stockCode.indices.single { stockCode[it].loadsFlag() }
        val call = (load + 1..load + 4).first { (stockCode[it] as? ReferenceInstruction)?.reference is MethodReference }
        val result = call + 1
        assertEquals("$label: the read's move-result", Opcode.MOVE_RESULT, stockCode[result].opcode)
        val register = (stockCode[result] as OneRegisterInstruction).registerA
        val test = stockCode[result + 1]
        assertTrue("$label: Instagram tests the answer right away", test.opcode == Opcode.IF_EQZ || test.opcode == Opcode.IF_NEZ)
        assertEquals("$label: on the read's register", register, (test as OneRegisterInstruction).registerA)

        // The rule in scripts/injected-mutation-contracts.txt picks this method alone.
        assertTrue("$label: the tap is an instance method", !AccessFlags.STATIC.isSet(stock.accessFlags))
        val shape = "(${stock.parameterTypes.joinToString("")})${stock.returnType}"
        assertTrue("$label: the tap's shape $shape", Regex(TAP_SHAPE).matches(shape))

        val context = PatchContexts.of(classes + ExtensionDex.classDef(RING_TAP))
        val read = context.findRingTap() ?: throw AssertionError("$label: the patch found no tap")
        val where = "$label: ${read.type}->${read.name}"
        assertEquals("$where: the tap", "${stock.definingClass}->${stock.name}", "${read.type}->${read.name}")
        assertEquals("$where: the read", result, read.moveResult)
        assertEquals("$where: its register", register, read.register)

        val method = context.mutableClassDefBy(read.type).methods.single {
            it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters
        }
        val path = NeutralNativePath(method)
        context.applyRingTap(read)
        assertAnswered(where, method, result, register)
        path.assertPreserved(where, method, setOf(result + 1, result + 2))
    }

    private fun assertAnswered(what: String, method: Method, result: Int, register: Int) {
        val code = method.code()
        assertEquals("$what: one hook", listOf(result + 1), code.indices.filter { code[it].referenceText() == RING_TAP_HOOK })
        val hook = code[result + 1]
        assertEquals("$what: a static range call", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$what: handed the read's answer", register, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$what: and only that", 1, hook.registerCount)
        assertEquals("$what: the answer comes back", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: on the same register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    private fun Instruction.loadsFlag(): Boolean =
        opcode == Opcode.CONST_WIDE && (this as WideLiteralInstruction).wideLiteral == STORY_FIRST_FLAG

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val TAP = "LX/0Inj;"
        const val OTHER = "LX/0Zzz;"
        const val CONFIG = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
        const val LOGGER = "LX/0Log;"

        /** The tap's shape on every 456 and 457 build: a context, the picture's bounds, the view, three of Instagram's own and a callback. */
        const val TAP_SHAPE = "\\(Landroid/content/Context;Landroid/graphics/RectF;Landroid/view/View;LX/[^;]+;LX/[^;]+;LX/[^;]+;Lkotlin/jvm/functions/Function0;\\)V"

        /** A stand-in tap: it reads the flag into v1, skips past logging [strings] when it's off, as the real one tests it. */
        fun tap(type: String = TAP, strings: List<String> = listOf(TAP_PICTURE, PROFILE_HEADER)): ClassDef {
            val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val logs = strings.flatMap { string ->
                listOf<Instruction>(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(string)),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(LOGGER, "log", listOf("Ljava/lang/String;"), "V")),
                )
            }
            // A const-string is two code units and a call three, so the branch lands on the return.
            val code = listOf<Instruction>(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 2, STORY_FIRST_FLAG),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(CONFIG)),
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 0, 2, 3, 0, 0, ImmutableMethodReference(CONFIG, "Be3", listOf("J"), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
                ImmutableInstruction21t(Opcode.IF_EQZ, 1, 2 + 5 * strings.size),
            ) + logs + ImmutableInstruction10x(Opcode.RETURN_VOID)
            return ImmutableClassDef(
                type, instance, "Ljava/lang/Object;", null, null, null, null,
                listOf(ImmutableMethod(type, "EMg", null, "V", instance, null, null, ImmutableMethodImplementation(5, code, null, null))),
            )
        }
    }
}
