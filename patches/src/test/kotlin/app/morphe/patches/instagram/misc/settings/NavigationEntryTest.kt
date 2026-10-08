/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.*
import org.junit.Test

class NavigationEntryTest {
    @Test fun hookMethodsArePublicAndStatic() {
        for (hook in listOf(NAV_REMEMBER, NAV_BIND, NAV_SET_LISTENER)) assertTrue(hook, ExtensionDex.classDef(NAVIGATION).methods.any {
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) &&
                "$NAVIGATION->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
        })
    }

    @Test fun bothNativeBindingsAndTheOneReturnedViewAreHooked() {
        val context = PatchContexts.of(NavigationEntryHosts.classes())
        val found = context.navigationEntryTargets()
        context.addNavigationEntry(found)
        val factory = context.mutableClassDefBy(MAIN_ACTIVITY).methods.single()
        assertEquals(1, factory.calls(NAV_BIND))
        for (setter in found.setters) {
            val patched = context.mutableClassDefBy(setter.definingClass).methods.single { it.name == setter.name }
            assertEquals(listOf(Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
                Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), patched.implementation!!.instructions.map { it.opcode })
            assertEquals(1, patched.calls(NAV_REMEMBER))
            assertEquals(3, patched.implementation!!.registerCount)
        }
    }

    @Test fun changedOwnersAndControlFlowRefuseBeforeAnyInstructionChanges() {
        val changes: List<(app.morphe.patcher.patch.BytecodePatchContext) -> Unit> = listOf(
            { context -> context.mutableClassDefBy(MAIN_ACTIVITY).methods.single().replaceInstruction(0, "move-object v2, p1") },
            { context -> context.mutableClassDefBy(MAIN_ACTIVITY).methods.single().addInstructions(2, "const/4 v2, 0") },
            { context -> context.mutableClassDefBy(MAIN_ACTIVITY).methods.single().addInstructions(2, "return-object v0") },
            { context -> context.mutableClassDefBy(NavigationEntryHosts.PLAIN).methods.single { it.name == "longPress" }
                .replaceInstruction(1, "invoke-virtual {v0, p0}, Landroid/view/View;->setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V") },
            { context -> context.mutableClassDefBy(NavigationEntryHosts.PLAIN).methods.single { it.name == "longPress" }
                .addInstructions(2, "const/4 v0, 0") },
            { context -> context.mutableClassDefBy(NavigationEntryHosts.PLAIN).methods.single { it.name == "view" }
                .replaceInstruction(0, "iget-object v0, p0, ${NavigationEntryHosts.PLAIN}->other:Landroid/view/View;") },
            { context -> context.mutableClassDefBy(MAIN_ACTIVITY).methods.single().apply {
                addInstructionsWithLabels(2, "if-eqz v0, :early_return", ExternalLabel("early_return", implementation!!.instructions.last()))
            } },
        )
        for (change in changes) {
            val context = PatchContexts.of(NavigationEntryHosts.classes())
            change(context)
            val before = snapshot(context)
            assertThrows(PatchException::class.java) { context.addNavigationEntry() }
            val after = snapshot(context)
            assertEquals(before, after)
        }
    }

    @Test fun aCleanupBranchToTheReturnStillReachesTheBindingCall() {
        val context = PatchContexts.of(NavigationEntryHosts.classes())
        val factory = context.mutableClassDefBy(MAIN_ACTIVITY).methods.single()
        val end = factory.implementation!!.instructions.last()
        factory.addInstructionsWithLabels(factory.implementation!!.instructions.size - 1,
            "if-eqz v0, :returned_view", ExternalLabel("returned_view", end))
        val found = context.navigationEntryTargets()
        context.addNavigationEntry(found)
        val branch = factory.implementation!!.instructions[found.returnIndex - 1] as BuilderOffsetInstruction
        assertEquals(found.returnIndex, branch.target.location.index)
        assertEquals(NAV_BIND, (factory.implementation!!.instructions[found.returnIndex] as ReferenceInstruction).reference.toString())
    }

    /**
     * 450's activity gives the Profile button its account switcher straight on the view as well
     * as through the proxy, and that one used to escape the choice (#82). It goes to the extension
     * in the call's own place and registers, so the branch to it still reaches it.
     */
    @Test fun theActivitysOwnLongPressGoesThroughTheExtension() {
        val context = PatchContexts.of(NavigationEntryHosts.classes(activityLongPress = true))
        val found = context.navigationEntryTargets()
        assertEquals(1, found.activityLongPresses.size)
        context.addNavigationEntry(found)
        val switcher = context.mutableClassDefBy(MAIN_ACTIVITY).methods.single { it.name == "showSwitcher" }
        val code = switcher.implementation!!.instructions
        val hook = code.indexOfFirst { (it as? ReferenceInstruction)?.reference.toString() == NAV_SET_LISTENER }
        val call = code[hook] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        assertEquals(listOf(2, 3), listOf(call.registerC, call.registerD))
        assertEquals(2, call.registerCount)
        assertEquals(1, switcher.calls(NAV_SET_LISTENER))
        assertEquals(0, switcher.calls(SET_LISTENER))
        assertEquals(hook, (code.first() as BuilderOffsetInstruction).target.location.index)
        assertEquals(4, switcher.implementation!!.registerCount)
        val factory = context.mutableClassDefBy(MAIN_ACTIVITY).methods.single { it.name == "makeTab" }
        assertEquals(1, factory.calls(NAV_BIND))
        assertEquals(0, factory.calls(NAV_SET_LISTENER))
    }

    @Test fun anActivityLongPressInAnotherFormRefusesBeforeAnyInstructionChanges() {
        val context = PatchContexts.of(NavigationEntryHosts.classes(activityLongPress = true))
        context.mutableClassDefBy(MAIN_ACTIVITY).methods.single { it.name == "showSwitcher" }
            .replaceInstruction(2, "invoke-virtual/range {p1 .. p2}, $SET_LISTENER")
        val before = snapshot(context)
        assertThrows(PatchException::class.java) { context.addNavigationEntry() }
        assertEquals(before, snapshot(context))
    }

    @Test fun original449ProvesBothVariantsAndPreservesAllNativeFactoryCalls() {
        val bundle = Fixtures.files { it.name == "instagram-449.0.0.52.84-385511871.apks" }.single()
        val tabs = FixtureDex.classesHolding(bundle, "clips_viewer_clips_tab").filter { it.superclass == "Ljava/lang/Enum;" }
        val main = FixtureDex.classes(bundle, setOf(MAIN_ACTIVITY)).getValue(MAIN_ACTIVITY)
        val factory = main.methods.single { method -> method.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference.toString() == TAB_FACTORY
        } == true }
        val getters = factory.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.returnType == NavigationEntryHosts.VIEW && it.parameterTypes.isEmpty() }
        val proxy = getters.single().definingClass
        val variants = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
        FixtureDex.forEach(bundle) { dex -> variants += dex.classes.filter { it.superclass == proxy }.map { ImmutableClassDef.of(it) } }
        val owners = FixtureDex.classes(bundle, setOf(proxy)).values
        val context = PatchContexts.of(tabs + listOf(main) + owners + variants)
        val found = context.navigationEntryTargets()
        val stockCalls = factory.implementation!!.instructions.filter { it.opcode.name.startsWith("invoke-") }
            .map { (it as ReferenceInstruction).reference.toString() }
        context.addNavigationEntry(found)
        val patched = context.mutableClassDefBy(MAIN_ACTIVITY).methods.single { it.name == factory.name && it.parameterTypes == factory.parameterTypes }
        assertEquals(stockCalls, patched.implementation!!.instructions.filter { it.opcode.name.startsWith("invoke-") }
            .map { (it as ReferenceInstruction).reference.toString() }.filter { it != NAV_BIND })
        assertEquals(2, found.setters.size)
        assertEquals(1, patched.calls(NAV_BIND))
    }

    private fun snapshot(context: app.morphe.patcher.patch.BytecodePatchContext): Map<String, List<String>> {
        val methods = mutableListOf<Method>()
        context.classDefForEach { methods += it.methods }
        return methods.associate { it.toString() to it.implementation!!.instructions.map { instruction -> instruction.toString() } }
    }

    private fun Method.calls(hook: String) = implementation!!.instructions.count {
        (it as? ReferenceInstruction)?.reference.toString() == hook
    }
}
