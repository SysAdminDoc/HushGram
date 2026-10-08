/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.sharelinks

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A story's share link goes through LinkCleaner.sanitizeShared in its parser, right before the
 * parser stores it in the model it makes, whether the parser loads the model's type name itself or
 * asks a pool of shared strings for it, as 450's 385611395 and 385611400 do (#77).
 */
class StoryLinkParserTest {
    @Test
    fun aParserNamingItsTypeItselfCleansTheLink() {
        val context = PatchContexts.of(listOf(parser(pooled = false)))

        assertNull(context.cleanStoryLink())

        assertCleanedBeforeTheStore(context.parserCode())
    }

    @Test
    fun aParserAskingAPoolForItsTypeCleansTheLink() {
        val context = PatchContexts.of(listOf(parser(pooled = true), pool()))

        assertNull(context.cleanStoryLink())

        assertCleanedBeforeTheStore(context.parserCode())
    }

    @Test
    fun aParserAskingThePoolForAnotherNameIsNoStoryLinkParser() {
        leftAlone(listOf(parser(pooled = true), pool(answer = "XDTPermalinkResponse")))
    }

    @Test
    fun aPooledParserWithoutItsPoolIsNoStoryLinkParser() {
        leftAlone(listOf(parser(pooled = true)))
    }

    @Test
    fun aParserOfAnotherNameIsNoStoryLinkParser() {
        leftAlone(listOf(parser(pooled = false, name = "parseFromJson")))
    }

    /**
     * On the declared build's fixture and every other build's: the one parser making the story
     * link's model has the cleaner right before it stores the link, on the link's register, and
     * nothing else in it moves.
     */
    @Test
    fun eachBuildCleansItsStoryLink() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        for (bundle in bundles) cleansStoryLink(bundle, "${bundle.parentFile.name}/${bundle.name}")
    }

    private fun cleansStoryLink(bundle: File, label: String) {
        val parsers = FixtureDex.classesHolding(bundle, MODEL_NAME).filter { holder -> holder.methods.any { it.makesModel() } }
        assertEquals("$label: classes holding $MODEL_NAME with a parser making it", 1, parsers.size)
        val type = parsers.single().type
        val before = parsers.single().methods.single { it.makesModel() }.implementation!!.instructions.toList()
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, parsers))

        assertNull(label, context.cleanStoryLink())

        val code = context.mutableClassDefBy(type).methods.single { it.makesModel() }.implementation!!.instructions.toList()
        assertEquals("$label: one call and its result added", before.size + 2, code.size)
        val hook = assertCleanedBeforeTheStore(code, model = MODEL)
        assertEquals(
            "$label: the rest as it was",
            before.map { it.opcode },
            code.filterIndexed { index, _ -> index != hook && index != hook + 1 }.map { it.opcode },
        )
    }

    /** The cleaner is called on the link's register right before the model's String field is stored, once. Answers the call's index. */
    private fun assertCleanedBeforeTheStore(code: List<Instruction>, model: String = LINK_MODEL): Int {
        val store = code.indexOfFirst { instruction ->
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            instruction.opcode == Opcode.IPUT_OBJECT && field?.definingClass == model && field.type == STRING
        }
        assertTrue("the link is stored in the model", store >= 2)
        val link = (code[store] as TwoRegisterInstruction).registerA
        val hook = store - 2
        assertEquals(SANITIZE, ((code[hook] as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals("the link's register", link, (code[hook] as RegisterRangeInstruction).startRegister)
        assertEquals("one register", 1, (code[hook] as RegisterRangeInstruction).registerCount)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("written back", link, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("one call", 1, code.count { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SANITIZE })
        return hook
    }

    private fun leftAlone(classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = context.parserCode().map { it.opcode }
        assertEquals("expected one parser naming $STORY_SHARE_URL_TYPE, found 0", context.cleanStoryLink())
        assertEquals(before, context.parserCode().map { it.opcode })
    }

    private fun BytecodePatchContext.parserCode(): List<Instruction> =
        mutableClassDefBy(PARSER).methods.single().implementation!!.instructions.toList()

    private fun com.android.tools.smali.dexlib2.iface.Method.makesModel() =
        name == "unsafeParseFromJson" && implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == MODEL
        } == true

    private companion object {
        const val PARSER = "Lfixture/StoryLinkParser;"
        const val LINK_MODEL = "Lfixture/StoryLink;"
        const val READER = "Lfixture/JsonReader;"
        const val POOL = "Lfixture/Strings;"
        const val POOLED = 7
        const val STRING = "Ljava/lang/String;"

        /** The model 450's story link parser makes, a kept name, and the name its parser's error path loads. */
        const val MODEL = "Lcom/instagram/api/schemas/StoryItemThirdPartySharingUrlResponseImpl;"
        const val MODEL_NAME = "StoryItemThirdPartySharingUrlResponseImpl"

        /**
         * The parser as 450 has it, cut down: it reads the link, loads the model's type name (itself,
         * or from [POOL] when [pooled]), makes the model with it and stores the link. v0 to v2 are its
         * locals, then the parser and the reader.
         */
        fun parser(pooled: Boolean, name: String = "unsafeParseFromJson"): ClassDef {
            val typeName = if (pooled) {
                """
                    const/16 v0, $POOLED
                    invoke-static { v0 }, $POOL->A00(I)$STRING
                    move-result-object v0
                """
            } else {
                """
                    const-string v0, "$STORY_SHARE_URL_TYPE"
                """
            }
            val body = """
                const-string v0, "$STORY_SHARE_URL_FIELD"
                invoke-static { p1, v0 }, $READER->read($READER$STRING)$STRING
                move-result-object v2
                $typeName
                new-instance v1, $LINK_MODEL
                invoke-direct { v1, v0 }, $LINK_MODEL-><init>($STRING)V
                iput-object v2, v1, $LINK_MODEL->A00:$STRING
                return-object v1
            """.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() }
            val method = MutableMethod(
                ImmutableMethod(
                    PARSER, name, listOf(ImmutableMethodParameter(READER, null, null)), "Ljava/lang/Object;",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(5, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body) }.let(ImmutableMethod::of)
            return ImmutableClassDef(PARSER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method))
        }

        /**
         * A pool of shared strings as 450's Redex writes it: a static (int)String switch answering
         * [answer] for [POOLED]. p0 is v1.
         */
        fun pool(answer: String = STORY_SHARE_URL_TYPE): ClassDef = ImmutableClassDef(
            POOL, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod(
                POOL, "A00", listOf(ImmutableMethodParameter("I", null, null)), STRING,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(2, listOf(
                    ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 8),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(answer)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(POOLED, 5))),
                ), null, null),
            )),
        )
    }
}
