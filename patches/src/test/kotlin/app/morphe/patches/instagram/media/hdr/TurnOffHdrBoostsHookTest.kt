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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    private val screenRatio = HDR_CALLS[5]
    private val dataSpace = HDR_CALLS[6]
    private val isHdr = HDR_CALLS[7]
    private val hdrTypes = HDR_CALLS[8]
    private val modeHdrTypes = HDR_CALLS[9]
    private val screenChecks = listOf(isHdr, hdrTypes, modeHdrTypes)

    private fun ref(call: HdrCall, definingClass: String = call.definingClass) = ImmutableMethodReference(
        definingClass, call.name,
        Regex("""L[^;]+;|[IF]""").findAll(call.shape.substringBefore(')').drop(1)).map { it.value }.toList(),
        call.shape.substringAfter(')'),
    )

    /**
     * Instagram's HDR recording helper as the camera has it: one method holding its marker, one
     * checking the screen the three ways, isHdr on v0 and the HDR types of capabilities in v1 and a
     * mode in v2.
     */
    private fun recordingHelper(type: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "encoder", emptyList(), "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    1,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(RECORDING_HDR_MARKER)),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                    null, null,
                ),
            ),
            ImmutableMethod(
                type, "check", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    3,
                    listOf(
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ref(isHdr)),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, ref(hdrTypes)),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, ref(modeHdrTypes)),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            ),
        ),
    )

    /**
     * A class of [type] whose one static method makes each HDR call the way Instagram's code does:
     * v0 a SurfaceView and v1 a headroom, v2 to v4 a transaction, its control and a headroom as a
     * range call whose answer it keeps, v5 a window and v6 a color mode, then the transaction's
     * extended range brightness on v2 and v3 with v4 and v1 as its two ratios, its answer kept too,
     * the HDR/SDR ratio of the display in v7, read into v1, the transaction's dataspace on v2 and v3
     * with v6, its answer kept, and last the display's three HDR checks: isHdr on v7 into v6, the HDR
     * types of capabilities in v0 and of a mode in v1, each read back into its register.
     * A window call of another name and a compat class's setColorMode sit between and stay.
     */
    private fun caller(type: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "show", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    8,
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
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 7, 0, 0, 0, 0, ref(screenRatio)),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 3, 6, 0, 0, ref(dataSpace)),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 7, 0, 0, 0, 0, ref(isHdr)),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT, 6),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ref(hdrTypes)),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, ref(modeHdrTypes)),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
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
        val context = PatchContexts.of(listOf(caller(instagram), recordingHelper("Lfixture/HdrRecording;")))

        assertEquals(HDR_CALLS.associateWith { 1 }, context.holdBackHdrBoosts())

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
        assertEquals(Opcode.INVOKE_STATIC, code[9].opcode)
        assertEquals("$HDR_BOOST->hdrSdrRatio(Landroid/view/Display;)F", code[9].target())
        assertEquals(listOf(7), code[9].registers())
        assertEquals("the ratio is still read into v1", Opcode.MOVE_RESULT, code[10].opcode)
        assertEquals(
            "$HDR_BOOST->dataSpace(Landroid/view/SurfaceControl\$Transaction;Landroid/view/SurfaceControl;I)" +
                "Landroid/view/SurfaceControl\$Transaction;",
            code[11].target(),
        )
        assertEquals(listOf(2, 3, 6), code[11].registers())
        assertEquals("the dataspace answer is still kept", Opcode.MOVE_RESULT_OBJECT, code[12].opcode)
        assertEquals("$HDR_BOOST->isHdr(Landroid/view/Display;)Z", code[13].target())
        assertEquals(listOf(7), code[13].registers())
        assertEquals(Opcode.MOVE_RESULT, code[14].opcode)
        assertEquals("$HDR_BOOST->hdrTypes(Landroid/view/Display\$HdrCapabilities;)[I", code[15].target())
        assertEquals(listOf(0), code[15].registers())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[16].opcode)
        assertEquals("$HDR_BOOST->modeHdrTypes(Landroid/view/Display\$Mode;)[I", code[17].target())
        assertEquals(listOf(1), code[17].registers())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[18].opcode)
    }

    /** The extension's own calls are the real ones the stand-ins make. Sent, each would call itself. */
    @Test
    fun theExtensionsOwnCallsStay() {
        val context = PatchContexts.of(listOf(caller("Lfixture/HdrViewer;"), caller(HDR_BOOST), recordingHelper("Lfixture/HdrRecording;")))

        context.holdBackHdrBoosts()

        val kept = context.mutableClassDefBy(HDR_BOOST).methods.single().instructions()
        assertEquals(HDR_CALLS, kept.mapNotNull { it.hdrCall() })
    }

    /**
     * The HDR recording helper's checks of the screen decide what the camera offers to record, so
     * they keep Instagram's answers while every other screen check goes to HdrBoost.
     */
    @Test
    fun theRecordingHelpersScreenChecksStay() {
        val helper = "Lfixture/HdrRecording;"
        val context = PatchContexts.of(listOf(caller("Lfixture/HdrViewer;"), recordingHelper(helper)))

        assertEquals(setOf(helper), context.recordingHelpers())
        context.holdBackHdrBoosts()

        val check = context.mutableClassDefBy(helper).methods.single { it.name == "check" }.instructions()
        assertEquals(screenChecks, check.mapNotNull { it.hdrCall() })
        val viewer = context.mutableClassDefBy("Lfixture/HdrViewer;").methods.single().instructions()
        assertEquals(emptyList<HdrCall>(), viewer.mapNotNull { it.hdrCall() })
    }

    /**
     * With no recording helper to tell apart, every screen check stays Instagram's, so the camera
     * never loses its HDR recording to a guess. The headroom, ratio and layer calls are still sent.
     */
    @Test
    fun withoutTheRecordingHelperEveryScreenCheckStays() {
        val instagram = "Lfixture/HdrViewer;"
        val context = PatchContexts.of(listOf(caller(instagram)))

        assertEquals(emptySet<String>(), context.recordingHelpers())
        val sent = context.holdBackHdrBoosts()

        assertEquals(HDR_CALLS.associateWith { if (it.screenCheck) 0 else 1 }, sent)
        val code = context.mutableClassDefBy(instagram).methods.single().instructions()
        assertEquals(screenChecks, code.mapNotNull { it.hdrCall() })
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
     * one, the layer it draws some videos on (#85), and reads the screen's HDR/SDR ratio from six:
     * that layer's setup and its ratio listener, the AV1 decoder's synthetic HDR back-off and three
     * capability checks. That layer sets its dataspace from one method. The screen's HDR support is
     * checked with isHdr from four methods (two Dolby Vision checks of the video player, the device
     * capability list and a display report), the HDR types of its capabilities from ten and of its
     * mode from five. One class holds the HDR recording helper's marker, and three of its methods
     * check the screen: those six checks stay, and every other call goes to its stand-in on the same
     * registers, with the instruction count unchanged, and none is left.
     */
    @Test
    fun eachDeclaredBuildSendsEveryCall() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildSendsEveryCallToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
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
            "$label: methods making each call",
            mapOf(
                surface to 1, transaction to 1, windowHeadroom to 2, colorMode to 6, extendedRange to 1, screenRatio to 6,
                dataSpace to 1, isHdr to 4, hdrTypes to 10, modeHdrTypes to 5,
            ),
            byCall,
        )
        val helpers = callers.filter { classDef ->
            classDef.methods.any { method ->
                method.instructions().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == RECORDING_HDR_MARKER }
            }
        }.map { it.type }
        assertEquals("$label: classes holding the recording helper's marker", 1, helpers.size)
        assertEquals(
            "$label: the recording helper's methods checking the screen", 3,
            callers.single { it.type in helpers }.methods.count { method -> method.instructions().any { it.hdrCall()?.screenCheck == true } },
        )

        val context = PatchContexts.of(callers)
        assertEquals("$label: the helper the patch finds", helpers.toSet(), context.recordingHelpers())
        val sent = context.holdBackHdrBoosts()

        var calls = 0
        var kept = 0
        for (before in callers) {
            val after = context.mutableClassDefBy(before.type).methods
            val helper = before.type in helpers
            for (original in before.methods) {
                val was = original.instructions()
                if (was.none { it.hdrCall() != null }) continue
                val where = "$label: ${original.definingClass}->${original.name}"
                val now = after.single { it.sameSignatureAs(original) }.instructions()
                assertEquals("$where: instruction count", was.size, now.size)
                val stays = was.mapNotNull { it.hdrCall() }.filter { helper && it.screenCheck }
                assertEquals("$where: calls left", stays, now.mapNotNull { it.hdrCall() })
                was.forEachIndexed { index, instruction ->
                    val call = instruction.hdrCall() ?: return@forEachIndexed
                    if (helper && call.screenCheck) {
                        kept++
                        return@forEachIndexed
                    }
                    assertEquals("$where at $index", call.standInMethod, now[index].target())
                    assertEquals("$where at $index: registers", instruction.registers(), now[index].registers())
                    calls++
                }
            }
        }
        assertEquals("$label: the recording helper's screen checks kept", 6, kept)
        assertEquals("$label: calls sent", calls, sent.values.sum())
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
