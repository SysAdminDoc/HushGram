/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.savedrings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Story rings on saved posts (#790): on every declared build, the lookup of a post author's story
 * leaves the saved feed's surfaces out of the ones it answers on, which is why a saved post shows
 * no ring, and the patch hands the surface it reads to the extension right after the read, jumping
 * to that lookup on a yes. The rest of the method is Instagram's.
 */
class SavedStoryRingsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SAVED_STORY_RINGS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SAVED_RING_SHOWS is not in the extension: $declared", SAVED_RING_SHOWS.substringAfter("->") in declared)
    }

    @Test
    fun eachDeclaredBuildLetsSavedPostsAskForTheRing() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    @Test
    fun withoutTheExtensionThePatchFails() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).requireSavedRingHook() }
        assertTrue(failure.message!!, failure.message!!.contains(SAVED_STORY_RINGS))
    }

    @Test
    fun withoutTheSurfaceEnumThePatchFails() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findSavedRingSite() }
        assertTrue(failure.message!!, failure.message!!.contains("expected one enum"))
    }

    private fun builds(bundle: File, label: String) {
        val classes = slice(bundle)
        val context = PatchContexts.of(classes)
        val site = context.findSavedRingSite()
        val stock = classes.single { it.type == site.type }.methods.single { it.matches(site) }
        val stockCode = stock.code()

        val enum = (stockCode[site.read].reference() as FieldReference).type
        val names = context.constantNames(classes.single { it.type == enum })
        val allowed = stockCode.subList(site.read + 1, site.lookup).mapNotNull { (it.reference() as? FieldReference)?.takeIf { field -> field.type == enum } }
            .map { names[it.name] ?: error("$label: ${it.name} isn't one of $enum's constants") }
        assertTrue("$label: Home asks for the ring, $allowed", "MAIN_FEED" in allowed)
        SAVED_SURFACES.forEach { assertFalse("$label: Instagram already asks on $it, $allowed", it in allowed) }

        context.letSavedRingsThrough(site)
        val patched = context.mutableClassDefBy(site.type).methods.single { it.matches(site) }
        val code = patched.code()
        val where = "$label ${site.where}"
        assertEquals("$where: grew by the call, its answer and the jump", stockCode.size + 3, code.size)
        assertEquals("$where: the surface read stays", stockCode.subList(0, site.read + 1).map { it.opcode }, code.subList(0, site.read + 1).map { it.opcode })
        val ask = code[site.read + 1] as RegisterRangeInstruction
        assertEquals("$where: asks the extension right after the read", SAVED_RING_SHOWS, ask.reference().toString())
        assertEquals("$where: with the surface", listOf(site.surface, 1), listOf(ask.startRegister, ask.registerCount))
        val answer = code[site.read + 2]
        assertEquals("$where: keeps the answer", Opcode.MOVE_RESULT, answer.opcode)
        val jump = code[site.read + 3]
        assertEquals("$where: jumps on a yes", Opcode.IF_NEZ, jump.opcode)
        assertEquals("$where: on the answer", (answer as OneRegisterInstruction).registerA, (jump as OneRegisterInstruction).registerA)
        val target = ControlFlow.of(patched).normal[site.read + 3].first()
        assertEquals("$where: to the lookup", site.lookup + 3, target)
        assertEquals("$where: the lookup it jumps to is Instagram's", stockCode[site.lookup].reference().toString(), code[target].reference().toString())
        assertEquals("$where: the rest is Instagram's", stockCode.drop(site.read + 1).map { it.opcode }, code.drop(site.read + 4).map { it.opcode })
        val stockFlow = ControlFlow.of(stock)
        val patchedFlow = ControlFlow.of(patched)
        for (at in site.read + 1 until stockCode.size) {
            assertEquals("$where: instruction $at still goes where it went", stockFlow.normal[at].map { it + 3 }, patchedFlow.normal[at + 3])
        }
    }

    /**
     * The fixture classes the patch reads: the enums loading SAVE_FEED, with the string pools they
     * ask, and every class with a method of the lookup's shape. The patch's own search by the enum's
     * readers runs over this slice.
     */
    private fun slice(bundle: File): List<ClassDef> {
        val enums = FixtureDex.classesLoading(bundle, SAVED_SURFACES.first()).filter { it.superclass == "Ljava/lang/Enum;" }
        val lookups = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.hasShape() }) lookups += ImmutableClassDef.of(classDef)
            }
        }
        val types = enums.mapTo(HashSet()) { it.type }
        return FixtureDex.withStringPools(bundle, enums + lookups.filter { it.type !in types })
    }

    /** Each of [enum]'s constant fields by name: the last string its initializer loads before setting the field. */
    private fun BytecodePatchContext.constantNames(enum: ClassDef): Map<String, String> {
        val code = enum.methods.single { it.name == "<clinit>" }.code()
        val names = mutableMapOf<String, String>()
        var last: String? = null
        code.indices.forEach { at ->
            stringLoadedAt(code, at)?.let { last = it }
            val field = code[at].reference() as? FieldReference
            if (code[at].opcode == Opcode.SPUT_OBJECT && field?.definingClass == enum.type && field.type == enum.type) {
                last?.let { names[field.name] = it }
            }
        }
        return names
    }

    private fun Method.hasShape(): Boolean {
        val parameters = parameterTypes.map(Any::toString)
        return implementation != null && parameters.size == 6 && parameters[0] == "Lcom/instagram/common/session/UserSession;" &&
            parameters[3] == "Lkotlin/jvm/functions/Function0;" && parameters[4] == "Z" && parameters[5] == "Z"
    }

    private fun Method.matches(site: SavedRingSite) =
        name == site.name && returnType == site.returnType && parameterTypes.map(Any::toString) == site.parameters

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}
