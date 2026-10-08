/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.location

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

/** Every read of a Location's place in Instagram's code goes through SpoofLocation, on the same registers. */
class SpoofLocationHookTest {
    private fun read(name: String, definingClass: String = LOCATION): ImmutableMethodReference {
        val (shape, _) = LOCATION_READS.getValue(name)
        val parameters = if (shape.startsWith("()")) emptyList() else listOf(LOCATION)
        return ImmutableMethodReference(definingClass, name, parameters, shape.substringAfter(')'))
    }

    /**
     * A class of [type] whose one static method reads a fix the way Instagram's code does: v0 the
     * fix, v1 another location. The latitude as a five-register call, the longitude as a range call,
     * then a distance. An accuracy read and another class's getLatitude sit between and stay.
     */
    private fun reader(type: String, reads: List<String> = LOCATION_READS.keys.toList()): ClassDef {
        val code = mutableListOf<Instruction>()
        if ("getLatitude" in reads) {
            code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, read("getLatitude"))
            code += ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 2)
        }
        code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference(LOCATION, "getAccuracy", emptyList(), "F"))
        code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, read("getLatitude", "Lfixture/Place;"))
        if ("getLongitude" in reads) {
            code += ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 1, read("getLongitude"))
            code += ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 2)
        }
        if ("distanceTo" in reads) {
            code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, read("distanceTo"))
            code += ImmutableInstruction11x(Opcode.MOVE_RESULT, 2)
        }
        code += ImmutableInstruction10x(Opcode.RETURN_VOID)
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    type, "read", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(4, code, null, null),
                ),
            ),
        )
    }

    @Test
    fun eachReadGoesToItsStandInWithTheSameRegisters() {
        val instagram = "Lfixture/LocationReader;"
        val context = PatchContexts.of(listOf(reader(instagram)))

        assertEquals(mapOf("getLatitude" to 1, "getLongitude" to 1, "distanceTo" to 1), context.spoofLocationReads())

        val code = context.mutableClassDefBy(instagram).methods.single().instructions()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$SPOOF_LOCATION->latitude($LOCATION)D", (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(0), code[0].registers())
        assertEquals("the accuracy stays", Opcode.INVOKE_VIRTUAL, code[2].opcode)
        assertEquals("another class's getLatitude stays", Opcode.INVOKE_VIRTUAL, code[3].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[4].opcode)
        assertEquals("$SPOOF_LOCATION->longitude($LOCATION)D", (code[4] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(0), code[4].registers())
        assertEquals("$SPOOF_LOCATION->distanceTo($LOCATION$LOCATION)F", (code[6] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(0, 1), code[6].registers())
    }

    /** The extension's own reads are the real ones the stand-ins make. Sent, each would call itself. */
    @Test
    fun theExtensionsOwnReadsStay() {
        val context = PatchContexts.of(listOf(reader("Lfixture/LocationReader;"), reader(SPOOF_LOCATION)))

        context.spoofLocationReads()

        val kept = context.mutableClassDefBy(SPOOF_LOCATION).methods.single().instructions()
        assertEquals(listOf("getLatitude", "getLongitude", "distanceTo"), kept.mapNotNull { it.locationRead() })
    }

    /** Without a latitude or a longitude read the place can't reach Instagram, so the patch fails unchanged. */
    @Test
    fun aBuildMissingAReadFailsThePatchUnchanged() {
        for (reads in listOf(listOf("getLatitude", "distanceTo"), listOf("getLongitude"), emptyList())) {
            val instagram = "Lfixture/LocationReader;"
            val built = reader(instagram, reads)
            val context = PatchContexts.of(listOf(built))

            assertThrows(PatchException::class.java) { context.spoofLocationReads() }

            assertEquals(reads, context.mutableClassDefBy(instagram).methods.single().instructions().mapNotNull { it.locationRead() })
        }
    }

    /** Each stand-in the rewrite writes is a public static method of the SpoofLocation the bundle ships. */
    @Test
    fun everyReadSentHasAStandIn() {
        val declared = ExtensionDex.classDef(SPOOF_LOCATION).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$SPOOF_LOCATION->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        LOCATION_READS.keys.forEach { name ->
            assertTrue("SpoofLocation declares no ${locationStandIn(name)}: $declared", locationStandIn(name) in declared)
        }
    }

    /**
     * In each declared build every latitude, longitude and distance read goes to its stand-in on the
     * same registers, with the instruction count unchanged, and none is left behind. 450 reads each
     * of the latitude and longitude in 64 methods' worth of calls, from the location sticker's
     * nearby places to Google Play services' own LocationResult.
     */
    @Test
    fun eachDeclaredBuildSendsEveryRead() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.definingClass == LOCATION && it.name in LOCATION_READS }) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.locationRead() != null } }) {
                            callers += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(callers)
                val sent = context.spoofLocationReads()
                assertTrue("${bundle.name}: $sent", sent.getValue("getLatitude") > 0 && sent.getValue("getLongitude") > 0)

                val calls = mutableMapOf<String, Int>()
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val was = original.instructions()
                        if (was.none { it.locationRead() != null }) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: instruction count", was.size, now.size)
                        assertEquals("$where: reads left", emptyList<String>(), now.mapNotNull { it.locationRead() })
                        was.forEachIndexed { index, instruction ->
                            val name = instruction.locationRead() ?: return@forEachIndexed
                            assertEquals("$where at $index", locationStandIn(name), (now[index] as ReferenceInstruction).reference.toString())
                            assertEquals("$where at $index: registers", instruction.registers(), now[index].registers())
                            calls[name] = (calls[name] ?: 0) + 1
                        }
                    }
                }
                assertEquals("${bundle.name}: reads sent", calls.filterValues { it > 0 }, sent.filterValues { it > 0 })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}
