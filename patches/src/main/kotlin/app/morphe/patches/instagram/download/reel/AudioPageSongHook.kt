/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.theme.hookReturns
import app.morphe.patches.instagram.misc.theme.isViewCreated
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Download any reel"

/** Instagram's song, which keeps its name: what a song's page shows and plays. */
internal const val MUSIC_ASSET_MODEL = "Lcom/instagram/music/common/model/MusicAssetModel;"

/** The module name a song's page logs under, which its fragment loads. */
internal const val AUDIO_PAGE_MODULE = "audio_page"

internal const val AUDIO_PAGE_SONG = "$EXTENSION_PACKAGE/download/AudioPageSong;"

/** Handed a song's page and its view as the page finishes making the view. */
internal const val AUDIO_PAGE_SHOWN = "$AUDIO_PAGE_SONG->shown(Ljava/lang/Object;Ljava/lang/Object;)V"

private const val SONG_STRING = "Ljava/lang/String;"

/**
 * What the Download button on a song's page needs (#741): the page's fragment [page], its field
 * [song] holding the song once it loads, and the song's fields for its whole file ([url]), its
 * [title] and its [artist].
 */
internal class AudioPageSite(
    val page: String,
    val song: FieldReference,
    val url: FieldReference,
    val title: FieldReference,
    val artist: FieldReference,
)

private fun refuseSongPage(detail: String): Nothing =
    throw PatchException("$PATCH, the Download button on a song's page: $detail")

private val SONG_KEY_LOADS = setOf(Opcode.CONST, Opcode.CONST_16, Opcode.CONST_HIGH16, Opcode.CONST_4)

private fun Instruction.loadsSongKey(key: Int) = opcode in SONG_KEY_LOADS && (this as? NarrowLiteralInstruction)?.narrowLiteral == key

/**
 * Adds the Download button to a song's page, the page a reel's song opens, when the page is as
 * this patch knows it. A build whose page differs gets a warning and keeps the rest of the patch.
 */
internal fun BytecodePatchContext.addSongPageDownloadWhereItFits() {
    val site = try {
        findAudioPageSite()
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $PATCH goes in without the Download button on a song's page.")
        return
    }
    addSongPageDownload(site)
}

/**
 * Finds the song's page: the one class loading [AUDIO_PAGE_MODULE] that has an onViewCreated of
 * its own and one field holding a [MUSIC_ASSET_MODEL], and the song's fields by the keys its parse
 * reads them with. Fails naming what it didn't find exactly once, before anything changes.
 */
internal fun BytecodePatchContext.findAudioPageSite(): AudioPageSite {
    fun songFields(fields: Iterable<Field>) = fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == MUSIC_ASSET_MODEL
    }
    val pages = classesLoadingString(AUDIO_PAGE_MODULE).filter { classDef ->
        songFields(classDef.fields).isNotEmpty() && classDef.methods.any { it.isViewCreated() && it.implementation != null }
    }
    val page = pages.singleOrNull()
        ?: refuseSongPage("expected one page loading $AUDIO_PAGE_MODULE with a song, found ${pages.joinToString { it.type }.ifEmpty { "none" }}")
    if (!AccessFlags.PUBLIC.isSet(page.accessFlags)) refuseSongPage("the page ${page.type} isn't public")
    val songs = songFields(page.fields)
    val song = songs.singleOrNull() ?: refuseSongPage("${page.type} holds ${songs.size} songs, not one")
    if (!AccessFlags.PUBLIC.isSet(song.accessFlags)) refuseSongPage("${page.type}->${song.name} isn't public")
    return AudioPageSite(page.type, song, parsedSongField("progressive_download_url"), parsedSongField("title"), parsedSongField("display_artist"))
}

/**
 * The song's String field its parse fills from [name]: in the song's own methods, the field an
 * iput-object writes within a few instructions of loading [name]'s key, the String.hashCode() of
 * it, which the parse reads the value by. Exactly one, and public.
 */
private fun BytecodePatchContext.parsedSongField(name: String): FieldReference {
    val key = name.hashCode()
    val model = classDefByOrNull(MUSIC_ASSET_MODEL) ?: refuseSongPage("there's no $MUSIC_ASSET_MODEL")
    val written = model.methods.flatMap { method ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        instructions.indices.filter { instructions[it].loadsSongKey(key) }.mapNotNull { at ->
            instructions.subList(at + 1, minOf(instructions.size, at + 5))
                .firstOrNull { it.opcode == Opcode.IPUT_OBJECT }
                ?.let { (it as ReferenceInstruction).reference as FieldReference }
                ?.takeIf { it.definingClass == MUSIC_ASSET_MODEL && it.type == SONG_STRING }
        }
    }.distinctBy { it.name }
    val field = written.singleOrNull()
        ?: refuseSongPage("expected the song's parse to fill one field from $name, found ${written.joinToString { it.name }.ifEmpty { "none" }}")
    val declared = model.fields.singleOrNull { it.name == field.name && it.type == SONG_STRING }
    if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuseSongPage("$MUSIC_ASSET_MODEL->${field.name} isn't public")
    return field
}

/**
 * Before each return-void of the page's onViewCreated, hands the page and its view to
 * [AUDIO_PAGE_SHOWN], and writes the extension's readers of the page's song and of the song's
 * file, title and artist. The page and the view are read from `this` and the first parameter, so
 * a page whose code writes over either before a return is refused.
 */
internal fun BytecodePatchContext.addSongPageDownload(site: AudioPageSite) {
    val stubs = mutableClassDefBy(AUDIO_PAGE_SONG).methods
    fun stub(name: String) = stubs.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: refuseSongPage("$AUDIO_PAGE_SONG has no static $name(Object)")
    val reads = listOf(
        stub("song") to (site.page to site.song),
        stub("songUrl") to (MUSIC_ASSET_MODEL to site.url),
        stub("songTitle") to (MUSIC_ASSET_MODEL to site.title),
        stub("songArtist") to (MUSIC_ASSET_MODEL to site.artist),
    )
    val shown = mutableClassDefBy(site.page).methods.single { it.isViewCreated() }
    val returns = shown.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    shown.requireThisIntact(PATCH, returns)
    shown.requireParameterIntact(PATCH, 0, returns)

    // The range form: the page keeps p0 above v15, out of a plain invoke's reach.
    shown.hookReturns("invoke-static/range { p0 .. p1 }, $AUDIO_PAGE_SHOWN", PATCH)
    for ((method, read) in reads) {
        val (owner, field) = read
        method.addInstructions(
            0,
            """
                check-cast p0, $owner
                iget-object p0, p0, $owner->${field.name}:${field.type}
                return-object p0
            """,
        )
    }
}
