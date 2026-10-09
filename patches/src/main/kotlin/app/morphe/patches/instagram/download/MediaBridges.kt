/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Instagram's post, reel or story, which keeps its name. */
internal const val MEDIA = "Lcom/instagram/feed/media/Media;"
internal const val USER = "Lcom/instagram/user/model/User;"
internal const val VIDEO_VERSION = "Lcom/instagram/api/schemas/VideoVersionIntf;"
internal const val PANDO_VIDEO_VERSION = "Lcom/instagram/api/schemas/ImmutablePandoVideoVersion;"

/** A picture's sizes, and the tree-backed class that reads them. */
internal const val IMAGE_INFO = "Lcom/instagram/model/mediasize/ImageInfo;"
internal const val PANDO_IMAGE_INFO = "Lcom/instagram/model/mediasize/ImmutablePandoImageInfo;"

/** One size of a picture. Its getters keep their names. */
internal const val IMAGE_URL = "Lcom/instagram/common/typedurl/ImageUrl;"

/** An account's full size picture. Its getters keep their names too. */
internal const val PROFILE_PICTURE_INFO = "Lcom/instagram/api/schemas/ProfilePicUrlInfo;"

/** The extension's bridges to Instagram's media model, whose bodies [mediaBridges] and [imageBridges] write. */
internal const val INSTAGRAM_MEDIA = "$EXTENSION_PACKAGE/download/InstagramMedia;"

/**
 * Instagram's getter on [type] for the model field [field]: the one method taking nothing and
 * answering [returns] that loads the field's key. Instagram's models read a field from the tree
 * the server sent by the Java hash of its name, so a getter holds that hash as a constant however
 * the release shortened the getter's own name. None, or two, stop the patch naming [patch].
 */
internal fun BytecodePatchContext.pandoGetter(patch: String, type: String, field: String, returns: String): Method {
    val key = field.hashCode()
    val getters = classDefBy(type).methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType == returns && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { it.loadsLiteral(key) } == true
    }
    return getters.singleOrNull() ?: throw PatchException(
        "$patch: expected one getter on $type answering $returns for $field, found " +
            if (getters.isEmpty()) "none" else getters.joinToString { it.name },
    )
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.loadsLiteral(value: Int): Boolean =
    (opcode == Opcode.CONST || opcode == Opcode.CONST_16 || opcode == Opcode.CONST_HIGH16 || opcode == Opcode.CONST_4) &&
        (this as NarrowLiteralInstruction).narrowLiteral == value

/**
 * One bridge: the extension method [name], and the Instagram call [call] its body makes on the
 * argument cast to [receiver]. [primitive] when the call answers an int rather than an object.
 */
private class Bridge(val name: String, val receiver: String, val call: String, val primitive: Boolean = false)

/**
 * Finds what the body of each of the extension's InstagramMedia bridges calls, and answers the
 * step that writes them: the argument cast to Instagram's type and handed to the getter that reads
 * the field, found by [pandoGetter] or, for the media's id, by its kept name. Everything is found
 * here, so a build where one is missing stops the patch naming [patch] before anything changes.
 *
 * Each body uses its parameter register alone, so the stub's own register count doesn't matter,
 * and goes in first, ahead of the stub's own `return null`, which is then never reached.
 */
