/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.quality

import app.morphe.ExtensionDex
import app.morphe.patches.instagram.feed.photos.LARGER_PHOTOS
import app.morphe.patches.instagram.feed.photos.fullResolutionPhotosPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Data saver rides on two other patches' hooks, so it has to bring them and they have to ask it. */
class DataSaverPatchTest {
    @Test
    fun itBringsBothPatchesItWorksThrough() {
        assertTrue(fullResolutionPhotosPatch in dataSaverPatch.dependencies)
        assertTrue(defaultPlaybackQualityPatch in dataSaverPatch.dependencies)
    }

    /** The size picker's hook and the video choice's hook each ask DataSaver, so the switch reaches both. */
    @Test
    fun bothHooksAskTheDataSaver() {
        val saver = ExtensionDex.classDef(DATA_SAVER)
        val saving = saver.methods.single { it.name == "saving" }
        assertEquals("()Z", saving.parameterTypes.joinToString("", "(", ")") + saving.returnType)
        assertTrue(AccessFlags.PUBLIC.isSet(saving.accessFlags) && AccessFlags.STATIC.isSet(saving.accessFlags))

        fun calls(type: String, method: String, owner: String, name: String) =
            ExtensionDex.classDef(type).methods.filter { it.name == method }.any { m ->
                m.implementation?.instructions?.any {
                    val called = (it as? ReferenceInstruction)?.reference as? MethodReference
                    called != null && called.definingClass == owner && called.name == name
                } == true
            }
        assertTrue("the size picker's hook", calls(LARGER_PHOTOS, "wanted", DATA_SAVER, "photoWidth"))
        // The video choice's hook hands the evaluator and the clock to starting, which asks DataSaver.
        assertTrue("the video choice's hook", calls(QUALITY_CHOICE, "firstChoice", QUALITY_CHOICE, "starting"))
        assertTrue("the video choice's start", calls(QUALITY_CHOICE, "starting", DATA_SAVER, "saving"))
    }
}
