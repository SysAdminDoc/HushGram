/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val WHAT = "HushGram settings row"

internal const val CREATE_VIEW_PARAMETERS = "Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;"

/** The keys Instagram's settings screen fragment is made with: the screen to show, and a fresh session. */
internal const val SCREEN_ID = "screen_id"
internal const val NEW_SETTINGS_SESSION = "new_settings_session"

private fun Method.isCreateView() = name == "onCreateView" && returnType == "Landroid/view/View;" &&
    implementation != null && parameterTypes.joinToString("") == CREATE_VIEW_PARAMETERS

/**
 * The settings screen fragment: the one class with an onCreateView that a factory makes. A factory
 * is a method that loads [SCREEN_ID] and [NEW_SETTINGS_SESSION], the keys it puts in a new
 * instance's arguments, and makes something with `new-instance`. In 449 that's a static on the
 * screen's own class; 450 also inlines it into the caller that opens the screen. Instagram's string
 * tables hold both keys too, but make nothing. Redex asks a pool of shared strings for the keys in
 * some of these methods and not others, differently from build to build: 450's 385611400 loads
 * neither key itself in any of them (#77), so the keys are read through the pools as well.
 */
internal fun BytecodePatchContext.settingsScreenType(): String {
    val makers = classesLoadingString(NEW_SETTINGS_SESSION).flatMap { it.methods }.filter { method ->
        method.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE } == true &&
            loadsString(method, NEW_SETTINGS_SESSION) && loadsString(method, SCREEN_ID)
    }
    if (makers.isEmpty()) throw PatchException("$WHAT: expected a settings screen factory in this Instagram build, found none")
    val screens = makers.flatMap { method ->
        method.implementation!!.instructions
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type }
    }.distinct().filter { type -> classDefByOrNull(type)?.methods?.any { it.isCreateView() } == true }
    return screens.singleOrNull() ?: throw PatchException(
        "$WHAT: expected the settings screen factory to make exactly one screen with an onCreateView, found " +
            if (screens.isEmpty()) "none" else screens.joinToString(),
    )
}

/**
 * Puts the HushGram settings row at the top of Instagram's Settings and activity screen. That
 * screen's rows come from Instagram's server and are drawn in Compose, so the extension wraps the
 * view its fragment makes: before each `return-object` of `onCreateView`, the view goes through
 * SettingsEntry.withSettingsRow with the fragment's arguments, which name the screen shown.
 *
 * The first injected instruction takes the return's place, so a branch that jumped to the return
 * runs it too. Every name used is androidx's or the framework's.
 */
internal fun BytecodePatchContext.addSettingsRow() {
    val screen = mutableClassDefBy(settingsScreenType())
    val createView = screen.methods.singleOrNull { it.isCreateView() }
        ?: throw PatchException("$WHAT: ${screen.type} declares no onCreateView($CREATE_VIEW_PARAMETERS)")

    val returns = createView.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index }
    if (returns.isEmpty()) throw PatchException("$WHAT: ${screen.type}->onCreateView never returns a view")
    createView.requireThisIntact(WHAT, returns)

    val borrowed = returns.associateWith { index -> createView.freeLocalsAt(WHAT, index, 1).single() }
    returns.asReversed().forEach { index ->
        val view = createView.getInstruction<OneRegisterInstruction>(index).registerA
        if (view > 15) throw PatchException("$WHAT: ${screen.type}->onCreateView returns its view from v$view, past v15")
        val arguments = borrowed.getValue(index)
        createView.replaceInstruction(
            index,
            "invoke-virtual/range { p0 .. p0 }, Landroidx/fragment/app/Fragment;->getArguments()Landroid/os/Bundle;",
        )
        createView.addInstructions(
            index + 1,
            """
                move-result-object v$arguments
                invoke-static { v$arguments, v$view }, $ENTRY->withSettingsRow(Landroid/os/Bundle;Landroid/view/View;)Landroid/view/View;
                move-result-object v$view
                return-object v$view
            """,
        )
    }
}
