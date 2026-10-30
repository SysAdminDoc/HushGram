/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the Friends tab on Reels (#300) hands the tab's view to the extension where Instagram builds
 * it. These hold the place the patch finds on every 452 build: one method handed the tab strip,
 * giving one tab the facepile icon, then reading that tab's view, which the hook gets right after.
 */
class FriendsTabFixtureTest {
    @Test
    fun theExtensionTakesTheTabsView() {
        val method = ExtensionDex.classDef(TOP_BAR).methods.single { it.name == "friendsTab" }
        assertEquals(listOf("Landroid/view/View;"), method.parameterTypes.map(Any::toString))
        assertEquals("V", method.returnType)
        assertTrue("public", AccessFlags.PUBLIC.isSet(method.accessFlags))
        assertTrue("static", AccessFlags.STATIC.isSet(method.accessFlags))
        assertEquals(FRIENDS_TAB_HOOK, "$TOP_BAR->friendsTab(Landroid/view/View;)V")
    }

    @Test
    fun eachDeclaredBuildMarksTheFriendsTabOnce() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                marksTheFriendsTab(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildMarksTheFriendsTabOnceToo() {
        val builds = Fixtures.otherBuilds()
        for (apk in builds) marksTheFriendsTab(apk, apk.parentFile.name)
        assertTrue("no other build was read", builds.isNotEmpty())
    }

    private fun marksTheFriendsTab(bundle: File, label: String) {
        val classes = friendsTabClasses(bundle)
        val context = PatchContexts.of(classes)
        val site = context.findFriendsTab()
        assertEquals("$label: the strip comes first", TAB_LAYOUT, site.method.parameterTypes.first().toString())
        val before = site.method.code()
        val icon = before[site.at - 2]
        assertEquals("$label: the icon", FRIENDS_TAB_ICON, (icon as NarrowLiteralInstruction).narrowLiteral)
        val read = before[site.at] as TwoRegisterInstruction
        assertEquals("$label: the view read off the tab", site.tab, read.registerB)
        val field = (read as ReferenceInstruction).reference as FieldReference
        assertTrue("$label: ${field.type} is a view", viewChain(classes, field.type))

        context.markFriendsTab(site)
        val after = context.mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name && it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString)
        }.code()
        assertEquals("$label: one instruction added", before.size + 1, after.size)
        val hook = after[site.at + 1]
        assertEquals("$label: the hook right after the read", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$label: the hook", FRIENDS_TAB_HOOK, (hook as ReferenceInstruction).reference.toString())
        hook as RegisterRangeInstruction
        assertEquals("$label: with the view", listOf(site.view), (hook.startRegister until hook.startRegister + hook.registerCount).toList())
        assertEquals("$label: the rest as it was", before.map { it.opcode }, after.filterIndexed { at, _ -> at != site.at + 1 }.map { it.opcode })

        // The same classes without the one building the tab: nothing to mark, so the patch fails.
        val without = PatchContexts.of(classes.filter { it.type != site.method.definingClass })
        val failure = assertThrows(PatchException::class.java) { without.findFriendsTab() }
        assertTrue("$label: ${failure.message}", failure.message!!.contains("found none"))
    }

    /**
     * The classes loading the facepile icon, from the dex files that name the tab strip (a method
     * taking it needs it there), and the classes up the chain of every field those methods read.
     */
    private fun friendsTabClasses(bundle: File): List<ClassDef> {
        val loading = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it == TAB_LAYOUT }) return@forEach
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == FRIENDS_TAB_ICON
                    } == true
                }
                if (loads) loading += ImmutableClassDef.of(classDef)
            }
        }
        val all = loading.associateBy { it.type }.toMutableMap()
        var wanted = loading.flatMap { it.methods }.flatMap { it.code() }
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type }
            .filter { it.startsWith("L") && !it.startsWith("Landroid/") && !it.startsWith("Ljava/") }.toSet() - all.keys
        repeat(10) {
            if (wanted.isEmpty()) return@repeat
            val found = FixtureDex.classes(bundle, wanted)
            all += found
            wanted = found.values.mapNotNull { it.superclass }.filter { !it.startsWith("Landroid/") && !it.startsWith("Ljava/") }
                .toSet() - all.keys
        }
        return all.values.toList()
    }

    private fun viewChain(classes: List<ClassDef>, type: String): Boolean {
        val byType = classes.associateBy { it.type }
        var at: String? = type
        repeat(10) {
            val current = at ?: return false
            if (current.startsWith("Landroid/")) return current in VIEWS
            at = byType[current]?.superclass
        }
        return false
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val TOP_BAR = "Lapp/hushgram/extension/instagram/reels/ReelsTopBar;"
        val VIEWS = setOf(
            "Landroid/view/View;", "Landroid/view/ViewGroup;", "Landroid/widget/LinearLayout;",
            "Landroid/widget/FrameLayout;", "Landroid/widget/RelativeLayout;",
        )
    }
}
