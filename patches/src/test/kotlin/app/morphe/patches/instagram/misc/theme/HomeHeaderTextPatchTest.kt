/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.instagram.misc.settings.ENTRY
import app.morphe.patches.instagram.misc.settings.LOGO_BAR
import app.morphe.patches.instagram.misc.settings.LOGO_LONG_PRESS_STAND_IN
import app.morphe.patches.instagram.misc.settings.LOGO_TITLE
import app.morphe.patches.instagram.misc.settings.MERGED_LOGO_BAR
import app.morphe.patches.instagram.misc.settings.MERGED_LOGO_STAND_IN
import app.morphe.patches.instagram.misc.settings.MERGED_LOGO_VIEW
import app.morphe.patches.instagram.misc.settings.hookLogoLongPress
import app.morphe.patches.instagram.misc.settings.hookMergedLogoLongPress
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home header text (#454) adds no hook of its own: it counts on the settings patch handing either
 * header's logo to SettingsEntry, whose stand-ins pass the logo on to HeaderText. A build where
 * neither logo was hooked has nothing to dress.
 */
class HomeHeaderTextPatchTest {
    private val setLongPress = "Landroid/view/View;->setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V"
    private val headerText = "Lapp/hushgram/extension/instagram/misc/HeaderText;->dress(Landroid/view/View;)V"

    @Test
    fun eitherHeadersHookedLogoIsEnough() {
        val older = PatchContexts.of(listOf(olderBar(withLongPress = true)))
        assertFalse("nothing hooked yet", older.headerLogoHooked())
        assertTrue(older.hookLogoLongPress())
        assertTrue("the older header's logo", older.headerLogoHooked())

        val newer = PatchContexts.of(listOf(newerBar()))
        assertFalse("nothing hooked yet", newer.headerLogoHooked())
        assertTrue(newer.hookMergedLogoLongPress())
        assertTrue("the newer header's logo", newer.headerLogoHooked())
    }

    @Test
    fun noHeaderOrOneThatWasntHookedLeavesNothingToDress() {
        val bare = PatchContexts.of(listOf(olderBar(withLongPress = false)))
        PatchLogCapture.warnings { assertFalse(bare.hookLogoLongPress()) }
        PatchLogCapture.warnings { assertFalse(bare.hookMergedLogoLongPress()) }
        assertFalse(bare.headerLogoHooked())
    }

    /** Both stand-ins reach HeaderText: the older one calls it, the newer one goes through the older. */
    @Test
    fun theStandInsHandTheLogoToHeaderText() {
        val entry = ExtensionDex.classDef(ENTRY).methods
        fun calls(standIn: String): List<String> {
            val name = standIn.substringAfter("->").substringBefore("(")
            val method = entry.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
            return method.implementation?.instructions?.mapNotNull {
                (it as? ReferenceInstruction)?.reference?.toString()
            }.orEmpty()
        }
        assertTrue("the older header's stand-in dresses the logo", headerText in calls(LOGO_LONG_PRESS_STAND_IN))
        assertTrue("the newer header's stand-in goes through the older's", LOGO_LONG_PRESS_STAND_IN in calls(MERGED_LOGO_STAND_IN))
    }

    private fun olderBar(withLongPress: Boolean): ClassDef = classDef(
        LOGO_BAR,
        method(
            LOGO_BAR, "draw", listOf("Ljava/lang/Object;"), 2,
            if (withLongPress) {
                """
                    iget-object v1, p0, $LOGO_BAR->title:$LOGO_TITLE
                    const/4 v0, 0x0
                    invoke-virtual { v1, v0 }, $setLongPress
                    return-void
                """
            } else {
                """
                    iget-object v1, p0, $LOGO_BAR->title:$LOGO_TITLE
                    return-void
                """
            },
        ),
    )

    private fun newerBar(): ClassDef = classDef(
        MERGED_LOGO_BAR,
        method(
            MERGED_LOGO_BAR, "<init>", listOf("Landroid/content/Context;"), 2,
            """
                invoke-virtual { p0 }, $MERGED_LOGO_BAR->findLogo()Landroid/view/View;
                move-result-object v1
                check-cast v1, $MERGED_LOGO_VIEW
                iput-object v1, p0, $MERGED_LOGO_BAR->logo:$MERGED_LOGO_VIEW
                return-void
            """,
        ),
    )

    private fun method(owner: String, name: String, parameters: List<String>, registers: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(registers + 1 + parameters.size, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods.toList())
}
