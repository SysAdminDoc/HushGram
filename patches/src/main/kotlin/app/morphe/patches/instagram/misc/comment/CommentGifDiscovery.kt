/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** A comment GIF's set of images: Giphy's own (images), or Instagram's copy (first_party_cdn_proxied_images). */
internal const val GIF_IMAGES = "Lcom/instagram/api/schemas/CommentGiphyMediaImagesIntf;"
/** The one rendition a set of a comment GIF's images lists, Giphy's fixed_height, with its GIF, WebP and MP4. */
internal const val GIF_RENDITION = "Lcom/instagram/api/schemas/CommentGiphyMediaFixedHeightImages;"
private const val BOOLEAN = "Ljava/lang/Boolean;"

/**
 * The getters the GIF bridges call, each declared on one of the comment GIF model's three
 * interfaces. Every one is proved on the interface's tree-backed class by the key its body reads,
 * and on its parsed class as a plain read of a field no other proved getter reads.
 */
internal class CommentGifPlan(
    val sticker: MethodReference, val proxied: MethodReference, val images: MethodReference,
    val rendition: MethodReference, val url: MethodReference, val webp: MethodReference, val mp4: MethodReference,
    val width: MethodReference, val height: MethodReference,
) {
    /** Each GIF bridge on [PHOTO_NATIVE], by name, with the getter it calls. */
    val reads: List<Pair<String, MethodReference>> get() = GIF_READS.zip(
        listOf(sticker, proxied, images, rendition, url, webp, mp4, width, height))
}

/** The GIF bridges on [PHOTO_NATIVE], in the order [CommentGifPlan.reads] pairs them. */
internal val GIF_READS = listOf("gifSticker", "gifProxied", "gifImages", "gifRendition", "gifUrl", "gifWebp", "gifMp4",
    "gifWidth", "gifHeight")

/**
 * The reads of a comment's GIF: whether it's a sticker, its two sets of images, each set's
 * fixed_height rendition, and that rendition's GIF, WebP and MP4 addresses and size. Refuses,
 * naming the patch discovering, when any of them can't be told.
 */
internal fun commentGif(classes: Map<String, ClassDef>): CommentGifPlan {
    val (sticker, proxied, images) = gifGetters(classes, GIPHY, "is_sticker" to BOOLEAN,
        "first_party_cdn_proxied_images" to GIF_IMAGES, "images" to GIF_IMAGES)
    val (rendition) = gifGetters(classes, GIF_IMAGES, "fixed_height" to GIF_RENDITION)
    val (url, webp, mp4, width, height) = gifGetters(classes, GIF_RENDITION, "url" to STRING, "webp" to STRING,
        "mp4" to STRING, "width" to INTEGER, "height" to INTEGER)
    return CommentGifPlan(sticker, proxied, images, rendition, url, webp, mp4, width, height)
}

/**
 * The GIF reads, or null after the patch log says why. Save comment photo still goes in for
 * photos then, and the GIF bridges keep answering null, so a GIF comment gets no row.
 */
internal fun commentGifOrNull(classes: Map<String, ClassDef>): CommentGifPlan? = try {
    commentGif(classes)
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Save comment photo goes in for photos, and a comment's GIF gets no Save.")
    null
}

/**
 * The getters on the public interface [model] for each of [keys], a field name and the type it
 * answers. The interface has one tree-backed class and one parsed class. The tree's getter holds
 * the hash of the key, and the parsed class's getter of the same name returns a field of its own
 * and nothing else, a different field for each key.
 */
private fun gifGetters(classes: Map<String, ClassDef>, model: String, vararg keys: Pair<String, String>): List<MethodReference> {
    val type = classes[model] ?: refuse("missing native class $model")
    if (!AccessFlags.INTERFACE.isSet(type.accessFlags)) refuse("$model is no longer an interface")
    requirePublic(type)
    val models = classes.values.filter { model in it.interfaces }
    val tree = models.filter { treeBacked(it, classes) }.one("tree-backed $model")
    val parsed = models.filter { !treeBacked(it, classes) }.one("parsed $model")
    val fields = mutableSetOf<String>()
    return keys.map { (key, returns) ->
        val read = hashGetter(tree, key, returns)
        val getter = type.methods.filter { it.matches(read) && AccessFlags.ABSTRACT.isSet(it.accessFlags) }
            .one("$model $key getter")
        if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$model $key getter isn't public")
        val field = parsedTextField(parsed.methods.filter { it.matches(getter) }.one("parsed $model $key getter"),
            parsed.type, returns)
        if (!fields.add(field.toString())) refuse("parsed $model reads $key from another key's field")
        getter
    }
}
