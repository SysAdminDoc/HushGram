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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Shorter Active now (#683): on every 454 build, the presence formatter's static initializer hands
 * the Active now limit, 390 seconds, to the extension right before storing it, and nothing else in
 * the initializer changes. A build the patch can't read fails it before an instruction changes.
 */
class ShorterActiveNowHookTest {
    @Test
    fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(shorterActiveNowPatch.default)
    }

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(ACTIVE_NOW).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$ACTIVE_NOW->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$ACTIVE_NOW_WINDOW is not in the extension: $declared", ACTIVE_NOW_WINDOW in declared)
    }

    @Test
    fun everyBuildShortensTheLimit() {
        val checked = mutableListOf<String>()
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) checked += shortens(bundle, bundle.name)
        }
        for (base in Fixtures.otherBuilds()) checked += shortens(base, base.parentFile.name)
        assertTrue("no fixture was checked", checked.isNotEmpty())
    }

    @Test
    fun anExtensionWithoutTheHookFailsThePatch() {
        refuses(ACTIVE_NOW) { PatchContexts.of(emptyList()).findActiveNow() }
    }

    @Test
    fun noPresenceTextMethodFailsThePatch() {
        refuses("found 0") { PatchContexts.of(listOf(ExtensionDex.classDef(ACTIVE_NOW))).findActiveNow() }
    }

    /** A limit something besides the initializer could set can't be trusted to be the one Instagram keeps. */
    @Test
    fun aLimitThatIsNotFinalFailsThePatch() {
        val kept = keptFrom(Fixtures.otherBuilds().first())
        val site = PatchContexts.of(kept + ExtensionDex.classDef(ACTIVE_NOW)).findActiveNow()
        val owner = kept.single { it.type == site.type }
        val loosened = ImmutableClassDef(
            owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations,
            owner.fields.map {
                val flags = if (it.name == site.field.name) it.accessFlags and AccessFlags.FINAL.value.inv() else it.accessFlags
                ImmutableField(it.definingClass, it.name, it.type, flags, it.initialValue, it.annotations, it.hiddenApiRestrictions)
            },
            owner.methods,
        )
        val context = PatchContexts.of(kept.filter { it.type != owner.type } + loosened + ExtensionDex.classDef(ACTIVE_NOW))
        refuses("isn't static final") { context.findActiveNow() }
    }

    /** The one build's classes that load the week limit, plus nothing else. */
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

    private fun shortens(bundle: File, label: String): String {
        val context = PatchContexts.of(keptFrom(bundle) + ExtensionDex.classDef(ACTIVE_NOW))
        val site = runCatching { context.findActiveNow() }.getOrElse { throw AssertionError("$label: ${it.message}", it) }
        val clinit = { context.mutableClassDefBy(site.type).methods.single { it.name == "<clinit>" } }
        val before = clinit().code()

        // Read off the stock initializer apart from the finder: the one store of the limit, and
        // the 390 seconds it's worked out from.
        val stores = before.indices.filter { before[it].opcode == Opcode.SPUT_WIDE && before[it].reference() == site.field.toString() }
        assertEquals("$label: the initializer sets the limit once", listOf(site.index), stores)
        val register = (before[site.index] as OneRegisterInstruction).registerA
        val leadIn = before.take(site.index)
        assertTrue("$label: from seconds", leadIn.any { it.reference() == "Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;" })
        assertTrue("$label: 390 of them", leadIn.any { (it as? WideLiteralInstruction)?.wideLiteral == 390L })

        context.shortenActiveNow(site)

        val code = clinit().code()
        assertEquals("$label: two instructions added", before.size + 2, code.size)
        val stock = { list: List<Instruction> -> list.map { it.opcode to it.reference() } }
        assertEquals("$label: the initializer up to the store", stock(before.take(site.index)), stock(code.take(site.index)))
        assertEquals("$label: the store and after", stock(before.drop(site.index)), stock(code.drop(site.index + 2)))
        val call = code[site.index] as RegisterRangeInstruction
        assertEquals("$label: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$label: the hook", ACTIVE_NOW_WINDOW, call.reference())
        assertEquals("$label: on the limit's pair", register to 2, call.startRegister to call.registerCount)
        assertEquals("$label: the answer", Opcode.MOVE_RESULT_WIDE, code[site.index + 1].opcode)
        assertEquals("$label: into the same pair", register, (code[site.index + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: right after Instagram's own", Opcode.MOVE_RESULT_WIDE, code[site.index - 1].opcode)
        assertEquals("$label: and right before the store", Opcode.SPUT_WIDE, code[site.index + 2].opcode)
        assertEquals("$label: one hook", 1, code.count { it.reference() == ACTIVE_NOW_WINDOW })
        return label
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
