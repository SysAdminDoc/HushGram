/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.shared.misc.extension

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An extension hook hands the context over in the one onCreate its fingerprint matches, and stops
 * the patch when an "ends with" class type matches two activities rather than taking the first.
 */
class ExtensionHookTest {
    private val setContext = "$EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V"

    @Test
    fun theOneMatchGetsTheContext() {
        val context = PatchContexts.of(listOf(activity("Lcom/example/MainActivity;")))
        with(context) { activityOnCreateExtensionHook("/MainActivity;")(EXTENSION_CLASS_DESCRIPTOR) }

        val onCreate = context.mutableClassDefBy("Lcom/example/MainActivity;").methods.single()
        val first = onCreate.implementation!!.instructions.first() as ReferenceInstruction
        assertEquals("the context goes over first", setContext, first.reference.toString())
    }

    @Test
    fun twoMatchesStopThePatch() {
        val context = PatchContexts.of(listOf(activity("Lcom/example/MainActivity;"), activity("Lcom/example/debug/MainActivity;")))
        val refusal = assertThrows(PatchException::class.java) {
            with(context) { activityOnCreateExtensionHook("/MainActivity;")(EXTENSION_CLASS_DESCRIPTOR) }
        }
        val message = refusal.message.orEmpty()
        assertTrue(message, message.contains("expected exactly one match") && message.contains("found 2"))
        assertTrue("the message names the fingerprint: $message", message.contains("/MainActivity;") && message.contains("onCreate"))
        for (type in listOf("Lcom/example/MainActivity;", "Lcom/example/debug/MainActivity;")) {
            val onCreate = context.mutableClassDefBy(type).methods.single()
            val calls = onCreate.implementation!!.instructions.count { (it as? ReferenceInstruction)?.reference?.toString() == setContext }
            assertEquals("$type is left as it was", 0, calls)
        }
    }

    private fun activity(type: String): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Landroid/app/Activity;", null, null, null, null, listOf(onCreate(type)))

    private fun onCreate(owner: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, "onCreate", listOf(ImmutableMethodParameter("Landroid/os/Bundle;", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        )
        mutable.addInstructions(0, "return-void")
        return ImmutableMethod.of(mutable)
    }
}
