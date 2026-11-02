/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.LOGO_BAR
import app.morphe.patches.instagram.misc.settings.LOGO_LONG_PRESS_STAND_IN
import app.morphe.patches.instagram.misc.settings.MERGED_LOGO_BAR
import app.morphe.patches.instagram.misc.settings.MERGED_LOGO_STAND_IN
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The settings patch's logo hooks, which hand the header's logo view to the extension. */
private val HEADER_LOGO_HOOKS = setOf(LOGO_LONG_PRESS_STAND_IN, MERGED_LOGO_STAND_IN)

/**
 * Whether the settings patch hooked a Home header's logo: a method of [LOGO_BAR] or [MERGED_LOGO_BAR],
 * as it stands now, calls one of its stand-ins. Those stand-ins dress the logo with the header text
 * or picture, so without one the rows would change nothing.
 */
internal fun BytecodePatchContext.headerLogoHooked(): Boolean = listOf(LOGO_BAR, MERGED_LOGO_BAR).any { type ->
    classDefByOrNull(type) != null && mutableClassDefBy(type).methods.any { method ->
        method.implementation?.instructions?.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            call != null && "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" in
                HEADER_LOGO_HOOKS
        } == true
    }
}

/**
 * Words of your own, or a picture, at the top of Home in place of Instagram's logo (#454). Included
 * in the default selection with nothing set, so Home looks as it did until a row is used.
 *
 * Nothing new is hooked. The settings patch already hands both headers' logo views to SettingsEntry
 * for the long press that opens the HushGram screen, and the extension's HeaderText puts the text
 * or picture on the same view, checking before each draw so a change shows at once. A build where
 * neither hook went in has no logo to dress, so the patch fails there instead of adding rows that
 * do nothing.
 */
@Suppress("unused")
val homeHeaderTextPatch = bytecodePatch(
    name = "Home header text",
    description = "Puts words of your own, or a picture, at the top of Home in place of Instagram's logo. " +
        "Nothing changes until you set one in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("headerText")
        if (!headerLogoHooked()) {
            throw PatchException(
                "Home header text: the settings patch found no logo at the top of Home in this build, so there's " +
                    "nothing to put the text on",
            )
        }
        enableStatus("headerText")
    }
}
