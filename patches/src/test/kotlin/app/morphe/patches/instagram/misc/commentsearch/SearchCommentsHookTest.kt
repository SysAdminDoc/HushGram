/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.commentsearch

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.theme.COMMENTS_CONTENT
import app.morphe.patches.instagram.misc.theme.COMMENTS_SCREEN_PREFIX
import app.morphe.patches.instagram.misc.theme.blackenCommentsScreens
import app.morphe.patches.instagram.misc.theme.findCommentsScreens
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchCommentsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(COMMENT_SEARCH_SHOWN.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_SEARCH_SHOWN is not in the extension: $declared", COMMENT_SEARCH_SHOWN.substringAfter("->") in declared)
    }

    /**
     * On every build, each comments screen's onViewCreated gets the search call before every
     * return, reading the screen and its view, with Pure black's call still first.
     */
    @Test
    fun eachBuildGetsTheSearchBeforeEveryReturn() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds: List<File> =
            Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } } + Fixtures.otherBuilds()
        for (build in builds) {
            val screens = mutableListOf<ClassDef>()
            FixtureDex.forEach(build) { dex ->
                for (classDef in dex.classes) {
                    val copy = ImmutableClassDef.of(classDef)
                    if (copy.originalName()?.startsWith(COMMENTS_SCREEN_PREFIX) == true) screens += copy
                }
            }
            val label = "${build.parentFile.name}/${build.name}"
            val context = PatchContexts.of(screens)
            val found = context.findCommentsScreens()
            val returns = found.associateWith { type ->
                screens.single { it.type == type }.methods.single { it.name == "onViewCreated" }
                    .implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID }
            }
            context.blackenCommentsScreens(found)
            context.addCommentSearch(found)
            for (type in found) {
                val method = context.mutableClassDefBy(type).methods.single { it.name == "onViewCreated" }
                val instructions = method.implementation!!.instructions.toList()
                val calls = instructions.withIndex().filter { isSearch(it.value) }
                assertEquals("$label/$type: one call per return", returns.getValue(type), calls.size)
                instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.forEach {
                    assertTrue("$label/$type: return ${it.index} has no call before it", isSearch(instructions[it.index - 1]))
                }
                val self = method.implementation!!.registerCount - 3
                for (call in calls) {
                    val range = call.value as RegisterRangeInstruction
                    assertEquals("$label/$type: reads this and the view", listOf(self, 2), listOf(range.startRegister, range.registerCount))
                }
                assertEquals("$label/$type: Pure black still first", COMMENTS_CONTENT, (instructions[0] as ReferenceInstruction).reference.toString())
                assertEquals("$label/$type: jumps that skip the call", 0, jumpsOntoAReturn(method))
            }
        }
    }

    /** A screen whose code reuses the view's register before returning would hand the extension something else, so it's refused. */
    @Test
    fun aScreenThatReusesTheViewRegisterIsRefused() {
        val context = PatchContexts.of(listOf(screen(ImmutableInstruction11n(Opcode.CONST_4, 1, 0), ImmutableInstruction10x(Opcode.RETURN_VOID))))
        assertThrows(PatchException::class.java) { context.addCommentSearch(context.findCommentsScreens()) }
    }

    /** A return something branches onto runs the call on that branch too. */
    @Test
    fun aBranchOntoAReturnRunsTheCall() {
        // if-eqz on the Bundle jumps straight onto the return, past the nop.
        val context = PatchContexts.of(
            listOf(screen(ImmutableInstruction21t(Opcode.IF_EQZ, 2, 3), ImmutableInstruction10x(Opcode.NOP), ImmutableInstruction10x(Opcode.RETURN_VOID))),
        )
        val type = context.findCommentsScreens().single()
        assertEquals(1, jumpsOntoAReturn(context.mutableClassDefBy(type).methods.single { it.name == "onViewCreated" }))
        context.addCommentSearch(listOf(type))
        val method = context.mutableClassDefBy(type).methods.single { it.name == "onViewCreated" }
        val instructions = method.implementation!!.instructions.toList()
        val branch = instructions.first() as OffsetInstruction
        val landing = instructions[addresses(instructions).indexOf(branch.codeOffset)]
        assertTrue("the branch lands on ${landing.opcode}, not the call", isSearch(landing))
        assertEquals(0, jumpsOntoAReturn(method))
    }

    /** A comments screen named the way Redex keeps it, with an onViewCreated of three registers: this, the view and the Bundle. */
    private fun screen(vararg code: Instruction): ClassDef {
        val type = "LX/0Test;"
        val name = ImmutableField(
            type, "__redex_internal_original_name", "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
            ImmutableStringEncodedValue("CommentListBottomsheetFragment"), null, null,
        )
        val viewCreated = ImmutableMethod(
            type, "onViewCreated",
            listOf(ImmutableMethodParameter("Landroid/view/View;", null, null), ImmutableMethodParameter("Landroid/os/Bundle;", null, null)),
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(3, code.toList(), null, null),
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, listOf(name), listOf(viewCreated))
    }

    private fun isSearch(instruction: Instruction) =
        (instruction as? ReferenceInstruction)?.reference?.toString() == COMMENT_SEARCH_SHOWN

    private fun addresses(instructions: List<Instruction>): List<Int> {
        var address = 0
        return instructions.map { instruction -> address.also { address += instruction.codeUnits } }
    }

    /** How many branches, switch cases and catch handlers in [method] land straight on a return-void. */
    private fun jumpsOntoAReturn(method: Method): Int {
        val implementation = method.implementation!!
        val instructions = implementation.instructions.toList()
        val addresses = addresses(instructions)
        val indexAt = addresses.withIndex().associate { (index, address) -> address to index }
        fun onReturn(target: Int) = indexAt[target]?.let { instructions[it].opcode == Opcode.RETURN_VOID } == true
        var jumps = 0
        instructions.forEachIndexed { index, instruction ->
            if (instruction !is OffsetInstruction) return@forEachIndexed
            val target = addresses[index] + instruction.codeOffset
            if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                val payload = instructions[indexAt.getValue(target)] as SwitchPayload
                jumps += payload.switchElements.count { onReturn(addresses[index] + it.offset) }
            } else if (onReturn(target)) {
                jumps++
            }
        }
        implementation.tryBlocks.forEach { block -> jumps += block.exceptionHandlers.count { onReturn(it.handlerCodeAddress) } }
        return jumps
    }
}
