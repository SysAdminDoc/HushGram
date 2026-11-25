/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An APK HushGram already patched is refused up front with a plain message (#864), and one it
 * hasn't is patched as before. Patching a patched APK again used to stop at the Reels patches with
 * a "found 0" that read as if Instagram had changed.
 */
class AlreadyPatchedTest {
    private val contextCall = "Lapp/hushgram/extension/shared/Utils;->setContext(Landroid/content/Context;)V"

    @Test
    fun aFreshOnCreateGetsTheContextOnceAndAPassOverItsOutputIsRefused() {
        val first = PatchContexts.of(listOf(application(onCreate(calls = 0))))
        first.handTheExtensionItsContext()
        assertEquals("the context first thing", contextCall, code(first).first())
        assertEquals(1, code(first).count { it == contextCall })

        val again = PatchContexts.of(listOf(ImmutableClassDef.of(first.mutableClassDefBy(INSTAGRAM_APPLICATION))))
        val failure = assertThrows(PatchException::class.java) { again.handTheExtensionItsContext() }
        assertEquals(ALREADY_PATCHED, failure.message)
        assertEquals("nothing more written", 1, code(again).count { it == contextCall })
    }

    /** The call anywhere in onCreate counts, not only first: a later patch may have put its own in front. */
    @Test
    fun theContextCallPastOtherHooksStillCounts() {
        val context = PatchContexts.of(listOf(application(onCreate(calls = 1, inFront = true))))
        val failure = assertThrows(PatchException::class.java) { context.handTheExtensionItsContext() }
        assertEquals(ALREADY_PATCHED, failure.message)
    }

    /**
     * Every stock build of the declared Instagram takes the context once, and the application the
     * first pass leaves, which is what a patched APK carries, is refused with the plain message.
     */
    @Test
    fun everyStockBuildPatchesOnceAndItsPatchedApplicationIsRefused() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals(Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val label = bundle.parentFile.name + "/" + bundle.name
            val stock = FixtureDex.classes(bundle, setOf(INSTAGRAM_APPLICATION)).values.toList()
            assertEquals("$label: the application class", 1, stock.size)
            assertTrue("$label: stock already hands the context over", code(stock.single()).none { it == contextCall })

            val first = PatchContexts.of(stock)
            first.handTheExtensionItsContext()
            assertEquals("$label: the context first thing", contextCall, code(first).first())

            val again = PatchContexts.of(listOf(ImmutableClassDef.of(first.mutableClassDefBy(INSTAGRAM_APPLICATION))))
            val failure = assertThrows(PatchException::class.java) { again.handTheExtensionItsContext() }
            assertEquals(label, ALREADY_PATCHED, failure.message)
        }
    }

    private fun code(context: BytecodePatchContext): List<String?> = code(context.mutableClassDefBy(INSTAGRAM_APPLICATION))

    private fun code(classDef: ClassDef): List<String?> =
        classDef.methods.single { it.name == "onCreate" && it.parameterTypes.isEmpty() }.implementation?.instructions
            ?.map { (it as? ReferenceInstruction)?.reference?.toString() } ?: emptyList()

    /** An application's onCreate handing the context over [calls] times, after another hook when [inFront]. */
    private fun onCreate(calls: Int, inFront: Boolean = false): Method {
        val body = buildString {
            if (inFront) appendLine("invoke-static { }, Lfixture/OtherHook;->atStart()V")
            repeat(calls) { appendLine("invoke-static/range { p0 .. p0 }, $contextCall") }
            appendLine("invoke-super { p0 }, Landroid/app/Application;->onCreate()V")
            appendLine("return-void")
        }
        val mutable = MutableMethod(
            ImmutableMethod(
                INSTAGRAM_APPLICATION, "onCreate", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun application(onCreate: Method): ClassDef =
        ImmutableClassDef(INSTAGRAM_APPLICATION, AccessFlags.PUBLIC.value, "Landroid/app/Application;", null, null, null, null, listOf(onCreate))
}
