/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.Fixtures
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extension hides the Search, Create and Profile tabs by the names Instagram's tab enum gives
 * them, so those names have to be there in every build, next to the ones Reels and Home use.
 */
class TabBarNamesTest {
    private val names = listOf("FEED", "SEARCH", "CREATION", "CLIPS", "DIRECT", "PROFILE")

    /** In each of the seven 450 builds the one tab enum names the tabs the switches and the start tab go by. */
    @Test
    fun everyBuildsTabEnumNamesTheTabsTheSwitchesGoBy() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the six others", 7, bundles.size)
        for (bundle in bundles) {
            val enums = FixtureDex.classesHolding(bundle, REELS_MODULE).filter { it.superclass == "Ljava/lang/Enum;" }
            val tabs = enums.filter { enum ->
                enum.methods.any { method -> method.name == "<clinit>" && strings(method).contains(REELS) }
            }
            assertEquals("${bundle.parentFile?.name ?: bundle.name}: tab enums", 1, tabs.size)
            val loaded = tabs.single().methods.filter { it.name == "<clinit>" }.flatMap { strings(it) }.toSet()
            for (name in names) assertTrue("${bundle.name}: the tab enum has no $name in $loaded", name in loaded)
        }
    }

    private fun strings(method: com.android.tools.smali.dexlib2.iface.Method): List<String> =
        method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
}
