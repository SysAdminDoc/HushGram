/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Show when someone was last active (#132): on each of 450's seven builds, the one presence text
 * method asks the extension for the time right after its two Active now checks, and a build the
 * patch can't tell apart fails it before an instruction changes.
 */
class ShowLastActiveHookTest {
    @Test
    fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(showLastActivePatch.default)
    }

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(LAST_ACTIVE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$EXACT_ACTIVE is not in the extension: $declared", EXACT_ACTIVE.substringAfter("->") in declared)
    }

    /** The declared build as the bundle the manager patches, and the other builds of the version, each compiled on its own. */
    @Test
    fun everyBuildAsksForTheExactTime() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                checked += builds(bundle, bundle.name)
            }
        }
        for (base in Fixtures.otherBuilds()) checked += builds(base, base.parentFile.name)
        for (code in BUILDS) assertTrue("no fixture of build $code among $checked", checked.any { it.contains("-$code") })
    }

    @Test
    fun noPresenceTextMethodFailsThePatch() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(LAST_ACTIVE)))
        refuses("found 0") { context.findPresenceText() }
    }

    /** A second method of the same shape holding the same limits can't be told from the first. */
    @Test
    fun twoPresenceTextMethodsFailThePatch() {
        val kept = keptFrom(representative())
        val copy = kept.single { it.methods.any { method -> method.isPresenceText() && method.wides().contains(WEEK_IN_MINUTES) } }
        val twin = ImmutableClassDef(
            "Lfixture/Twin;", copy.accessFlags, copy.superclass, copy.interfaces, null, null, null,
            copy.methods.map {
                ImmutableMethod(
                    "Lfixture/Twin;", it.name, it.parameters.map { p -> ImmutableMethodParameter(p.type, null, null) }, it.returnType,
                    it.accessFlags, null, null, it.implementation?.let { body ->
                        ImmutableMethodImplementation(body.registerCount, body.instructions.toList(), null, null)
                    },
                )
            },
        )
        val context = PatchContexts.of(kept + twin + ExtensionDex.classDef(LAST_ACTIVE))
        refuses("found 2") { context.findPresenceText() }
    }

    private val checked = mutableListOf<String>()
    private var cached: File? = null

    private fun representative(): File = cached ?: Fixtures.otherBuilds().first().also { cached = it }

    /** The one build's classes that load the week limit, plus the extension. */
    private fun keptFrom(bundle: File): List<ClassDef> {
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { m -> m.code().any { (it as? WideLiteralInstruction)?.wideLiteral == WEEK_IN_MINUTES } }) {
                    kept += ImmutableClassDef.of(classDef)
                }
            }
        }
        return kept
    }

    private fun builds(bundle: File, label: String): List<String> {
        val context = PatchContexts.of(keptFrom(bundle) + ExtensionDex.classDef(LAST_ACTIVE))
        val site = context.findPresenceText()
        val before = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.isPresenceText() }.code()
            .map { it.opcode to it.reference() }
        context.showLastActive(site)
        val method = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.isPresenceText() }
        assertHook("$label ${site.type}->${site.name}", method, site, before)
        return listOf(label)
    }

    /**
     * The hook as four instructions in front of the first one past the second Active now check: the
     * exact-time call on the pair the time was read into, its answer in a borrowed local, a branch
     * to the original instruction when it's null, and the answer returned. Everything else is as
     * Instagram has it, in order.
     */
    private fun assertHook(what: String, method: Method, site: PresenceTextSite, before: List<Pair<Opcode, String?>>) {
        val code = method.code()
        assertEquals("$what: four instructions added", before.size + 4, code.size)
        assertEquals("$what: Instagram's code before the hook", before.take(site.index), code.take(site.index).map { it.opcode to it.reference() })
        assertEquals("$what: Instagram's code after the hook", before.drop(site.index), code.drop(site.index + 4).map { it.opcode to it.reference() })
        assertEquals("$what: the second Active now check comes right before", Opcode.IF_LEZ, code[site.index - 1].opcode)
        val call = code[site.index] as RegisterRangeInstruction
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$what: the hook", EXACT_ACTIVE, call.reference())
        assertEquals("$what: the time's first register", site.time, call.startRegister)
        assertEquals("$what: the time is a pair", 2, call.registerCount)
        val answer = (code[site.index + 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[site.index + 1].opcode)
        assertEquals("$what: the null branch", Opcode.IF_EQZ, code[site.index + 2].opcode)
        assertEquals("$what: the branch's register", answer, (code[site.index + 2] as OneRegisterInstruction).registerA)
        val target = (method.implementation!!.instructions.toList()[site.index + 2] as BuilderOffsetInstruction).target.location.index
        assertEquals("$what: null goes on to Instagram's own count", site.index + 4, target)
        assertEquals("$what: the early return", Opcode.RETURN_OBJECT, code[site.index + 3].opcode)
        assertEquals("$what: the register returned", answer, (code[site.index + 3] as OneRegisterInstruction).registerA)
        assertTrue("$what: the answer is a local, not a parameter or the time", answer < method.implementation!!.registerCount - 3 &&
            answer != site.time && answer != site.time + 1)
        assertEquals("$what: one hook", 1, code.count { it.reference() == EXACT_ACTIVE })
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun Method.isPresenceText() = parameterTypes.map(CharSequence::toString) == PRESENCE_TEXT_PARAMETERS && returnType == "Ljava/lang/String;"
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.wides(): List<Long> = code().mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        val BUILDS = listOf(385914283, 385914282, 385914284, 385914285)
    }
}
