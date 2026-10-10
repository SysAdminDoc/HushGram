/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.ELIGIBLE_MARKER
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.video.FEED_HELPER_NAME
import app.morphe.patches.instagram.download.video.WHY_OPTION
import app.morphe.patches.instagram.download.video.isShortMenuList
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide posts from this account, the row Hide suggested posts adds to a post's menu: the hooks are in
 * the extension, a build without the feed menu changes nothing, and in every declared 450 build the
 * row's call goes where anyone else's rows start, before each return of the short menu's list and
 * in front of the menu's handler, with the row's stubs filled.
 */
class HideAccountRowHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(OFFER_HIDE, ALLOW_HIDE, HIDE_OPTION, HIDE_TAPPED)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        assertTrue(
            "HiddenAccounts.authorOfPost is not in the extension",
            ExtensionDex.classDef(HIDDEN_ACCOUNTS).methods.any { it.name == "authorOfPost" && AccessFlags.STATIC.isSet(it.accessFlags) },
        )
    }

    /** A build with no feed menu keeps Hide suggested posts as it was and leaves the row's stubs alone. */
    @Test
    fun aBuildWithoutTheFeedMenuChangesNothing() {
        val row = ExtensionDex.classDef(HIDE_ROW)
        val context = PatchContexts.of(listOf(row))
        val before = row.methods.associate { it.name to it.code().size }

        assertNull(context.hideAccountRowOrWarn())

        val after = context.classDefBy(HIDE_ROW).methods.associate { it.name to it.code().size }
        assertEquals(before, after)
    }

    @Test
    fun eachDeclaredBuildGetsTheRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                getsTheRow(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGetsTheRow() {
        for (apk in Fixtures.otherBuilds()) getsTheRow(apk, apk.parentFile.name)
    }

    private fun getsTheRow(bundle: File, label: String) {
        val classes = mutableListOf<ClassDef>(ExtensionDex.classDef(HIDE_ROW))
        FixtureDex.forEach(bundle) { dex ->
            val marked = dex.stringSection.any { it.startsWith("android_purge_") && PURGE_MARKER.find(it)?.groupValues?.get(1) == ELIGIBLE_MARKER }
            val loads = dex.fieldSection.any { it.toString() == DOWNLOAD }
            val named = dex.stringSection.any { it == FEED_HELPER_NAME }
            val options = dex.fieldSection.any { it.toString() == WHY_OPTION }
            if (!marked && !loads && !named && !options && dex.classes.none { it.type == OPTION }) return@forEach
            for (classDef in dex.classes) {
                val wanted = classDef.type == OPTION || classDef.originalName() == FEED_HELPER_NAME || classDef.methods.any { method ->
                    ELIGIBLE_MARKER in method.markers() || method.code().any { it.referenceText() == DOWNLOAD } || method.isShortMenuList()
                }
                if (wanted) classes += ImmutableClassDef.of(classDef)
            }
        }
        // The static methods R8 outlined `new ArrayList()` and getString into, in a build that has them.
        val outlined = classes.asSequence().flatMap { it.methods.asSequence() }.flatMap { it.code().asSequence() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { (it.returnType == "Ljava/util/ArrayList;" && it.parameterTypes.isEmpty()) ||
                (it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/res/Resources;", "I")) }
            .map { it.definingClass }.filter { type -> classes.none { it.type == type } }.toSet()
        if (outlined.isNotEmpty()) classes += FixtureDex.classes(bundle, outlined).values
        val context = PatchContexts.of(classes)

        val row = context.hideAccountRowOrWarn()
        assertNotNull("$label: the row was not found", row)
        row!!.write()

        HideRowAsserts.assertRowWritten(context, label)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