internal fun BytecodePatchContext.mediaBridges(patch: String): () -> Unit {
    val videoVersions = pandoGetter(patch, MEDIA, "video_versions", "Ljava/util/List;")
    val dashManifest = pandoGetter(patch, MEDIA, "video_dash_manifest", "Ljava/lang/String;")
    val owner = pandoGetter(patch, MEDIA, "user", USER)
    val takenAt = pandoGetter(patch, MEDIA, "taken_at", "Ljava/lang/Long;")
    val username = pandoGetter(patch, USER, "username", "Ljava/lang/String;")
    val mediaId = classDefBy(MEDIA).methods.singleOrNull {
        it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("$patch: $MEDIA has no getId()")
    // A version comes as either of Instagram's two classes, so it's read through their interface,
    // by the name the tree-backed class gives each getter.
    fun version(field: String, returns: String) = throughInterface(patch, VIDEO_VERSION, PANDO_VIDEO_VERSION, field, returns)

    return bridgeWriter(patch, listOf(
        Bridge("videoVersions", MEDIA, virtual(videoVersions)),
        Bridge("dashManifest", MEDIA, virtual(dashManifest)),
        Bridge("mediaId", MEDIA, virtual(mediaId)),
        Bridge("owner", MEDIA, virtual(owner)),
        Bridge("takenAt", MEDIA, virtual(takenAt)),
        Bridge("username", USER, virtual(username)),
        Bridge("versionUrl", VIDEO_VERSION, version("url", "Ljava/lang/String;")),
        Bridge("versionWidth", VIDEO_VERSION, version("width", "Ljava/lang/Integer;")),
        Bridge("versionHeight", VIDEO_VERSION, version("height", "Ljava/lang/Integer;")),
    ))
}

/**
 * The same for a picture's bridges: a Media's sizes, the candidates among them, and each
 * candidate's address and size, read through the interface every candidate implements.
 */
internal fun BytecodePatchContext.imageBridges(patch: String): () -> Unit {
    val versions = pandoGetter(patch, MEDIA, "image_versions2", IMAGE_INFO)
    val candidates = throughInterface(patch, IMAGE_INFO, PANDO_IMAGE_INFO, "candidates", "Ljava/util/List;")
    val candidate = anInterface(patch, IMAGE_URL).methods
    fun kept(name: String, returns: String): String {
        if (candidate.none { it.name == name && it.parameterTypes.isEmpty() && it.returnType == returns }) {
            throw PatchException("$patch: $IMAGE_URL has no $name()$returns")
        }
        return "invoke-interface {p0}, $IMAGE_URL->$name()$returns"
    }
    return bridgeWriter(patch, listOf(
        Bridge("imageVersions", MEDIA, virtual(versions)),
        Bridge("imageCandidates", IMAGE_INFO, candidates),
        Bridge("candidateUrl", IMAGE_URL, kept("getUrl", "Ljava/lang/String;")),
        Bridge("candidateWidth", IMAGE_URL, kept("getWidth", "I"), primitive = true),
        Bridge("candidateHeight", IMAGE_URL, kept("getHeight", "I"), primitive = true),
    ))
}

/**
 * The same for an account's picture: the one its profile shows, read as a picture's candidate, and
 * its full size, read through the interface both of Instagram's classes for it implement, whose
 * getters keep their names. The account's username names the save, and it and the account's bio
 * are what Copy username and Copy bio copy. Only Copy bio reads the bio, so a build where its
 * getter can't be told leaves that bridge answering null, after the patch log says why, and the
 * patch goes in without Copy bio.
 */
internal fun BytecodePatchContext.profilePictureBridges(patch: String): () -> Unit {
    val shown = pandoGetter(patch, USER, "profile_pic_url", IMAGE_URL)
    val full = pandoGetter(patch, USER, "hd_profile_pic_url_info", PROFILE_PICTURE_INFO)
    val username = pandoGetter(patch, USER, "username", "Ljava/lang/String;")
    val biography = try {
        pandoGetter(patch, USER, "biography", "Ljava/lang/String;")
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $patch goes in without Copy bio.")
        null
    }
    return bridgeWriter(patch, listOfNotNull(
        Bridge("profilePicture", USER, virtual(shown)),
        Bridge("fullSizeProfilePicture", USER, virtual(full)),
        Bridge("username", USER, virtual(username)),
        biography?.let { Bridge("biography", USER, virtual(it)) },
        Bridge("profilePictureUrl", PROFILE_PICTURE_INFO, kept(patch, PROFILE_PICTURE_INFO, "getUrl", "Ljava/lang/String;")),
        Bridge("profilePictureWidth", PROFILE_PICTURE_INFO, kept(patch, PROFILE_PICTURE_INFO, "getWidth", "I"), primitive = true),
        Bridge("profilePictureHeight", PROFILE_PICTURE_INFO, kept(patch, PROFILE_PICTURE_INFO, "getHeight", "I"), primitive = true),
        Bridge("candidateUrl", IMAGE_URL, kept(patch, IMAGE_URL, "getUrl", "Ljava/lang/String;")),
        Bridge("candidateWidth", IMAGE_URL, kept(patch, IMAGE_URL, "getWidth", "I"), primitive = true),
        Bridge("candidateHeight", IMAGE_URL, kept(patch, IMAGE_URL, "getHeight", "I"), primitive = true),
    ))
}

/**
 * The same for an account in a list: its username and the address of the picture its profile
 * shows, and nothing a profile's own patches read besides, so a build where the full size picture
 * or the bio moved doesn't stop a patch that only lists accounts.
 */
internal fun BytecodePatchContext.accountBridges(patch: String): () -> Unit {
    val shown = pandoGetter(patch, USER, "profile_pic_url", IMAGE_URL)
    val username = pandoGetter(patch, USER, "username", "Ljava/lang/String;")
    return bridgeWriter(patch, listOf(
        Bridge("profilePicture", USER, virtual(shown)),
        Bridge("username", USER, virtual(username)),
        Bridge("candidateUrl", IMAGE_URL, kept(patch, IMAGE_URL, "getUrl", "Ljava/lang/String;")),
    ))
}

/** A call through [type]'s getter [name], which keeps its name, after checking the interface has it. */
private fun BytecodePatchContext.kept(patch: String, type: String, name: String, returns: String): String {
    if (anInterface(patch, type).methods.none { it.name == name && it.parameterTypes.isEmpty() && it.returnType == returns }) {
        throw PatchException("$patch: $type has no $name()$returns")
    }
    return "invoke-interface {p0}, $type->$name()$returns"
}

/**
 * The same for the sizes Instagram's feed photo picker reads for a Media: a call to [helper], the
 * static method of Media's helpers the picker asks, which may answer a carousel page's sizes
 * rather than the post's own.
 */
internal fun BytecodePatchContext.pickerSizesBridge(patch: String, helper: MethodReference): () -> Unit =
    bridgeWriter(patch, listOf(Bridge("pickerImageVersions", MEDIA, "invoke-static {p0}, $helper")))

/**
 * The same for a post's caption, for Details' Copy caption: Media's `caption`, which is a comment,
 * and that comment's `text`, read through the comment's interface by the name its tree-backed class
 * gives the getter, as a comment's own text is. Media's getter is the one taking nothing that holds
 * the key's hash, since the comment's type has no kept name. Only Copy caption needs them, so a
 * build where either can't be told answers null, after the patch log says why, and Details goes in
 * without it.
 */
internal fun BytecodePatchContext.captionBridges(patch: String): (() -> Unit)? = try {
    val key = "caption".hashCode()
    val captions = classDefBy(MEDIA).methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType.startsWith("L") && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { it.loadsLiteral(key) } == true
    }
    val caption = captions.singleOrNull() ?: throw PatchException(
        "$patch: expected one getter on $MEDIA for caption, found " +
            if (captions.isEmpty()) "none" else captions.joinToString { it.name },
    )
    val comment = caption.returnType
    anInterface(patch, comment)
    val textKey = "text".hashCode()
    val trees = mutableListOf<ClassDef>()
    classDefForEach { type ->
        if (comment in type.interfaces && type.methods.any { method ->
                method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/String;" &&
                    method.implementation?.instructions?.any { it.loadsLiteral(textKey) } == true
            }) trees += type
    }
    val tree = trees.singleOrNull() ?: throw PatchException(
        "$patch: expected one tree-backed $comment reading its text, found " +
            if (trees.isEmpty()) "none" else trees.joinToString { it.type },
    )
    bridgeWriter(patch, listOf(
        Bridge("caption", MEDIA, virtual(caption)),
        Bridge("captionText", comment, throughInterface(patch, comment, tree.type, "text", "Ljava/lang/String;")),
    ))
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Details goes in without Copy caption.")
    null
}

