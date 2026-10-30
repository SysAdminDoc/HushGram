/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hide Meta AI's Muse card as a quick promotion: the method that builds a promotion's card answers null when the extension says drop. */
class QuickPromotionHookTest {
    private val owner = "Lfixture/PromotionControllers;"
    private val promotion = "Lfixture/Promotion;"
    private val creative = "Lfixture/Creative;"
    private val assets = "app/hushgram/extension/instagram/metaai/MetaAi"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(DROPS_PROMOTION.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$DROPS_PROMOTION is not in the extension: $declared", DROPS_PROMOTION.substringAfter("->") in declared)
        for (stub in PROMOTION_STUBS) {
            assertTrue("$stub(Object)String is not in the extension: $declared", "$stub(Ljava/lang/Object;)Ljava/lang/String;" in declared)
        }
    }

    @Test
    fun theBuilderAnswersNullWhenTheExtensionSaysDrop() {
        val patch = PatchContexts.of(classes())
        val found = patch.findQuickPromotionBuilder()
        assertEquals(owner, found.type)
        assertEquals("controller", found.name)
        assertEquals(promotion, found.promotion)
        assertEquals("slot", found.slot.name)
        assertEquals("creative", found.creative.name)
        assertEquals(listOf("a", "b", "c"), found.texts.map { it.name })
        assertEquals("value", found.text.name)
        val before = patch.mutableClassDefBy(owner).methods.single { it.name == "controller" }.code()

        patch.holdQuickPromotion(found)

        val after = patch.mutableClassDefBy(owner).methods.single { it.name == "controller" }.code()
        assertAsked("synthetic", before, after)
        val stubs = patch.mutableClassDefBy("L$assets;").methods.filter { it.name in PROMOTION_STUBS }
        assertEquals(PROMOTION_STUBS.size, stubs.size)
        for (stub in stubs) {
            val reads = stub.code().filter { it.opcode == Opcode.IGET_OBJECT }
            assertEquals("${stub.name} reads", if (stub.name == "promotionSlot") 1 else 3, reads.size)
        }
    }

    @Test
    fun noBuilderOrTwoOrAnOddPromotionFailThePatch() {
        val odd = listOf(
            classes(templates = PROMOTION_TEMPLATES.drop(1)) to "methods turn a quick promotion into its card",
            classes(builders = 2) to "methods turn a quick promotion into its card",
            classes(slots = 0) to "casts its last parameter to no class holding a slot",
            // The hook hands over p2, so a cast of the session parameter doesn't name the promotion.
            classes(castOf = "p1") to "casts its last parameter to no class holding a slot",
            classes(slots = 2) to "doesn't hold exactly one slot field",
            classes(texts = 2) to "fields holding three text containers, not one",
            classes(creatives = 2) to "fields holding three text containers, not one",
            classes(strings = 2) to "string fields, not one",
        )
        for ((case, why) in odd) {
            val refused = assertThrows(PatchException::class.java) { PatchContexts.of(case).findQuickPromotionBuilder() }
            assertTrue("${refused.message} names $why", refused.message.orEmpty().contains(why))
        }
    }

    @Test
    fun aBuilderWithNoLocalsFailsThePatch() {
        val patch = PatchContexts.of(classes(registers = 3))
        val found = patch.findQuickPromotionBuilder()
        assertThrows(PatchException::class.java) { patch.holdQuickPromotion(found) }
    }

