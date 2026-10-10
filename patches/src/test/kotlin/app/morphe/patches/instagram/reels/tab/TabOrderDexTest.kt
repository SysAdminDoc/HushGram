/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tab order choice reorders the copy of the tab list the tab list hook hands back, which the
 * tab host keeps. That's only safe while nothing takes a tab out of the host's lists by a fixed
 * position, as code that assumed Home is first would. On 450 the host's lists are read by
 * iterating them (the bar's buttons, the pager's pages), by indexOf (a tab's place), and by a page
 * position the pager hands over, which follows the same list. This is the net for the fixed kind: a
 * get(int) whose index is a constant, in any method that reads one of the host's List fields.
 */
class TabOrderDexTest {
    private val constants = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
    private val lists = setOf(LIST, "Ljava/util/ArrayList;", "Ljava/util/AbstractList;", "Ljava/util/AbstractCollection;")

    @Test
    fun noReaderOfTheTabHostsListsTakesATabByAFixedPosition() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the six others", 7, bundles.size)
        for (bundle in bundles) {
            val name = bundle.parentFile?.name ?: bundle.name
            val enums = FixtureDex.classesHolding(bundle, REELS_MODULE).filter { it.superclass == "Ljava/lang/Enum;" }
            val hosts = FixtureDex.classesHolding(bundle, TAB_HOST_STATE)
            val builderTypes = hosts.flatMap { it.methods }.filter { it.name == "<init>" }.flatMap { it.code() }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .filter { it.returnType == LIST && SESSION in it.parameterTypes.map(CharSequence::toString) }
                .map { it.definingClass }.toSet()
            val builders = FixtureDex.classes(bundle, builderTypes).values
            val context = PatchContexts.of((enums + hosts + builders).distinctBy { it.type })
            val host = context.findReelsTab().switch.definingClass
            val fields = hosts.single { it.type == host }.fields.filter { it.type == LIST }.map { it.name }.toSet()
            assertTrue("$name: the tab host keeps its tabs in a List", fields.isNotEmpty())

            val reads = { method: Method ->
                method.code().any { instruction ->
                    instruction.opcode == Opcode.IGET_OBJECT && instruction.fieldReference()?.let { it.definingClass == host && it.name in fields } == true
                }
            }
            val readers = FixtureDex.methodsWhere(
                bundle,
                { dex -> dex.fieldSection.any { it.definingClass == host && it.type == LIST } },
                reads,
            )
            assertTrue("$name: only ${readers.size} methods read the host's lists, so the scan found nothing", readers.size >= 3)
            val fixed = readers.flatMap { method -> fixedGets(method).map { "${method.definingClass}->${method.name} at $it" } }
            assertEquals("$name: these take a tab by a fixed position", emptyList<String>(), fixed)
        }
    }

    /** The indexes of the get(int) calls in [method] whose index register was last written with a constant. */
    private fun fixedGets(method: Method): List<Int> {
        val code = method.code()
        return code.indices.filter { at ->
            val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
            if (call.name != "get" || call.definingClass !in lists || call.parameterTypes.map(CharSequence::toString) != listOf("I")) {
                return@filter false
            }
            val index = when (val invoke = code[at]) {
                is FiveRegisterInstruction -> invoke.registerD
                is RegisterRangeInstruction -> invoke.startRegister + 1
                else -> return@filter false
            }
            val written = code.subList(0, at).lastOrNull { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == index }
            written != null && written.opcode in constants
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
}