/** The same for an account's username alone, for a save named after an account found elsewhere. */
internal fun BytecodePatchContext.usernameBridge(patch: String): () -> Unit {
    val username = pandoGetter(patch, USER, "username", "Ljava/lang/String;")
    return bridgeWriter(patch, listOf(Bridge("username", USER, virtual(username))))
}

/**
 * The same for a carousel's pages, `carousel_media`, which Save all reads in the feed and the Reels
 * viewer's Download saves, since a carousel's own picture there is its first page's (#78).
 */
internal fun BytecodePatchContext.carouselBridge(patch: String): () -> Unit {
    val pages = pandoGetter(patch, MEDIA, "carousel_media", "Ljava/util/List;")
    return bridgeWriter(patch, listOf(Bridge("carouselMedia", MEDIA, virtual(pages))))
}

/**
 * The same for whether a story is a photo with music, which Instagram serves as a video: the flag
 * only some uploads set, and `original_media_type`, the type the story was posted as, which
 * Instagram's own story viewer reads to tell such a video from a filmed one (#98).
 */
internal fun BytecodePatchContext.storyMusicBridges(patch: String): () -> Unit {
    val photoWithMusic = pandoGetter(patch, MEDIA, "is_story_image_with_music", "Ljava/lang/Boolean;")
    val postedAs = pandoGetter(patch, MEDIA, "original_media_type", "Ljava/lang/Integer;")
    return bridgeWriter(patch, listOf(
        Bridge("storyImageWithMusic", MEDIA, virtual(photoWithMusic)),
        Bridge("originalMediaType", MEDIA, virtual(postedAs)),
    ))
}

