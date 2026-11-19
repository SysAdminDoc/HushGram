/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savemenu

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Liked by (#762): the likes row under a feed post is found by the state a post's media state
 * prints after "likesRowUiState=", and every row that draws itself from that state hands its
 * render's answer through FeedSaveRow, which answers null while the switch is on.
 */
class LikesRowHookTest {
    @Test
    fun theRenderOfEachRowDrawnFromTheStateIsFound() {
        val context = PatchContexts.of(fixture())

        assertEquals(LIKES_STATE, context.likesRowState())
        val found = context.findLikesRows()
        assertEquals(listOf("$ROW->render"), found.methods.map { "${it.definingClass}->${it.name}" })
        assertTrue("before the write, nothing is changed", found.methods.single().code().none { it.calls(LIKES_ROW) })

        found.write()

        val code = found.methods.single().code()
        assertEquals("one call per return", 2, code.count { it.calls(LIKES_ROW) })
        assertEveryReturnFiltered("fixture", code, COMPONENT)
        for (decoy in listOf(READER, OUTSIDE)) {
            for (method in context.mutableClassDefBy(decoy).methods) {
                assertTrue("$decoy->${method.name} changed", method.code().none { it.calls(LIKES_ROW) })
            }
        }
    }

    @Test
    fun withoutOneMediaStateNothingIsFound() {
        for ((case, classes) in listOf(
            "no media state" to fixture().filter { it.type != MEDIA },
            "two media states" to fixture() + media(type = "Lfixture/OtherMedia;"),
            "the label printing another field" to fixture().filter { it.type != MEDIA } + media(printed = "other"),
            "no row drawn from the state" to fixture().filter { it.type != ROW },
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(case, PatchException::class.java) { context.findLikesRows() }
            assertNull(case, context.findLikesRowsOrWarn())
        }
    }

    /**
     * On each build the media state's likes row state is found, and the rows drawn from it include
     * the one with Instagram's likes test tag. Every return of each one's render goes through the hook.
     */
    @Test
    fun eachBuildFindsTheLikesRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            builds(bundle, "${bundle.parentFile.name}/${bundle.name}")
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, LIKES_ROW_LABEL)
        val state = PatchContexts.of(holders).likesRowState()
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it == state }) return@forEach
            for (classDef in dex.classes) {
                val reads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == state
                    } == true
                }
                if (reads) readers += ImmutableClassDef.of(classDef)
            }
        }
        val have = (holders + readers).mapTo(HashSet()) { it.type }
        val supers = mutableListOf<ClassDef>()
        var wanted = readers.mapNotNull { it.superclass }.filterTo(HashSet()) { it !in have }
        repeat(4) {
            if (wanted.isEmpty()) return@repeat
            val found = FixtureDex.classes(bundle, wanted).values
            supers += found
            have += wanted
            wanted = found.mapNotNull { it.superclass }.filterTo(HashSet()) { it !in have }
        }
        val holderTypes = holders.mapTo(HashSet()) { it.type }
        val context = PatchContexts.of(holders + readers.filter { it.type !in holderTypes } + supers)

        val found = context.findLikesRows()
        assertTrue("$label: a likes row is found", found.methods.isNotEmpty())
        assertTrue("$label: the likes row with Instagram's test tag is among ${found.methods.map { it.definingClass }}",
            found.methods.any { method -> method.code().any { it.loadedString() == LIKES_TEST_TAG } })
        val returns = found.methods.associateWith { method -> method.code().count { it.opcode == Opcode.RETURN_OBJECT } }

        found.write()

        for (method in found.methods) {
            val code = method.code()
            assertEquals("$label: ${method.definingClass} one call per return", returns.getValue(method), code.count { it.calls(LIKES_ROW) })
            assertEveryReturnFiltered("$label: ${method.definingClass}", code, method.returnType)
        }
    }

    /** Each return-object right after the hook's call, its answer and the cast back to [type], on the same register. */
    private fun assertEveryReturnFiltered(what: String, code: List<Instruction>, type: String) {
        for (at in code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }) {
            val register = (code[at] as OneRegisterInstruction).registerA
            assertEquals("$what: the cast before return $at", Opcode.CHECK_CAST, code[at - 1].opcode)
            assertEquals("$what: the cast's type", type, ((code[at - 1] as ReferenceInstruction).reference as TypeReference).type)
            assertEquals("$what: the cast's register", register, (code[at - 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[at - 2].opcode)
            assertEquals("$what: the answer's register", register, (code[at - 2] as OneRegisterInstruction).registerA)
            assertTrue("$what: the call", code[at - 3].calls(LIKES_ROW))
            assertEquals("$what: the call's register", register, (code[at - 3] as RegisterRangeInstruction).startRegister)
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun Instruction.loadedString() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
        ?.takeIf { opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO }

    private companion object {
        const val LIKES_TEST_TAG = "row_feed_textview_likes"
        const val MEDIA = "Lfixture/MediaState;"
        const val LIKES_STATE = "Lfixture/LikesState;"
        const val COMPONENT = "Lfixture/Component;"
        const val BASE = "Lfixture/Base;"
        const val ROW = "Lfixture/LikesRow;"
        const val READER = "Lfixture/LikesReader;"
        const val OUTSIDE = "Lfixture/Outside;"
        const val CONTEXT = "Lfixture/Context;"
        const val APPEND_STRING = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        const val APPEND_OBJECT = "Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        val FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun fixture(): List<ClassDef> = listOf(media(), base(), row(), reader(), outside())

        /**
         * A post's media state. As on 456, its toString reads every field at the top, keeps each in a
         * register of its own and copies it back right before appending it.
         */
        fun media(type: String = MEDIA, printed: String = "likes"): ClassDef {
            val printer = """
                iget-object v1, p0, $type->likes:$LIKES_STATE
                move-object v4, v1
                iget-object v1, p0, $type->other:Ljava/lang/String;
                move-object v5, v1
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct { v0 }, Ljava/lang/StringBuilder;-><init>()V
                const-string v2, ", other="
                invoke-virtual { v0, v2 }, $APPEND_STRING
                invoke-virtual { v0, v5 }, $APPEND_STRING
                const-string v2, "$LIKES_ROW_LABEL"
                invoke-virtual { v0, v2 }, $APPEND_STRING
                move-object v2, ${if (printed == "likes") "v4" else "v5"}
                invoke-virtual { v0, v2 }, $APPEND_OBJECT
                invoke-virtual { v0 }, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
            val fields = listOf(
                ImmutableField(type, "likes", LIKES_STATE, FINAL, null, null, null),
                ImmutableField(type, "other", "Ljava/lang/String;", FINAL, null, null, null),
            )
            return classDef(type, "Ljava/lang/Object;", fields, listOf(method(type, "toString", emptyList(), "Ljava/lang/String;", 7, printer)))
        }

        /** The row's superclass, itself a component, so the render answers its grandparent's type as on 456. */
        fun base() = classDef(BASE, COMPONENT, emptyList(), emptyList())

        /** The likes row: its state in a field, and a render that draws nothing for a post with no state. */
        fun row(): ClassDef {
            val render = """
                iget-object v0, p0, $ROW->state:$LIKES_STATE
                if-nez v0, :drawn
                const/4 v1, 0x0
                return-object v1
                :drawn
                new-instance v1, $BASE
                return-object v1
            """
            val fields = listOf(ImmutableField(ROW, "state", LIKES_STATE, FINAL, null, null, null))
            return classDef(ROW, BASE, fields, listOf(method(ROW, "render", listOf(CONTEXT), COMPONENT, 3, render)))
        }

        /** A class with the state in a field whose one-argument method answers something no component is. */
        fun reader(): ClassDef {
            val describe = """
                iget-object v0, p0, $READER->state:$LIKES_STATE
                const-string v0, ""
                return-object v0
            """
            val fields = listOf(ImmutableField(READER, "state", LIKES_STATE, FINAL, null, null, null))
            return classDef(READER, BASE, fields, listOf(method(READER, "describe", listOf(CONTEXT), "Ljava/lang/String;", 3, describe)))
        }

        /** A component reading the state off the media state rather than a field of its own. */
        fun outside(): ClassDef {
            val render = """
                iget-object v0, p0, $OUTSIDE->media:$MEDIA
                iget-object v0, v0, $MEDIA->likes:$LIKES_STATE
                const/4 v0, 0x0
                return-object v0
            """
            val fields = listOf(ImmutableField(OUTSIDE, "media", MEDIA, FINAL, null, null, null))
            return classDef(OUTSIDE, BASE, fields, listOf(method(OUTSIDE, "render", listOf(CONTEXT), COMPONENT, 3, render)))
        }

        fun classDef(type: String, superclass: String, fields: List<ImmutableField>, methods: List<Method>): ClassDef =
            ImmutableClassDef(type, FINAL, superclass, null, null, null, fields, methods)

        fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, body: String): Method =
            MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result,
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent())
            }.let(ImmutableMethod::of)
    }
}
