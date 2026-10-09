/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.versioncode

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Document

/**
 * Raises the version code in [manifest] from [real] to [HIGHEST_VERSION_CODE]. Refused before
 * anything changes when the manifest has no such code, a different one than the patcher read, or
 * a versionCodeMajor, whose high half would put the build past the highest code anyway.
 */
internal fun raiseVersionCode(manifest: Document, real: Int) {
    val root = manifest.documentElement
    if (root == null || root.tagName != "manifest") refuse("AndroidManifest.xml has no manifest element")
    if (root.hasAttribute("android:versionCodeMajor")) refuse("AndroidManifest.xml sets a versionCodeMajor")
    val code = root.getAttribute("android:versionCode")
    if (code.trim().toIntOrNull() != real) refuse("AndroidManifest.xml says its version code is \"$code\", not $real")
    root.setAttribute("android:versionCode", HIGHEST_VERSION_CODE.toString())
}

/**
 * Sends Instagram's reads of its own version code through the extension, which answers the code
 * Meta built while the manifest says [HIGHEST_VERSION_CODE]. It runs before the manifest half, so
 * a build whose reads it can't prove keeps Meta's version code: the manifest changes only once its
 * own checks are taken care of.
 */
private val versionCodeReadsPatch = bytecodePatch {
    dependsOn(settingsPatch, instagramExtensionPatch)

    execute {
        val prepared = prepareVersionCode(packageMetadata.packageName, realVersionCode(packageMetadata.versionCode))
        applyVersionCode(prepared)
    }
}

/**
 * Raises Instagram's version code to the highest Android takes, so Google Play stops offering
 * Meta's updates over the patched build (#68).
 *
 * A resource patch, so Manager decodes the manifest to edit it (see Remove the advertising ID), and
 * the named half, so it runs after the bytecode half it depends on: if that half refuses, this one
 * doesn't run and the manifest keeps Meta's code.
 */
@Suppress("unused")
val changeVersionCodePatch = resourcePatch(
    name = "Change version code",
    description = "Raises this build's version code to the highest Android allows, so Google Play stops offering " +
        "Meta's updates over it. Instagram's checks against the version it was built as still see the real one. Once " +
        "it's in, going back to an unpatched Instagram means uninstalling first, which deletes Instagram's data on " +
        "your phone, and later HushGram builds need this patch too or they won't install over this one. It's set " +
        "when you patch, with no switch.",
    default = false,
) {
    category("Updates")
    dependsOn(settingsPatch, instagramExtensionPatch, versionCodeReadsPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        val real = realVersionCode(packageMetadata.versionCode)
        document("AndroidManifest.xml").use { raiseVersionCode(it, real) }
    }
}
