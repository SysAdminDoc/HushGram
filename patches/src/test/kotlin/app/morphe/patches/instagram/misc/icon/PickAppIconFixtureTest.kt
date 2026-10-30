/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.icon

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pick the app icon on every build of each declared Instagram version: every call Instagram makes to turn
 * a component on or off is one the patch can move, the one turning its launcher alias back on among
 * them, and after the patch each goes to AppIcon on the same registers.
 */
class PickAppIconFixtureTest {
    private fun builds(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } }
        return declared + Fixtures.otherBuilds()
    }

    /** Every call on PackageManager by one of [COMPONENT_CALLS]' names, whatever its form. */
    private fun Method.componentCalls() = (implementation?.instructions ?: emptyList()).filter {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        call?.definingClass == PACKAGE_MANAGER && call.name in COMPONENT_CALLS
    }

    @Test
    fun theStandInsAreInTheExtension() {
        val appIcon = componentStandIn(COMPONENT_CALLS.keys.first()).substringBefore("->")
        val declared = ExtensionDex.classDef(appIcon).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (name in COMPONENT_CALLS.keys) assertTrue("${componentStandIn(name)} is not in $declared", componentStandIn(name) in declared)
    }

    @Test
    fun eachBuildsComponentCallsAllMoveToAppIcon() {
        val extension = ExtensionDex.classDef(componentStandIn(COMPONENT_CALLS.keys.first()).substringBefore("->"))
        for (build in builds()) {
            val label = "${build.parentFile.name}/${build.name}"
            val callers = FixtureDex.methodsWhere(build, { dex -> dex.stringSection.any { it in COMPONENT_CALLS } }) {
                it.componentCalls().isNotEmpty()
            }
            val sites = callers.flatMap { it.componentCalls() }
            assertTrue("$label: no component call", sites.isNotEmpty())
            val odd = sites.filter { it.componentCall() == null }
            assertTrue("$label: calls the patch can't move, ${odd.map { "${it.opcode} ${(it as ReferenceInstruction).reference}" }}", odd.isEmpty())

            // Instagram's start logic turns the alias back on from a class that names it.
            val naming = FixtureDex.classesHolding(build, INSTAGRAM_ALIAS).map { it.type }.toSet()
            assertTrue("$label: no class naming $INSTAGRAM_ALIAS turns a component on or off", callers.any { it.definingClass in naming })

            val types = callers.map { it.definingClass }.toSet()
            val context = PatchContexts.of(FixtureDex.classes(build, types).values + extension)
            assertEquals("$label: every call moves", sites.size, context.routeComponentCalls())
            for (type in types) {
                for (method in context.mutableClassDefBy(type).methods) {
                    assertEquals("$label: $type->${method.name} still calls PackageManager", emptyList<Any>(), method.componentCalls())
                    val moved = (method.implementation?.instructions ?: emptyList())
                        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }
                        .filter { it.startsWith(extension.type) }
                    assertTrue("$label: $type->${method.name} calls ${moved - COMPONENT_CALLS.keys.map(::componentStandIn).toSet()}",
                        moved.all { it in COMPONENT_CALLS.keys.map(::componentStandIn) })
                }
            }
            val own = context.mutableClassDefBy(extension.type).methods.sumOf { it.componentCalls().size }
            assertTrue("$label: AppIcon's own calls stay", own > 0)
        }
    }

    @Test
    fun withoutTheStandInsOrAnyCallItRefuses() {
        val build = builds().first()
        val callers = FixtureDex.methodsWhere(build, { dex -> dex.stringSection.any { it in COMPONENT_CALLS } }) {
            it.componentCalls().isNotEmpty()
        }
        val classes = FixtureDex.classes(build, setOf(callers.first().definingClass)).values
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(classes).routeComponentCalls() }
        assertTrue(missing.message, missing.message.orEmpty().contains("the extension has no public static"))

        val extension = ExtensionDex.classDef(componentStandIn(COMPONENT_CALLS.keys.first()).substringBefore("->"))
        val none = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(extension)).routeComponentCalls() }
        assertTrue(none.message, none.message.orEmpty().contains("found no PackageManager.setComponentEnabledSetting"))
    }
}
