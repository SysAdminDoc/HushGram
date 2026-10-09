/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.adid

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * What Instagram 449 asks for to read the phone's advertising ID and to tell Android which ads you
 * saw or tapped: Google Play services' advertising ID, Android's own ad services ID, and Android's
 * attribution reporting.
 */
internal val AD_PERMISSIONS = listOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
)

/** The two elements a manifest asks for a permission with; the second only on Android 6 and later. */
private val REQUEST_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

/**
 * Takes every request for [AD_PERMISSIONS] out of [manifest], in either form. A manifest that doesn't
 * ask for all of them isn't one this was checked against, so it's refused before anything is removed.
 */
internal fun removeAdPermissions(manifest: Document) {
    val requests = REQUEST_TAGS.flatMap { tag ->
        manifest.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
    }
    val byName = AD_PERMISSIONS.associateWith { name -> requests.filter { it.getAttribute("android:name") == name } }
    val missing = byName.filterValues { it.isEmpty() }.keys
    if (missing.isNotEmpty()) {
        throw PatchException("Remove the advertising ID: AndroidManifest.xml doesn't ask for ${missing.joinToString()}")
    }
    byName.values.flatten().forEach { it.parentNode.removeChild(it) }
}

/**
 * A resource patch, so Manager decodes and rebuilds Instagram's resources to edit the manifest. A raw
 * resource patch would leave them alone, but in raw mode patcher 1.14.1 leaves the manifest as
 * AndroidManifest.xml.bin and doesn't write an edited copy back into the APK (checked on 449 with
 * the desktop CLI, 2026-09-30), and the ARSCLib the CLI ships lacks methods patcher 1.14.1's has.
 */
private val removeAdPermissionsPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use(::removeAdPermissions)
    }
}

/**
 * Takes the advertising ID and ad attribution away from Instagram.
 *
 * Google Play services hands an app that targets Android 13 or later a string of zeros in place of
 * the advertising ID unless its manifest asks for AD_ID, and Android's ad services refuse an app
 * without their permissions. Instagram 449 targets Android 16. Its one attribution call (a
 * MeasurementManager source registration) already catches the refusal, and its base code never
 * asks ad services for their ID, so nothing but the manifest changes.
 */
@Suppress("unused")
val removeAdIdPatch = bytecodePatch(
    name = "Remove the advertising ID",
    description = "Instagram can't read your phone's advertising ID or tell Android's ad services which ads you saw " +
        "or tapped. The permissions for them are taken out of the build, so Google Play services hands Instagram a " +
        "string of zeros in place of the ID. It's set when you patch, with no switch.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch, removeAdPermissionsPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        enableStatus("removeAdId")
    }
}
