/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.Opcode

internal const val ENTRY = "$EXTENSION_PACKAGE/settings/SettingsEntry;"

/**
 * The activity every launcher alias of Instagram targets, and the one the settings shortcut opens.
 * A manifest name is never obfuscated.
 */
internal const val MAIN_ACTIVITY = "Lcom/instagram/mainactivity/InstagramMainActivity;"

/**
 * The first class in [type]'s hierarchy, as far as the APK carries it, that declares this
 * method with a body.
 */
internal fun BytecodePatchContext.declaredInHierarchy(
    type: String,
    name: String,
    vararg parameters: String,
): MutableMethod = superclassChain(type)
    .mapNotNull { mutableClassDefByOrNull(it) }
    .firstNotNullOfOrNull { classDef ->
        classDef.methods.singleOrNull { method ->
            method.name == name && method.returnType == "V" && method.implementation != null &&
                method.parameterTypes.map { it.toString() } == parameters.toList()
        }
    } ?: throw PatchException("No class of $type's hierarchy declares $name(${parameters.joinToString("")})V")

/**
 * Makes the HushGram screen reachable from a long-press shortcut on Instagram's launcher icon and
 * from a row at the top of Instagram's Settings and activity screen. The shortcut opens Instagram's
 * main activity with an extra, and the screen opens over it once it resumes. A default-off choice
 * also gives one native navigation tab's long press to the same settings entry. The row is found by
 * the strings its screen's factory puts in the arguments. Every other name used here is a manifest
 * component or a framework or androidx override or call. Nothing is added to the manifest.
 */
@Suppress("unused")
val settingsPatch = bytecodePatch(
    name = "HushGram settings",
    description = "Adds HushGram settings to Instagram. Long-press Instagram's launcher icon and pick HushGram " +
        "settings, or tap HushGram settings at the top of Instagram's Settings and activity. You can also choose one " +
        "tab whose long press opens HushGram. That choice starts off. Turn features on or off, pause HushGram and " +
        "export diagnostics. Most features start off, so this is where you turn on the ones you want. The licenses " +
        "are there too.",
    default = true,
) {
    category("Settings")
    dependsOn(instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        // The two binding variants and the returned view must be proved before startup is edited.
        val navigation = navigationEntryTargets()
        // Before each return of the application's onCreate, after Instagram's own startup, which
        // is when the shortcut is published and the activity callbacks that open the screen are
        // registered.
        val applicationOnCreate = declaredInHierarchy(INSTAGRAM_APPLICATION, "onCreate")
        val returns = applicationOnCreate.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
        if (returns.isEmpty()) throw PatchException("$INSTAGRAM_APPLICATION.onCreate never returns")
        returns.asReversed().forEach { index ->
            applicationOnCreate.addInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $ENTRY->onApplicationCreate(Landroid/content/Context;)V",
            )
        }

        // The main activity is singleTop, so a shortcut tap while it's on top arrives here and not
        // as a new activity. A fresh activity is seen by the lifecycle callbacks instead.
        declaredInHierarchy(MAIN_ACTIVITY, "onNewIntent", "Landroid/content/Intent;").addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $ENTRY->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V",
        )

        // Instagram publishes shortcuts of its own, and the newest push goes first, so the
        // HushGram shortcut could end up last, where a launcher that shows only a few cuts it off.
        // Each of those calls now goes through the extension, which puts it back in front
        // afterwards. Framework names only, which the obfuscator keeps.
        rerouteShortcutCalls()

        // A launcher that shows no shortcuts on a long press still gets there from Instagram's
        // own settings.
        addSettingsRow()
        preserveSettingsState()
        addNavigationEntry(navigation)
    }
}
