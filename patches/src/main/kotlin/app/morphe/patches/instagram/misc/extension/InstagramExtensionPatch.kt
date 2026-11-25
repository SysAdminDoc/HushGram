/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The application class the manifest names. A manifest name is never obfuscated. */
internal const val INSTAGRAM_APPLICATION = "Lcom/instagram/app/InstagramAppShell;"

internal fun enforceInstagramRuntimeFloor(manifest: Document) {
    val root = manifest.documentElement
    if (root == null || root.tagName != "manifest") throw PatchException("HushGram: no manifest element")
    val declarations = manifest.getElementsByTagName("uses-sdk")
    if (declarations.length != 1) throw PatchException("HushGram: expected one uses-sdk declaration")
    val sdk = declarations.item(0) as Element
    if (sdk.parentNode !== root) throw PatchException("HushGram: uses-sdk is not a manifest child")
    val minimum = sdk.getAttribute("android:minSdkVersion").toIntOrNull()
        ?: throw PatchException("HushGram: the vendor minimum Android API is not numeric")
    if (minimum < 1) throw PatchException("HushGram: the vendor minimum Android API is invalid")
    if (minimum < AppCompatibilities.INSTAGRAM_RUNTIME_MIN_SDK) {
        sdk.setAttribute("android:minSdkVersion", AppCompatibilities.INSTAGRAM_RUNTIME_MIN_SDK.toString())
    }
}

private val instagramRuntimeFloorPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use(::enforceInstagramRuntimeFloor)
    }
}

/**
 * Merges the shared and Instagram extensions and hands them the application context.
 *
 * The context goes in at the start of the application's `onCreate()`, in whichever class of its
 * hierarchy declares one: every process Instagram starts builds this application, and the
 * settings, the pause switch and safe mode all read preferences through that context before any
 * hook asks for a switch.
 */
val instagramExtensionPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch(extensionName = "instagram", isYouTubeOrYouTubeMusic = false), instagramRuntimeFloorPatch)

    execute {
        handTheExtensionItsContext()
    }
}

/** What patching an APK HushGram already patched stops with (#864). */
internal const val ALREADY_PATCHED =
    "This APK is already patched by HushGram. Patch the original Instagram APK instead."

private const val CONTEXT_CALL = "$EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V"

/**
 * Hands the extension the application context, first thing in the application's onCreate.
 *
 * An APK HushGram already patched has that call there already, and is refused with a plain
 * [ALREADY_PATCHED] (#864). Patching it again would otherwise stop further on, at whichever patch
 * first meets its own hook from the first time, with an error that reads as if Instagram had
 * changed, such as the Reels pager holder "found 0".
 */
internal fun BytecodePatchContext.handTheExtensionItsContext() {
    // The chain starts at the application class itself and stops at the first framework class
    // the APK does not carry.
    val declaring = superclassChain(INSTAGRAM_APPLICATION)
        .mapNotNull { type -> mutableClassDefByOrNull(type) }
        .firstNotNullOfOrNull { classDef ->
            classDef.methods.singleOrNull { method ->
                method.name == "onCreate" && method.parameterTypes.isEmpty() &&
                    method.returnType == "V" && method.implementation != null
            }
        } ?: throw PatchException(
            "Neither $INSTAGRAM_APPLICATION nor a superclass in the APK declares onCreate()",
        )

    val handed = declaring.implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == CONTEXT_CALL
    } ?: false
    if (handed) throw PatchException(ALREADY_PATCHED)

    declaring.addInstruction(0, "invoke-static/range { p0 .. p0 }, $CONTEXT_CALL")
}