    /**
     * In each declared build, and in every other build of a declared version, one method names the
     * three templates, the promotion it casts to has one slot and one creative of three texts, and
     * the check goes in ahead of the method's first instruction without changing the rest.
     */
    @Test
    fun eachBuildHasOneQuickPromotionBuilder() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            builds(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesLoading(bundle, PROMOTION_TEMPLATES.first())
        val first = PatchContexts.of(FixtureDex.withStringPools(bundle, holders)).let { context ->
            // The promotion's class and the classes its fields lead to, two levels, as the finder reads them.
            val cast = holders.flatMap { it.methods }.filter { method ->
                PROMOTION_TEMPLATES.all { with(context) { loadsString(method, it) } }
            }.flatMap { it.implementation?.instructions?.toList().orEmpty() }
                .filter { it.opcode == Opcode.CHECK_CAST }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }
            cast.toSet()
        }
        val level1 = FixtureDex.classes(bundle, first).values.flatMap { it.fields.map { field -> field.type } }.toSet()
        val level2 = FixtureDex.classes(bundle, level1).values.flatMap { it.fields.map { field -> field.type } }.toSet()
        val staged = FixtureDex.classes(bundle, first + level1 + level2).values
        val patch = PatchContexts.of(
            (FixtureDex.withStringPools(bundle, holders + staged) + ExtensionDex.classDef("L$assets;")).distinctBy { it.type },
        )
        val found = patch.findQuickPromotionBuilder()
        val before = holders.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()

        patch.holdQuickPromotion(found)

        val after = patch.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()
        assertAsked(label, before, after)
        assertEquals("$label: three texts", 3, found.texts.size)
    }

    /** The ask, its answer, the test, a null back, and then every original instruction in its order (nops aside). */
    private fun assertAsked(what: String, before: List<Instruction>, after: List<Instruction>) {
        assertEquals("$what: the ask comes first", DROPS_PROMOTION, after[0].referenceText())
        assertEquals("$what: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$what: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$what: null", Opcode.CONST_4, after[3].opcode)
        assertEquals("$what: returned", Opcode.RETURN_OBJECT, after[4].opcode)
        assertEquals("$what: one ask", 1, after.count { it.referenceText() == DROPS_PROMOTION })
        assertEquals(
            "$what: the rest is as it was",
            before.filter { it.opcode != Opcode.NOP }.map { it.opcode },
            after.drop(5).filter { it.opcode != Opcode.NOP }.map { it.opcode },
        )
    }

    private fun classes(
        templates: List<String> = PROMOTION_TEMPLATES,
        builders: Int = 1,
        slots: Int = 1,
        creatives: Int = 1,
        texts: Int = 3,
        strings: Int = 1,
        registers: Int = 6,
        castOf: String = "p2",
    ): List<ClassDef> {
        val loads = templates.joinToString("\n") { "const-string v0, \"$it\"" }
        val body = """
            $loads
            check-cast $castOf, $promotion
            const/4 v0, 0x0
            return-object v0
        """
        val flags = AccessFlags.PUBLIC.value
        val controller = listOf("Lfixture/Session;", "Ljava/lang/Object;")
        val first = classDef(owner, emptyList(), listOf(method(owner, "controller", controller, "Lfixture/Controller;", registers, body, flags)))
        val more = (1 until builders).map { copy ->
            val type = "Lfixture/MoreControllers$copy;"
            classDef(type, emptyList(), listOf(method(type, "controller", controller, "Lfixture/Controller;", registers, body, flags)))
        }
        val promotionFields = (0 until slots).map { field(promotion, if (slots == 1) "slot" else "slot$it", PROMOTION_SLOT_TYPE) } +
            (0 until creatives).map { field(promotion, if (creatives == 1) "creative" else "creative$it", creative) }
        val promotionClass = classDef(promotion, promotionFields, emptyList())
        val creativeClass = classDef(creative, (0 until texts).map { field(creative, "abc"[it].toString(), PROMOTION_TEXT_TYPE) }, emptyList())
        val textClass = classDef(
            PROMOTION_TEXT_TYPE,
            listOf(field(PROMOTION_TEXT_TYPE, "value", "Ljava/lang/String;")) +
                (1 until strings).map { field(PROMOTION_TEXT_TYPE, "other$it", "Ljava/lang/String;") },
            emptyList(),
        )
        return listOf(first) + more + promotionClass + creativeClass + textClass + ExtensionDex.classDef("L$assets;")
    }

    private fun field(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, fields: List<ImmutableField>, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
