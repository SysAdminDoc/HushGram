/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.Fixtures
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a carousel's pages are, on every build of the declared version (#78). Download as video on
 * a music carousel reads each page's own video, picture and music through the same Media bridges
 * the post is read with, which only holds when a page is a Media. The pages come from the getter of
 * `carousel_media`, which builds each one with a static call answering Media and adds it to the list.
 * A page's music, video and picture are read from that same Media class, one getter each.
 */
class CarouselPagesFixtureTest {
    private fun holding(media: List<Method>, field: String, returns: (String) -> Boolean): List<Method> {
        val key = field.hashCode()
        return media.filter { method ->
            method.parameterTypes.isEmpty() && returns(method.returnType) &&
                method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } == true
        }
    }

    @Test
    fun eachBuildsCarouselPagesAreMedia() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertTrue("seven builds of the declared version", bundles.size >= 7)
        for (bundle in bundles) {
            val name = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val media = FixtureDex.classes(bundle, setOf(MEDIA))[MEDIA] ?: error("$name: no Media class")
            val methods = media.methods.toList()
            val pages = holding(methods, "carousel_media") { it == "Ljava/util/List;" }
            assertEquals("$name: one carousel_media getter", 1, pages.size)
            val code = pages.single().implementation!!.instructions.toList()
            val adds = code.indices.filter { at ->
                val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
                code[at].opcode == Opcode.INVOKE_VIRTUAL && reference?.name == "add" && reference.definingClass == "Ljava/util/AbstractCollection;"
            }
            assertTrue("$name: the getter adds no page", adds.isNotEmpty())
            adds.forEach { at ->
                val made = code[at - 2]
                val reference = (made as? ReferenceInstruction)?.reference as? MethodReference
                assertEquals("$name: a page is built by a static call", Opcode.INVOKE_STATIC, made.opcode)
                assertEquals("$name: a page is a Media", MEDIA, reference?.returnType)
                assertEquals("$name: its result is what's added", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
            }
            // The page's own video, picture and music: each one getter on the Media class.
            assertEquals("$name: video_versions", 1, holding(methods, "video_versions") { it == "Ljava/util/List;" }.size)
            assertEquals("$name: image_versions2", 1, holding(methods, "image_versions2") { it.startsWith("L") }.size)
            assertEquals("$name: music_metadata", 1, holding(methods, "music_metadata") { it.startsWith("L") && it != "Ljava/lang/String;" }.size)
        }
    }
}
