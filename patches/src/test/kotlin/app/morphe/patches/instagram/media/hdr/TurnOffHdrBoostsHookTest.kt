/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.hdr

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every HDR headroom request and HDR color mode call in Instagram's code goes through HdrBoost,
 * which holds the boost back while the switch is on and passes Instagram's value while it's off.
 */
class TurnOffHdrBoostsHookTest {
    private val surface = HDR_CALLS[0]
    private val transaction = HDR_CALLS[1]
    private val windowHeadroom = HDR_CALLS[2]
    private val colorMode = HDR_CALLS[3]
    private val extendedRange = HDR_CALLS[4]

    private fun ref(call: HdrCall, definingClass: String = call.definingClass) = ImmutableMethodReference(
        definingClass, call.name,
        Regex("""L[^;]+;|[IF]""").findAll(call.shape.substringBefore(')').drop(1)).map { it.value }.toList(),
        call.shape.substringAfter(')'),
    )

    /**
     * A class of [type] whose one static method makes each HDR call the way Instagram's code does:
     * v0 a SurfaceView and v1 a headroom, v2 to v4 a transaction, its control and a headroom as a
     * range call whose answer it keeps, v5 a window and v6 a color mode, then the transaction's
     * extended range brightness on v2 and v3 with v4 and v1 as its two ratios, its answer kept too.
     * A window call of another name and a compat class's setColorMode sit between and stay.
     */
    private fun caller(type: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "show", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    7,
                    listOf(
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, ref(surface)),
                        ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 2, 3, ref(transaction)),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 5, 6, 0, 0, 0,
                            ImmutableMethodReference("Landroid/view/Window;", "addFlags", listOf("I"), "V")),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 5, 6, 0, 0, 0,
                            ref(colorMode, "Lfixture/WindowCompat;")),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 5, 1, 0, 0, 0, ref(windowHeadroom)),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 5, 6, 0, 0, 0, ref(colorMode)),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 2, 3, 4, 1, 0, ref(extendedRange)),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            ),
        ),
    )

    @Test
    fun eachCallGoesToItsStandInWithTheSameRegisters() {
        val instagram = "Lfixture/HdrViewer;"
        val context = PatchContexts.of(listOf(caller(instagram)))

        assertEquals(
            mapOf(surface to 1, transaction to 1, windowHeadroom to 1, colorMode to 1, extendedRange to 1),
            context.holdBackHdrBoosts(),
        )

        val code = context.mutableClassDefBy(instagram).methods.single().instructions()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$HDR_BOOST->surfaceViewHeadroom(Landroid/view/SurfaceView;F)V", code[0].target())
        assertEquals(listOf(0, 1), code[0].registers())
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[1].opcode)
        assertEquals(
            "$HDR_BOOST->transactionHeadroom(Landroid/view/SurfaceControl\$Transaction;Landroid/view/SurfaceControl;F)" +
                "Landroid/view/SurfaceControl\$Transaction;",
            code[1].target(),
        )
        assertEquals(listOf(2, 3, 4), code[1].registers())
        assertEquals("the transaction's answer is still kept", Opcode.MOVE_RESULT_OBJECT, code[2].opcode)
        assertEquals("another window call stays", "addFlags", ((code[3] as ReferenceInstruction).reference as MethodReference).name)
        assertEquals("another class's setColorMode stays", Opcode.INVOKE_VIRTUAL, code[4].opcode)
        assertEquals("$HDR_BOOST->windowHeadroom(Landroid/view/Window;F)V", code[5].target())
        assertEquals(listOf(5, 1), code[5].registers())
        assertEquals("$HDR_BOOST->colorMode(Landroid/view/Window;I)V", code[6].target())
        assertEquals(listOf(5, 6), code[6].registers())
        assertEquals(Opcode.INVOKE_STATIC, code[7].opcode)
        assertEquals(
            "$HDR_BOOST->extendedRangeBrightness(Landroid/view/SurfaceControl\$Transaction;Landroid/view/SurfaceControl;FF)" +
                "Landroid/view/SurfaceControl\$Transaction;",
            code[7].target(),
        )
        assertEquals(listOf(2, 3, 4, 1), code[7].registers())
        assertEquals("the extended range answer is still kept", Opcode.MOVE_RESULT_OBJECT, code[8].opcode)
    }

    /** The extension's own calls are the real ones the stand-ins make. Sent, each would call itself. */
    @Test
    fun theExtensionsOwnCallsStay() {
        val context = PatchContexts.of(listOf(caller("Lfixture/HdrViewer;"), caller(HDR_BOOST)))

        context.holdBackHdrBoosts()

        val kept = context.mutableClassDefBy(HDR_BOOST).methods.single().instructions()
        assertEquals(listOf(surface, transaction, windowHeadroom, colorMode, extendedRange), kept.mapNotNull { it.hdrCall() })
    }

    /** A build that never asks for headroom fails the patch, even if it sets an HDR color mode. */
    @Test
    fun aBuildAskingForNoHeadroomFailsThePatch() {
        val type = "Lfixture/ColorOnly;"
        val colorOnly = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    type, "show", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(
                        2,
                        listOf(
                            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, ref(colorMode)),
                            ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ),
                        null, null,
                    ),
                ),
            ),
        )
        val context = PatchContexts.of(listOf(colorOnly))
        assertThrows(PatchException::class.java) { context.holdBackHdrBoosts() }
        assertEquals("changed before failing", colorMode, context.mutableClassDefBy(type).methods.single().instructions()[0].hdrCall())
    }

    /**
     * Each stand-in the rewrite writes is a public static method of the HdrBoost the bundle ships,
     * read from the compiled extension, so a parameter that compiles to another type fails here and
     * not in Instagram's viewer.
     */
    @Test
    fun everyCallSentHasAStandIn() {
        val declared = ExtensionDex.classDef(HDR_BOOST).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HDR_BOOST->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        HDR_CALLS.forEach { assertTrue("HdrBoost declares no ${it.standInMethod}: $declared", it.standInMethod in declared) }
    }

    /**
     * The declared build asks for headroom from one SurfaceView method, one transaction method and
     * two window methods, sets a window's color mode from six and an extended range brightness from
     * one, the layer it draws some videos on (#85). Every one of those calls goes to
     * its stand-in on the same registers, with the instruction count unchanged, and none is left.
     */
    @Test
    fun eachDeclaredBuildSendsEveryCall() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { m -> HDR_CALLS.any { it.definingClass == m.definingClass && it.name == m.name } }) {
                        return@forEach
                    }
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.hdrCall() != null } }) {
                            callers += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val making = callers.flatMap { it.methods }.filter { method -> method.instructions().any { it.hdrCall() != null } }
                val byCall = HDR_CALLS.associateWith { call -> making.count { method -> method.instructions().any { it.hdrCall() == call } } }
                assertEquals(
                    "${bundle.name}: methods making each call",
                    mapOf(surface to 1, transaction to 1, windowHeadroom to 2, colorMode to 6, extendedRange to 1), byCall,
                )

                val context = PatchContexts.of(callers)
                val sent = context.holdBackHdrBoosts()

                var calls = 0
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val was = original.instructions()
                        if (was.none { it.hdrCall() != null }) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: instruction count", was.size, now.size)
                        assertEquals("$where: calls left", emptyList<HdrCall>(), now.mapNotNull { it.hdrCall() })
                        was.forEachIndexed { index, instruction ->
                            val call = instruction.hdrCall() ?: return@forEachIndexed
                            assertEquals("$where at $index", call.standInMethod, now[index].target())
                            assertEquals("$where at $index: registers", instruction.registers(), now[index].registers())
                            calls++
                        }
                    }
                }
                assertEquals("${bundle.name}: calls sent", calls, sent.values.sum())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.target(): String = (this as ReferenceInstruction).reference.toString()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}
