/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide ads in the comments (#415): the comments sheet's settings keep "enable_comment_ads" in a
 * field their constructor writes, and every write of it goes through the extension first.
 */
class CommentAdsHookTest {
    private val sheet = "Lfixture/CommentsSheetSettings;"
    private val saver = "Lfixture/CommentsSheetSaver;"
    private val opener = "Lfixture/CommentsSheetOpener;"
    private val flag = "$sheet->a:Z"
    private val putBoolean = "Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V"
    private val putString = "Landroid/os/BaseBundle;->putString(Ljava/lang/String;Ljava/lang/String;)V"

    /** The hook the patch writes is in the Ads the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(COMMENT_ADS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_ADS is not in the extension: $declared", COMMENT_ADS.substringAfter("->") in declared)
    }

    /**
     * The field read into the key's putBoolean is the flag, a literal put elsewhere doesn't count,
     * and the constructor's write gets the extension's answer, a branch onto it included.
     */
    @Test
    fun theConstructorsWriteIsAnswered() {
        val context = PatchContexts.of(classes())

        val found = context.findCommentAds()
        assertEquals(flag, found.field)
        assertEquals(sheet, found.type)
        context.hideCommentAds(found)

        val init = context.mutableClassDefBy(sheet).methods.single { it.name == "<init>" }
        assertAnswered("stand-in", init)
        val code = init.code()
        val hook = code.indexOfFirst { it.referenceText() == COMMENT_ADS }
        val branch = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch onto the write runs the hook", hook, branch.target.location.index)
        val other = context.mutableClassDefBy(sheet).methods.single { it.name == "other" }
        assertTrue("another field's write was hooked", other.code().none { it.referenceText() == COMMENT_ADS })
    }

    @Test
    fun noFieldBehindTheKeyFailsThePatch() {
        refuses("found 0") { PatchContexts.of(classes(saved = false)).findCommentAds() }
    }

    @Test
    fun twoFieldsBehindTheKeyFailThePatch() {
        refuses("found 2") { PatchContexts.of(classes(secondField = true)).findCommentAds() }
    }

    @Test
    fun aFlagNobodyWritesFailsThePatch() {
        refuses("never writes") { PatchContexts.of(classes(written = false)).findCommentAds() }
    }

    /** A build where the flag moved leaves the rest of Hide ads to go in. */
    @Test
    fun aMovedFlagOnlyWarns() {
        assertNull(PatchContexts.of(classes(saved = false)).findCommentAdsOrWarn())
    }

    /**
     * In each declared build the flag is one boolean of the comments sheet's settings, written in
     * their constructor, and every write is answered. On 453's 385914510 that's LX/0KhM->A1G.
     */
    @Test
    fun eachDeclaredBuildTurnsTheCommentsSheetsAdOff() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = FixtureDex.classesLoading(bundle, ENABLE_COMMENT_ADS)
            val owners = holders.flatMap { it.methods }.flatMap { it.code() }
                .filter { it.opcode == Opcode.IGET_BOOLEAN }
                .map { ((it as ReferenceInstruction).reference as FieldReference).definingClass }.toSet()
            val classes = holders.associateBy { it.type }.toMutableMap()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) if (classDef.type in owners && classDef.type !in classes) classes[classDef.type] = ImmutableClassDef.of(classDef)
            }
            val context = PatchContexts.of(classes.values.toList())

            val found = context.findCommentAds()
            context.hideCommentAds(found)

            assertTrue("${bundle.name}: ${found.field} isn't a boolean", found.field.endsWith(":Z"))
            val methods = context.mutableClassDefBy(found.type).methods
            val writes = methods.flatMap { m -> m.code().filter { it.writes(found.field) } }.size
            assertTrue("${bundle.name}: no constructor writes ${found.field}", methods.any { m -> m.name == "<init>" && m.code().any { it.writes(found.field) } })
            assertEquals("${bundle.name}: hooks", writes, methods.sumOf { m -> m.code().count { it.referenceText() == COMMENT_ADS } })
            methods.filter { m -> m.code().any { it.writes(found.field) } }.forEach { assertAnswered("${bundle.name} ${found.type}->${it.name}", it, found.field) }
        }
    }

    /** The card hook the patch writes is in the Ads the bundle ships, public and static (#857). */
    @Test
    fun theCardHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(COMMENT_CARD.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_CARD is not in the extension: $declared", COMMENT_CARD.substringAfter("->") in declared)
    }

    /**
     * #857: the two card types the saver puts under "ad_card_type" and "organic_card_type" are found,
     * a string put under another key isn't, and each constructor write gets the extension's answer.
     */
    @Test
    fun theConstructorsCardWritesAreAnswered() {
        val context = PatchContexts.of(classes())

        val found = context.findCommentCards()
        assertEquals(listOf("$sheet->d:Ljava/lang/String;", "$sheet->e:Ljava/lang/String;"), found.fields)
        assertEquals(sheet, found.type)
        context.hideCommentCards(found)

        val init = context.mutableClassDefBy(sheet).methods.single { it.name == "<init>" }
        found.fields.forEach { assertCardAnswered("stand-in", init, it) }
        val code = init.code()
        assertEquals("one hook per card", 2, code.count { it.referenceText() == COMMENT_CARD })
        val other = context.mutableClassDefBy(sheet).methods.single { it.name == "other" }
        assertTrue("another field's write was hooked", other.code().none { it.referenceText() == COMMENT_CARD })
    }

    @Test
    fun aMissingCardFailsTheCardSearch() {
        refuses("found 0") { PatchContexts.of(classes(cardSaved = false)).findCommentCards() }
        assertNull(PatchContexts.of(classes(cardSaved = false)).findCommentCardsOrWarn())
    }

    /**
     * In each declared build both card types are strings of the comments sheet's settings, the class
     * that keeps the comment ads flag, written in their constructor, and every write is answered.
     */
    @Test
    fun eachDeclaredBuildTakesTheCommentsSheetsCardOut() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = FixtureDex.classesLoading(bundle, ENABLE_COMMENT_ADS)
            val owners = holders.flatMap { it.methods }.flatMap { it.code() }
                .filter { it.opcode == Opcode.IGET_BOOLEAN || it.opcode == Opcode.IGET_OBJECT }
                .map { ((it as ReferenceInstruction).reference as FieldReference).definingClass }.toSet()
            val classes = holders.associateBy { it.type }.toMutableMap()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) if (classDef.type in owners && classDef.type !in classes) classes[classDef.type] = ImmutableClassDef.of(classDef)
            }
            val context = PatchContexts.of(classes.values.toList())

            val flag = context.findCommentAds()
            val found = context.findCommentCards()
            context.hideCommentCards(found)

            assertEquals("${bundle.name}: the cards' class", flag.type, found.type)
            assertEquals("${bundle.name}: ${found.fields}", 2, found.fields.size)
            assertTrue("${bundle.name}: ${found.fields} aren't strings", found.fields.all { it.endsWith(":Ljava/lang/String;") })
            val methods = context.mutableClassDefBy(found.type).methods
            val writes = methods.sumOf { m -> m.code().count { i -> found.fields.any { i.writesObject(it) } } }
            assertTrue("${bundle.name}: hooks", writes >= 2)
            assertEquals("${bundle.name}: hooks", writes, methods.sumOf { m -> m.code().count { it.referenceText() == COMMENT_CARD } })
            for (field in found.fields) {
                assertTrue("${bundle.name}: no constructor writes $field", methods.any { m -> m.name == "<init>" && m.code().any { it.writesObject(field) } })
                methods.filter { m -> m.code().any { it.writesObject(field) } }.forEach { assertCardAnswered("${bundle.name} ${found.type}->${it.name}", it, field) }
            }
        }
    }

    /** Right before each write of [field]: the range call with the written register, and its answer back in it. */
    private fun assertCardAnswered(what: String, method: Method, field: String) {
        val code = method.code()
        val writes = code.indices.filter { code[it].writesObject(field) }
        assertTrue("$what: no write of $field", writes.isNotEmpty())
        for (write in writes) {
            val register = (code[write] as TwoRegisterInstruction).registerA
            assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[write - 2].opcode)
            assertEquals("$what: the hook", COMMENT_CARD, code[write - 2].referenceText())
            assertEquals("$what: what it's handed", register, (code[write - 2] as RegisterRangeInstruction).startRegister)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[write - 1].opcode)
            assertEquals("$what: the register", register, (code[write - 1] as OneRegisterInstruction).registerA)
        }
    }

    /** Right before each write of the flag: the range call with the written register, and its answer back in it. */
    private fun assertAnswered(what: String, method: Method, field: String = flag) {
        val code = method.code()
        val writes = code.indices.filter { code[it].writes(field) }
        assertTrue("$what: no write of $field", writes.isNotEmpty())
        for (write in writes) {
            val register = (code[write] as TwoRegisterInstruction).registerA
            assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[write - 2].opcode)
            assertEquals("$what: the hook", COMMENT_ADS, code[write - 2].referenceText())
            assertEquals("$what: what it's handed", register, (code[write - 2] as RegisterRangeInstruction).startRegister)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[write - 1].opcode)
            assertEquals("$what: the register", register, (code[write - 1] as OneRegisterInstruction).registerA)
        }
    }

    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /**
     * Shaped like 453's: the settings' constructor writes the flag past a branch onto that write,
     * a saver puts it into a Bundle under the key, and an opener puts a literal under the same key.
     */
    private fun classes(saved: Boolean = true, secondField: Boolean = false, written: Boolean = true, cardSaved: Boolean = true): List<ClassDef> {
        val init = method(
            sheet, "<init>", listOf("Z", "Z", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"), "V", 1,
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            listOfNotNull(
                "invoke-direct {p0}, Ljava/lang/Object;-><init>()V",
                "const/4 v0, 0x0",
                "if-eqz p2, :write",
                "move p1, v0",
                ":write",
                if (written) "iput-boolean p1, p0, $flag" else "nop",
                "iput-boolean p2, p0, $sheet->b:Z",
                "iput-object p3, p0, $sheet->d:Ljava/lang/String;",
                "iput-object p4, p0, $sheet->e:Ljava/lang/String;",
                "iput-object p5, p0, $sheet->f:Ljava/lang/String;",
                "return-void",
            ).joinToString("\n"),
        )
        val other = method(sheet, "other", listOf("Z"), "V", 0, AccessFlags.PUBLIC.value, "iput-boolean p1, p0, $sheet->c:Z\nreturn-void")
        val save = method(
            saver, "A00", listOf("Landroid/os/Bundle;", sheet), "V", 2, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            listOfNotNull(
                "const-string v0, \"$ENABLE_COMMENT_ADS\"",
                if (saved) "iget-boolean v1, p1, $flag" else "const/4 v1, 0x1",
                "invoke-virtual {p0, v0, v1}, $putBoolean",
                if (secondField) "const-string v0, \"$ENABLE_COMMENT_ADS\"" else null,
                if (secondField) "iget-boolean v1, p1, $sheet->b:Z" else null,
                if (secondField) "invoke-virtual {p0, v0, v1}, $putBoolean" else null,
                "const-string v0, \"$AD_CARD_TYPE\"",
                if (cardSaved) "iget-object v1, p1, $sheet->d:Ljava/lang/String;" else "const-string v1, \"iab_card\"",
                "invoke-virtual {p0, v0, v1}, $putString",
                "const-string v0, \"$ORGANIC_CARD_TYPE\"",
                "iget-object v1, p1, $sheet->e:Ljava/lang/String;",
                "invoke-virtual {p0, v0, v1}, $putString",
                "const-string v0, \"organic_product_destination_url\"",
                "iget-object v1, p1, $sheet->f:Ljava/lang/String;",
                "invoke-virtual {p0, v0, v1}, $putString",
                "return-void",
            ).joinToString("\n"),
        )
        val open = method(
            opener, "A00", listOf("Landroid/os/Bundle;"), "V", 2, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            "const-string v0, \"$ENABLE_COMMENT_ADS\"\nconst/4 v1, 0x0\ninvoke-virtual {p0, v0, v1}, $putBoolean\nreturn-void",
        )
        return listOf(
            classDef(sheet, listOf(init, other)),
            classDef(saver, listOf(save)),
            classDef(opener, listOf(open)),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + parameters.size + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.writes(field: String): Boolean =
        opcode == Opcode.IPUT_BOOLEAN && ((this as ReferenceInstruction).reference as FieldReference).toString() == field

    private fun Instruction.writesObject(field: String): Boolean =
        opcode == Opcode.IPUT_OBJECT && ((this as ReferenceInstruction).reference as FieldReference).toString() == field
}
