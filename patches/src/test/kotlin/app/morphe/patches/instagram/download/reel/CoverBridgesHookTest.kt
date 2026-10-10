/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.ADDITIONAL_CANDIDATES
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.PANDO_ADDITIONAL_CANDIDATES
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import app.morphe.patches.instagram.download.coverBridges
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stills in a picture's `additional_candidates` that Download cover reads besides the
 * candidates. They are proven in the declared build of Instagram 450.0.0.50.77 and in the six
 * other builds of it: each bridge casts to the type that holds the getter, calls the getter whose
 * code loads the field's key, and an entry is a size Instagram reads as a candidate.
 */
class CoverBridgesHookTest {
    private val extended = "Lcom/instagram/model/mediasize/ExtendedImageUrl;"
    private val chain = setOf(
        IMAGE_INFO, PANDO_IMAGE_INFO, ADDITIONAL_CANDIDATES, PANDO_ADDITIONAL_CANDIDATES, extended, IMAGE_URL,
        "Lcom/instagram/common/typedurl/ExpirableImageUrl;", "Lcom/instagram/common/typedurl/ImageUrlBase;",
        "Lcom/instagram/common/typedurl/CacheKeyGenerationMetadataProvider;",
    )

    @Test
    fun eachBuildOf450ReadsTheStillsBesideTheCandidates() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val bundles = declared + Fixtures.otherBuilds()
        assertTrue("every build of 450 is read: ${bundles.map { it.parent }}", bundles.size >= 7)
        for (bundle in bundles) {
            val what = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val pool = FixtureDex.classes(bundle, chain)
            assertEquals("$what: classes found", chain, pool.keys)
            val context = PatchContexts.of(pool.values + ExtensionDex.classDef(INSTAGRAM_MEDIA))

            val write = context.coverBridges("test")
            assertNotNull("$what: the stills can be read", write)
            write!!()

            val bridges = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
            fun bridge(name: String) = bridges.single { it.name == name }.code()
            val expected = listOf(
                Triple("additionalCandidates", IMAGE_INFO to PANDO_IMAGE_INFO, "additional_candidates"),
                Triple("firstFrame", ADDITIONAL_CANDIDATES to PANDO_ADDITIONAL_CANDIDATES, "first_frame"),
                Triple("igtvFirstFrame", ADDITIONAL_CANDIDATES to PANDO_ADDITIONAL_CANDIDATES, "igtv_first_frame"),
                Triple("smartFrame", ADDITIONAL_CANDIDATES to PANDO_ADDITIONAL_CANDIDATES, "smart_frame"),
            )
            for ((name, types, field) in expected) {
                val code = bridge(name)
                assertEquals("$what: $name casts to", types.first, (code[0].reference() as TypeReference).type)
                val call = code[1].reference() as MethodReference
                assertEquals("$what: $name calls through", types.first, call.definingClass)
                assertEquals("$what: $name's opcode", Opcode.INVOKE_INTERFACE, code[1].opcode)
                val getter = pool.getValue(types.second).methods.single { it.name == call.name && it.parameterTypes.isEmpty() }
                assertTrue(
                    "$what: ${call.name} on ${types.second} doesn't load $field",
                    getter.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == field.hashCode() },
                )
                assertEquals("$what: $name answers", getter.returnType, call.returnType)
            }
            assertTrue("$what: a still isn't an ImageUrl", IMAGE_URL in supertypes(extended, pool))
        }
    }

    /** A build whose model has no such stills gets none of the bridges, and the patch goes in. */
    @Test
    fun aBuildWithoutTheStillsGoesInWithoutThem() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(INSTAGRAM_MEDIA)))

        assertNull(context.coverBridges("test"))

        val first = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods.single { it.name == "firstFrame" }
        assertEquals("the stub is untouched", Opcode.CONST_4, first.code().first().opcode)
    }

    private fun supertypes(type: String, pool: Map<String, ClassDef>): Set<String> {
        val found = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(type))
        while (pending.isNotEmpty()) {
            val classDef = pool[pending.removeFirst()] ?: continue
            for (parent in listOfNotNull(classDef.superclass) + classDef.interfaces) if (found.add(parent)) pending.addLast(parent)
        }
        return found
    }

    private fun Method.code() = implementation?.instructions?.toList().orEmpty()

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.reference() = (this as? ReferenceInstruction)?.reference

}
