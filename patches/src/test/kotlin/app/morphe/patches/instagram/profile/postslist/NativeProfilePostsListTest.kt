/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.postslist

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.profile.postslist.ProfilePostsListHookTest.Companion.assertResumeHook
import app.morphe.patches.instagram.profile.postslist.ProfilePostsListHookTest.Companion.code
import app.morphe.patches.instagram.profile.postslist.ProfilePostsListHookTest.Companion.reference
import app.morphe.patches.instagram.profile.postslist.ProfilePostsListHookTest.Companion.snapshot
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The posts tab hook on each of 450's seven builds: the one onResume it lands in, the classes the
 * extension leans on, and the grid cell's binder still taking the cell the extension looks for.
 */
class NativeProfilePostsListTest {
    @Test fun everyBuildHandsThePostsTabToTheExtensionFirstInItsOnResume() {
        val bundles = declared() + Fixtures.otherBuilds()
        val checked = mutableListOf<String>()
        for (bundle in bundles) {
            val name = bundle.absolutePath
            val classes = load(bundle) { types -> FixtureDex.classes(bundle, types) }
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(POSTS_LIST))
            val tab = classes.getValue(PROFILE_MEDIA_TAB)
            val original = tab.methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" }
            val key = original.toString()
            val before = snapshot(tab.methods.filter { it.toString() != key })
            context.tellOnResume()
            val methods = context.mutableClassDefBy(PROFILE_MEDIA_TAB).methods
            assertResumeHook(methods.single { it.toString() == key }, original.code())
            assertEquals("$name: the tab's other methods changed", before, snapshot(methods.filter { it.toString() != key }))
            for (other in classes.values.filter { it.type != PROFILE_MEDIA_TAB }) {
                assertEquals("$name: ${other.type} changed", snapshot(other.methods), snapshot(context.mutableClassDefBy(other.type).methods))
            }
            assertTrue("$name: the tab calls up to its superclass's onResume", original.code().any { it.reference()?.endsWith(";->onResume()V") == true })

            // A grid post is bound to the cell class the extension looks for, along with the tap listener that opens the list.
            val binders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == GRID_CELL } }) { method ->
                val parameters = method.parameterTypes.map(Any::toString)
                GRID_CELL in parameters && MEDIA in parameters &&
                    method.code().any { it.reference()?.contains("(Landroid/view/View\$OnClickListener;") == true }
            }
            assertTrue("$name: a method taking a post and its grid cell hands the cell a tap listener", binders.isNotEmpty())
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of 450 build $code among $checked", checked.any { it.contains("-$code") })
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameOnResume() {
        val bundles = declared()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        for (bundle in bundles) reReadsResolve(bundle)
    }

    private fun reReadsResolve(bundle: File) {
        val classes = load(bundle) { types -> FixtureDex.classesAsRead(bundle, types) }
        val context = PatchContexts.of(classes.values + ExtensionDex.classDef(POSTS_LIST))
        val found = context.findPostsTabResume()
        val original = found.code()
        context.tellOnResume()
        assertResumeHook(found, original)
    }

    private companion object {
        val BUILDS = listOf(385611395, 385611400, 385611404, 385611431, 385611438, 385611439, 385611440)
        const val MEDIA = "Lcom/instagram/feed/media/Media;"

        /** The bundles of the builds the patches declare, as the other native tests read them. */
        fun declared(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        }

        /** The tab, every superclass it has in the APK up to Fragment, the tab controller and the grid cell. */
        fun load(bundle: File, read: (Set<String>) -> Map<String, ClassDef>): Map<String, ClassDef> {
            val supers = HashMap<String, String?>()
            FixtureDex.forEach(bundle) { dex -> for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass) }
            val chain = mutableSetOf<String>()
            var next = supers[PROFILE_MEDIA_TAB]
            while (next != null && next in supers && chain.add(next)) next = supers[next]
            assertTrue("${bundle.name}: the posts tab is a Fragment", FRAGMENT in chain)
            val classes = read(setOf(PROFILE_MEDIA_TAB, PROFILE_TAB_CONTROLLER, GRID_CELL) + chain)
            assertEquals(bundle.name, 3 + chain.size, classes.size)
            assertTrue("${bundle.name}: the tab controller names the posts tab", classes.getValue(PROFILE_TAB_CONTROLLER).methods.any { method ->
                method.code().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == POSTS_TAB }
            })
            return classes
        }
    }
}