/** A post's music, the track it comes from, and the part of the track the post plays. All keep their names. */
internal const val MUSIC_INFO = "Lcom/instagram/api/schemas/MusicInfo;"
internal const val PANDO_MUSIC_INFO = "Lcom/instagram/api/schemas/ImmutablePandoMusicInfo;"
internal const val TRACK_DATA = "Lcom/instagram/api/schemas/TrackData;"
internal const val PANDO_TRACK_DATA = "Lcom/instagram/api/schemas/ImmutablePandoTrackData;"
internal const val MUSIC_CONSUMPTION = "Lcom/instagram/music/common/model/MusicConsumptionModel;"
internal const val PANDO_MUSIC_CONSUMPTION = "Lcom/instagram/music/common/model/ImmutablePandoMusicConsumptionModel;"

/**
 * The same for a post's music (#71): where a photo post keeps it (`music_metadata`) or a reel does
 * (`clips_metadata`), the track's addresses, and the part of the track the post plays. Each of
 * the two metadata types is an interface of Instagram's whose one getter answering the music
 * reads it, and the bridge calls that getter.
 */
internal fun BytecodePatchContext.musicBridges(patch: String): () -> Unit {
    fun holder(field: String): Pair<Method, String> {
        val key = field.hashCode()
        val getters = classDefBy(MEDIA).methods.filter { method ->
            method.parameterTypes.isEmpty() && method.returnType.startsWith("L") && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.implementation?.instructions?.any { it.loadsLiteral(key) } == true
        }
        val getter = getters.singleOrNull() ?: throw PatchException(
            "$patch: expected one getter on $MEDIA answering an object for $field, found " +
                if (getters.isEmpty()) "none" else getters.joinToString { it.name },
        )
        val music = anInterface(patch, getter.returnType).methods.filter {
            it.parameterTypes.isEmpty() && it.returnType == MUSIC_INFO && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val read = music.singleOrNull() ?: throw PatchException(
            "$patch: expected one getter of the music on ${getter.returnType}, the type of $field, found ${music.size}",
        )
        return getter to "invoke-interface {p0}, ${getter.returnType}->${read.name}()$MUSIC_INFO"
    }
    val (metadata, metadataMusic) = holder("music_metadata")
    val (clips, clipsMusic) = holder("clips_metadata")
    fun music(field: String, returns: String) = throughInterface(patch, MUSIC_INFO, PANDO_MUSIC_INFO, field, returns)
    fun track(field: String) = throughInterface(patch, TRACK_DATA, PANDO_TRACK_DATA, field, "Ljava/lang/String;")
    fun part(field: String) = throughInterface(patch, MUSIC_CONSUMPTION, PANDO_MUSIC_CONSUMPTION, field, "Ljava/lang/Integer;")

    return bridgeWriter(patch, listOf(
        Bridge("musicMetadata", MEDIA, virtual(metadata)),
        Bridge("metadataMusic", metadata.returnType, metadataMusic),
        Bridge("clipsMetadata", MEDIA, virtual(clips)),
        Bridge("clipsMusic", clips.returnType, clipsMusic),
        Bridge("musicTrack", MUSIC_INFO, music("music_asset_info", TRACK_DATA)),
        Bridge("musicConsumption", MUSIC_INFO, music("music_consumption_info", MUSIC_CONSUMPTION)),
        Bridge("trackUrl", TRACK_DATA, track("progressive_download_url")),
        Bridge("trackFastStartUrl", TRACK_DATA, track("fast_start_progressive_download_url")),
        Bridge("musicStartMs", MUSIC_CONSUMPTION, part("audio_asset_start_time_in_ms")),
        Bridge("musicLengthMs", MUSIC_CONSUMPTION, part("overlap_duration_in_ms")),
    ))
}

private fun virtual(getter: Method) = "invoke-virtual {p0}, ${getter.definingClass}->${getter.name}()${getter.returnType}"

/**
 * The call to the getter for [field] through [type], an interface, by the name [pando], the
 * tree-backed class implementing it, gives the getter. The interface must declare it.
 */
private fun BytecodePatchContext.throughInterface(patch: String, type: String, pando: String, field: String, returns: String): String {
    val getter = pandoGetter(patch, pando, field, returns)
    if (anInterface(patch, type).methods.none { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == returns }) {
        throw PatchException("$patch: $type doesn't declare ${getter.name}, the getter for $field")
    }
    return "invoke-interface {p0}, $type->${getter.name}()$returns"
}

/** The class [type], which the bridges call through invoke-interface, so it has to be an interface. */
private fun BytecodePatchContext.anInterface(patch: String, type: String) = classDefBy(type).also {
    if (!AccessFlags.INTERFACE.isSet(it.accessFlags)) throw PatchException("$patch: $type is no longer an interface")
}

/**
 * Finds each bridge's stub, and answers the step that writes its body. A stub another patch has
 * written already, since two patches share the video's bridges, is left as it is.
 */
private fun BytecodePatchContext.bridgeWriter(patch: String, bridges: List<Bridge>): () -> Unit {
    val stubs = mutableClassDefBy(INSTAGRAM_MEDIA).methods
    val found = bridges.associateWith { bridge ->
        stubs.singleOrNull {
            it.name == bridge.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
        } ?: throw PatchException("$patch: $INSTAGRAM_MEDIA has no static ${bridge.name}(Object)")
    }
    return {
        found.forEach { (bridge, stub) ->
            if (stub.implementation!!.instructions.first().opcode == Opcode.CHECK_CAST) return@forEach
            stub.addInstructions(
                0,
                if (bridge.primitive) {
                    """
                        check-cast p0, ${bridge.receiver}
                        ${bridge.call}
                        move-result p0
                        return p0
                    """
                } else {
                    """
                        check-cast p0, ${bridge.receiver}
                        ${bridge.call}
                        move-result-object p0
                        return-object p0
                    """
                },
            )
        }
    }
}
