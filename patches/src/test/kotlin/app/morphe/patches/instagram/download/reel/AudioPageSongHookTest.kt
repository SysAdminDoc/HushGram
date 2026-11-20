/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.theme.isViewCreated
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download on a song's page (#741), on every declared build of Instagram 456: the page is found
 * by what it loads, the extension hears of it before each return of its onViewCreated with the
 * page and its view, and the readers take the page's song and the song's file, title and artist
 * from the fields the song's parse fills by those keys.
 */
class AudioPageSongHookTest {
    @Test
    fun eachBuildOf456GetsTheSongPageDownload() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val bundles = declared + Fixtures.otherBuilds()
        assertEquals("every build is read: ${bundles.map { it.parent }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val what = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val sliced = (FixtureDex.classesLoading(bundle, AUDIO_PAGE_MODULE) + FixtureDex.classes(bundle, setOf(MUSIC_ASSET_MODEL)).values)
                .distinctBy { it.type }
            val classes = FixtureDex.withStringPools(bundle, sliced)
            val model = classes.single { it.type == MUSIC_ASSET_MODEL }
            val context = PatchContexts.of(classes + ExtensionDex.classDef(AUDIO_PAGE_SONG))

            val site = context.findAudioPageSite()
            assertEquals("$what: the page's song", MUSIC_ASSET_MODEL, site.song.type)
            for ((field, key) in listOf(site.url to "progressive_download_url", site.title to "title", site.artist to "display_artist")) {
                assertTrue("$what: ${field.name} isn't read by $key", model.readsInto(key, field))
            }
            assertEquals("$what: three different fields", 3, setOf(site.url.name, site.title.name, site.artist.name).size)

            val stock = classes.single { it.type == site.page }.methods.single { it.isViewCreated() }.code()
            val returns = stock.count { it.opcode == Opcode.RETURN_VOID }
            context.addSongPageDownload(site)

            val patched = context.mutableClassDefBy(site.page).methods.single { it.isViewCreated() }
            val code = patched.code()
            val self = patched.implementation!!.registerCount - 3
            val hooks = code.withIndex().filter { (it.value.reference() as? MethodReference)?.let(::isShown) == true }
            assertEquals("$what: one call before each of the $returns returns", returns, hooks.size)
            for ((at, hook) in hooks) {
                assertEquals("$what: the call is a range", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                val range = hook as RegisterRangeInstruction
                assertEquals("$what: it hands this and the view", self to 2, range.startRegister to range.registerCount)
                assertEquals("$what: a return follows the call at $at", Opcode.RETURN_VOID, code[at + 1].opcode)
            }

            val readers = context.mutableClassDefBy(AUDIO_PAGE_SONG).methods
            fun reader(name: String) = readers.single { it.name == name }.code()
            for ((name, owner, field) in listOf(
                Triple("song", site.page, site.song),
                Triple("songUrl", MUSIC_ASSET_MODEL, site.url),
                Triple("songTitle", MUSIC_ASSET_MODEL, site.title),
                Triple("songArtist", MUSIC_ASSET_MODEL, site.artist),
            )) {
                val body = reader(name)
                assertEquals("$what: $name casts to", owner, (body[0].reference() as TypeReference).type)
                assertEquals("$what: $name reads", Opcode.IGET_OBJECT, body[1].opcode)
                val read = body[1].reference() as FieldReference
                assertEquals("$what: $name's field", "$owner->${field.name}", "${read.definingClass}->${read.name}")
                assertEquals("$what: $name answers it", Opcode.RETURN_OBJECT, body[2].opcode)
            }
        }
    }

    private fun isShown(call: MethodReference) =
        "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" == AUDIO_PAGE_SHOWN

    /**
     * Whether the song's code reads a String by [key]'s hash and writes it into [field]: the key,
     * the call taking it and answering a String, its result and the write, one after another.
     */
    private fun ClassDef.readsInto(key: String, field: FieldReference) = methods.any { method ->
        val code = method.code()
        code.indices.any { at ->
            at + 3 < code.size && (code[at] as? NarrowLiteralInstruction)?.narrowLiteral == key.hashCode() &&
                (code[at + 1].reference() as? MethodReference)?.let { call ->
                    call.returnType == "Ljava/lang/String;" && call.parameterTypes.map(Any::toString) == listOf("I")
                } == true &&
                code[at + 2].opcode == Opcode.MOVE_RESULT_OBJECT && code[at + 3].opcode == Opcode.IPUT_OBJECT &&
                (code[at + 3].reference() as FieldReference).name == field.name
        }
    }

    private fun Method.code() = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}
