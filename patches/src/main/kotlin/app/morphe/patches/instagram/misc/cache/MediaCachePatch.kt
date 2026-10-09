/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.cache

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

internal const val MEDIA_CACHE_PATCH = "Clear the media cache"

internal const val MEDIA_CACHE = "$EXTENSION_PACKAGE/misc/MediaCache;"
internal const val BEFORE_VIDEO_CACHE = "$MEDIA_CACHE->beforeVideoCache(Ljava/lang/String;)V"

/** The video cache's three folders, each named under the folder the method is handed. */
internal val VIDEO_CACHE_FOLDERS = listOf(
    "/ExoPlayerCacheDir/videocache",
    "/ExoPlayerCacheDir/videoprefetchcache",
    "/ExoPlayerCacheDir/videocachemetadata",
)

/** The static method that names the video cache's folders: a kind and the folder they go under. */
internal object VideoCacheFolderFingerprint : Fingerprint(
    returnType = "Ljava/io/File;",
    parameters = listOf("Ljava/lang/Integer;", "Ljava/lang/String;"),
    strings = VIDEO_CACHE_FOLDERS,
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Turns on the extension's cache cleaner. The settings patch already calls into the extension once
 * Instagram's application starts, and from there MediaCache hears each time Instagram goes to the
 * background, through Android's own memory callbacks, and deletes the images. The one hook here is
 * for the videos: the method that names the video cache's folders tells the extension first, and
 * the first time in a process, before the video player has built its cache, a clear that was asked
 * for moves the video cache aside.
 */
@Suppress("unused")
val clearMediaCachePatch = bytecodePatch(
    name = "Clear the media cache",
    description = "Frees storage by deleting Instagram's saved copies of photos and videos once they pass 500 MB. " +
        "Your sign-in, drafts and settings stay. A row clears them right away. Starts off. Turn it on in " +
        "HushGram settings > Storage.",
    default = true,
) {
    category("Settings")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("mediaCache")
        clearVideosAtStart()
        enableStatus("mediaCache")
    }
}

/** Puts the extension's call first in the method that names the video cache's folders, with the folder it's handed. */
internal fun BytecodePatchContext.clearVideosAtStart() {
    val folders = uniqueMethod(MEDIA_CACHE_PATCH, "method naming the video cache's folders", VideoCacheFolderFingerprint)
    if (folders.implementation == null) throw PatchException("$MEDIA_CACHE_PATCH: the method naming the video cache's folders has no code")
    if (0 in folders.jumpTargets()) {
        throw PatchException("$MEDIA_CACHE_PATCH: a jump or exception handler enters the method naming the video cache's folders at its first instruction")
    }
    val folder = folders.parameterRegister(1)
    folders.addInstruction(0, "invoke-static/range { $folder .. $folder }, $BEFORE_VIDEO_CACHE")
}
