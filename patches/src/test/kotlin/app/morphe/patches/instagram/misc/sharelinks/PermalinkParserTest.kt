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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
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
 * A post or reel's copied link goes through LinkCleaner.sanitizeShared in the parser of the
 * server's copy-link answer, right before the parser stores it in the model it makes, whether the
 * parser loads the answer's type name and its "permalink" field itself or asks a pool of shared
 * strings for them, as 450's x86 build (385611439) does for the type name (#95).
 */
class PermalinkParserTest {
    @Test
    fun aParserLoadingBothNamesItselfCleansTheLink() {
        val context = PatchContexts.of(listOf(parser()))

        assertNull(context.cleanPermalink())

        assertCleanedBeforeTheStore(context.parserCode())
    }

    @Test
    fun aParserAskingAPoolForItsTypeCleansTheLink() {
        val context = PatchContexts.of(listOf(parser(pooledType = true), pool()))

        assertNull(context.cleanPermalink())

        assertCleanedBeforeTheStore(context.parserCode())
    }

    @Test
    fun aParserAskingAPoolForItsFieldCleansTheLink() {
        val context = PatchContexts.of(listOf(parser(pooledField = true), pool()))

        assertNull(context.cleanPermalink())

        assertCleanedBeforeTheStore(context.parserCode())
    }

    @Test
    fun aParserAskingThePoolForAnotherTypeIsNoPermalinkParser() {
        leftAlone(listOf(parser(pooledType = true), pool(type = STORY_SHARE_URL_TYPE)))
    }

    @Test
    fun aPooledParserWithoutItsPoolIsNoPermalinkParser() {
        leftAlone(listOf(parser(pooledType = true)))
    }

    @Test
    fun aParserReadingAnotherFieldIsNoPermalinkParser() {
        leftAlone(listOf(parser(field = "share_url")))
    }

    @Test
    fun aParserOfAnotherNameIsNoPermalinkParser() {
        leftAlone(listOf(parser(name = "parseFromJson")))
    }

    /**
     * On the declared build's fixture and every other build's: the one parser of the copy-link
     * answer has the cleaner right before it stores the link in the model it makes, on the link's
     * register, and nothing else in it moves.
     */
    @Test
    fun eachBuildCleansItsPermalink() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        for (bundle in bundles) cleansPermalink(bundle, "${bundle.parentFile.name}/${bundle.name}")
    }

    private fun cleansPermalink(bundle: File, label: String) {
        val parsers = FixtureDex.classesHolding(bundle, MODEL_NAME).filter { holder -> holder.methods.any { it.isNativeParser() } }
        assertEquals("$label: classes holding $MODEL_NAME with a parser", 1, parsers.size)
        val type = parsers.single().type
        val before = parsers.single().methods.single { it.isNativeParser() }.implementation!!.instructions.toList()
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, parsers))

        assertNull(label, context.cleanPermalink())

        val code = context.mutableClassDefBy(type).methods.single { it.isNativeParser() }.implementation!!.instructions.toList()
        assertEquals("$label: one call and its result added", before.size + 2, code.size)
        val hook = code.indexOfFirst { it.call() == SANITIZE }
        assertTrue("$label: the cleaner is called", hook >= 0)
        val store = code[hook + 2]
        assertEquals("$label: the link is stored straight after", Opcode.IPUT_OBJECT, store.opcode)
        val field = (store as ReferenceInstruction).reference as FieldReference
        assertEquals("$label: a String field", STRING, field.type)
        val made = code.take(hook).last { it.opcode == Opcode.NEW_INSTANCE }
        assertEquals("$label: of the model made last", ((made as ReferenceInstruction).reference as TypeReference).type, field.definingClass)
        assertCleaned(label, code, hook, (store as TwoRegisterInstruction).registerA)
        assertEquals(
            "$label: the rest as it was",
            before.map { it.opcode },
            code.filterIndexed { index, _ -> index != hook && index != hook + 1 }.map { it.opcode },
        )
    }

    /** The cleaner is called on the link's register right before the model's String field is stored, once. */
    private fun assertCleanedBeforeTheStore(code: List<Instruction>) {
        val store = code.indexOfFirst { instruction ->
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            instruction.opcode == Opcode.IPUT_OBJECT && field?.definingClass == LINK_MODEL && field.type == STRING
        }
        assertTrue("the link is stored in the model", store >= 2)
        assertCleaned("the stand-in", code, store - 2, (code[store] as TwoRegisterInstruction).registerA)
    }

    private fun assertCleaned(label: String, code: List<Instruction>, hook: Int, link: Int) {
        assertEquals("$label: the cleaner", SANITIZE, code[hook].call())
        assertEquals("$label: the link's register", link, (code[hook] as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, (code[hook] as RegisterRangeInstruction).registerCount)
        assertEquals("$label: its answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$label: written back", link, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: one call", 1, code.count { it.call() == SANITIZE })
    }

    private fun leftAlone(classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = context.parserCode().map { it.opcode }
        assertEquals("expected one parser naming $PERMALINK_TYPE, found 0", context.cleanPermalink())
        assertEquals(before, context.parserCode().map { it.opcode })
    }

    private fun BytecodePatchContext.parserCode(): List<Instruction> =
        mutableClassDefBy(PARSER).methods.single().implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Method.isNativeParser() = name == "unsafeParseFromJson" && returnType == "Ljava/lang/Object;" &&
        implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == MODEL_NAME } == true

    private companion object {
        const val PARSER = "Lfixture/PermalinkParser;"
        const val LINK_MODEL = "Lfixture/Permalink;"
        const val READER = "Lfixture/JsonReader;"
        const val POOL = "Lfixture/Strings;"
        const val POOLED_TYPE = 7
        const val POOLED_FIELD = 8
        const val STRING = "Ljava/lang/String;"

        /** The name 450's copy-link parser loads on its error path, in every build. */
        const val MODEL_NAME = "PermalinkResponseImpl"

        /**
         * The parser as 450 has it, cut down: it reads the link by its [field] name, loads the
         * answer's type name, makes the model with it and stores the link. Either name is loaded
         * itself or asked of [POOL]. v0 to v2 are its locals, then the parser and the reader.
         */
        fun parser(
            pooledType: Boolean = false,
            pooledField: Boolean = false,
            field: String = PERMALINK_FIELD,
            name: String = "unsafeParseFromJson",
        ): ClassDef {
            val load = { pooled: Boolean, number: Int, value: String ->
                if (pooled) "const/16 v0, $number\ninvoke-static { v0 }, $POOL->A00(I)$STRING\nmove-result-object v0"
                else "const-string v0, \"$value\""
            }
            val body = """
                ${load(pooledField, POOLED_FIELD, field)}
                invoke-static { p1, v0 }, $READER->read($READER$STRING)$STRING
                move-result-object v2
                ${load(pooledType, POOLED_TYPE, PERMALINK_TYPE)}
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
         * [type] for [POOLED_TYPE] and [field] for [POOLED_FIELD]. p0 is v1; the nop keeps the
         * switch's table on an even code unit.
         */
        fun pool(type: String = PERMALINK_TYPE, field: String = PERMALINK_FIELD): ClassDef = ImmutableClassDef(
            POOL, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod(
                POOL, "A00", listOf(ImmutableMethodParameter("I", null, null)), STRING,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(2, listOf(
                    ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 12),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(type)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(field)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ImmutableInstruction10x(Opcode.NOP),
                    ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(POOLED_TYPE, 5), ImmutableSwitchElement(POOLED_FIELD, 8))),
                ), null, null),
            )),
        )
    }
}
