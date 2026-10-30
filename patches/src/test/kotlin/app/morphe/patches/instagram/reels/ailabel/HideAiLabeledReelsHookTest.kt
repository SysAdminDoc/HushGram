/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.ailabel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideAiLabeledReelsHookTest {
    /** The page filter and the four stubs the patch fills are in the AiLabeledReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(AI_LABELED_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(
            AI_PAGE_FILTER.substringAfter("->"),
            "media(Ljava/lang/Object;)Ljava/lang/Object;",
            "labelInfo(Ljava/lang/Object;)Ljava/lang/Object;",
            "readsLabel(Ljava/lang/Object;)Ljava/lang/Boolean;",
            "labelText(Ljava/lang/Object;)Ljava/lang/String;",
        )) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
    }

    /**
     * Media's label getter is the one loading the hash of ai_label_info, and the label's two getters
     * are the tree-backed class's, called through the interface. The class holding the same type
     * name with no hashes, Instagram's own copy of a label, isn't taken for the tree. The stubs read
     * the item's post, the post's label and the label's two fields.
     */
    @Test
    fun theLabelIsReadThroughItsInterface() {
        val context = PatchContexts.of(standIns())
        val sites = context.findAiLabel()

        assertEquals("label", sites.getter.name)
        assertEquals(LABEL, sites.labelType)
        assertEquals("reads", sites.reads)
        assertEquals("text", sites.text)

        context.fillAiLabelStubs(page(), sites)
        assertStubsFilled("the stand-in", context, ITEM, "post", sites)
    }

    /** A build the finder can't read exactly fails at patch time, saying what it found, before anything is written. */
    @Test
    fun anythingItCantFindExactlyFailsThePatch() {
        val cases = listOf(
            standIns(getters = 0) to "expected one getter on $MEDIA for $AI_LABEL_FIELD, found []",
            standIns(getters = 2) to "expected one getter on $MEDIA for $AI_LABEL_FIELD, found [label, label2]",
            standIns(getterPublic = false) to "$MEDIA->label isn't public, so the extension can't reach it",
            standIns(labelInterface = false) to "$LABEL, which $MEDIA->label answers, isn't a public interface",
            standIns(typeName = "XDTAIGMAccountLabelInfo") to "expected one $AI_LABEL_TYPE_NAME class implementing $LABEL to read $READS_LABEL_FIELD, found []",
            standIns(treeImplements = false) to "expected one $AI_LABEL_TYPE_NAME class implementing $LABEL to read $READS_LABEL_FIELD, found []",
            standIns(twoTrees = true) to "expected one $AI_LABEL_TYPE_NAME class implementing $LABEL to read $READS_LABEL_FIELD, found [$TREE, Lfixture/LabelTree2;]",
            standIns(declaresReads = false) to "$LABEL doesn't declare reads, the getter for $READS_LABEL_FIELD",
            standIns(textReturns = "Ljava/lang/CharSequence;") to "expected one getter on $TREE answering Ljava/lang/String; for $LABEL_TEXT_FIELD, found []",
            standIns(extension = false) to "the extension has no $AI_LABELED_REELS",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findAiLabel() }
            assertTrue("$expected: ${failure.message}", failure.message == "Hide AI-labeled reels: $expected")
        }
    }

    /** The declared build of 451: Instagram's own AI label, read the way Instagram decides to show it. */
    @Test
    fun theDeclaredBuildReadsTheLabelInstagramShows() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                checkBuild(bundle.name, bundle)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other seven builds of 451 (#77, #95), where every class and getter has another short name. */
    @Test
    fun everyOtherBuildReadsTheLabelInstagramShows() {
        val checked = Fixtures.otherBuilds().map { bundle ->
            checkBuild(bundle.parentFile.name, bundle)
            bundle.parentFile.name
        }
        assertEquals("the other builds: $checked", Fixtures.OTHER_BUILDS, checked.size)
    }

    /**
     * Patches the slice of [bundle] the finder reads and checks what it found against Instagram's
     * own use of the label, read off the code here rather than the finder:
     *
     * - The getter is Media's one loading the hash of ai_label_info, and the label's getters are the
     *   ones of the class whose type name is XDTAILabelInfo loading the hashes of
     *   should_read_attribution_label and attribution_label_text.
     * - Instagram's test for showing the AI label under a reel (both copies of the method marked
     *   ClipsMediaItemInfoUtil_shouldShowAIGMMediaLabelSubtitle) reads those three fields and hands
     *   the flag and the text to one check. That check answers true only past Boolean.TRUE.equals on
     *   the flag and String.length on the text, which is the positive label the extension takes.
     * - The Reels impression logger, which names the label's type "ai_label_type", reads the same
     *   two getters through the same interface off the same Media getter.
     */
    private fun checkBuild(label: String, bundle: File) {
        val media = FixtureDex.classes(bundle, setOf(MEDIA)).values.single()
        val getter = media.methods.single { it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags) && it.loads(AI_LABEL_FIELD.hashCode()) }
        val labelType = getter.returnType
        val implementers = mutableListOf<ClassDef>()
        val interfaces = mutableListOf<ClassDef>()
        val shows = mutableListOf<Method>()
        FixtureDex.forEach(bundle) { dex ->
            val marked = dex.stringSection.any { it.endsWith("_$LABEL_SUBTITLE") }
            for (classDef in dex.classes) {
                if (classDef.type == labelType) interfaces += ImmutableClassDef.of(classDef)
                if (labelType in classDef.interfaces) implementers += ImmutableClassDef.of(classDef)
                if (marked) classDef.methods.filter { LABEL_SUBTITLE in it.markers() }.forEach { shows += ImmutableMethod.of(it) }
            }
        }
        val named = FixtureDex.classesLoading(bundle, AI_LABEL_TYPE_NAME)
        val classes = FixtureDex.withStringPools(bundle, (listOf(media) + interfaces + implementers + named).distinctBy { it.type })
        val context = PatchContexts.of(classes + ExtensionDex.classDef(AI_LABELED_REELS))

        val sites = context.findAiLabel()

        assertEquals("$label: Media's label getter", getter.name, sites.getter.name)
        assertEquals("$label: the label interface", labelType, sites.labelType)
        val tree = implementers.single { classDef -> classDef.methods.any { method -> method.code().any { it.string() == AI_LABEL_TYPE_NAME } } &&
            classDef.methods.any { it.loads(READS_LABEL_FIELD.hashCode()) } }
        assertTrue("$label: ${sites.reads} reads $READS_LABEL_FIELD", tree.methods.single { it.name == sites.reads && it.parameterTypes.isEmpty() }.loads(READS_LABEL_FIELD.hashCode()))
        assertTrue("$label: ${sites.text} reads $LABEL_TEXT_FIELD", tree.methods.single { it.name == sites.text && it.parameterTypes.isEmpty() }.loads(LABEL_TEXT_FIELD.hashCode()))

        assertEquals("$label: Instagram's tests for showing the AI label under a reel (452 folds the two into one)", 1, shows.size)
        val checks = shows.map { method ->
            for (field in listOf(AI_LABEL_FIELD, READS_LABEL_FIELD, LABEL_TEXT_FIELD)) {
                assertTrue("$label: ${method.definingClass}->${method.name} doesn't read $field", method.loads(field.hashCode()))
            }
            method.code().mapNotNull { it.method() }.single { called ->
                called.returnType == "Z" && called.parameterTypes.map(Any::toString) == listOf(SESSION, BOOLEAN, STRING, "Z", "Z")
            }
        }.distinctBy { "${it.definingClass}->${it.name}" }
        for (check in checks) {
            val body = FixtureDex.classes(bundle, setOf(check.definingClass)).values.single().methods.single {
                it.name == check.name && it.parameterTypes.map(Any::toString) == check.parameterTypes.map(Any::toString)
            }
            val registers = body.implementation!!.registerCount
            val flag = registers - 4
            val text = registers - 3
            val code = body.code()
            assertTrue("$label: the check doesn't ask Boolean.TRUE.equals(flag)", code.any { instruction ->
                instruction.method()?.let { it.definingClass == OBJECT && it.name == "equals" } == true &&
                    (instruction as? FiveRegisterInstruction)?.registerD == flag
            })
            assertTrue("$label: the check doesn't ask the text's length", code.any { instruction ->
                instruction.method()?.let { it.definingClass == STRING && it.name == "length" } == true &&
                    (instruction as? FiveRegisterInstruction)?.registerC == text
            })
        }

        val logger = FixtureDex.classesLoading(bundle, "ai_label_type").flatMap { it.methods }.filter { method ->
            val called = method.code().mapNotNull { it.method() }
            called.any { it.definingClass == MEDIA && it.name == sites.getter.name } &&
                called.any { it.definingClass == labelType && it.name == sites.reads && it.returnType == BOOLEAN } &&
                called.any { it.definingClass == labelType && it.name == sites.text && it.returnType == STRING }
        }
        assertTrue("$label: nothing logging ai_label_type reads ${sites.reads} and ${sites.text}", logger.isNotEmpty())

        context.fillAiLabelStubs(page(), sites)
        assertStubsFilled(label, context, ITEM, "post", sites)
    }

    private fun assertStubsFilled(what: String, context: app.morphe.patcher.patch.BytecodePatchContext, item: String, mediaField: String, sites: AiLabelSites) {
        val stubs = context.mutableClassDefBy(AI_LABELED_REELS).methods
        fun body(name: String) = stubs.single { it.name == name && it.parameterTypes.map(Any::toString) == listOf(OBJECT) }.code()
            .map { listOfNotNull(it.opcode.name, it.referenceText()).joinToString(" ") }
        assertEquals(
            "$what: media",
            listOf("instance-of $item", "if-nez", "const/4", "return-object", "check-cast $item", "iget-object $item->$mediaField:$MEDIA", "return-object"),
            body("media"),
        )
        assertEquals(
            "$what: labelInfo",
            listOf("check-cast $MEDIA", "invoke-virtual $MEDIA->${sites.getter.name}()${sites.labelType}", "move-result-object", "return-object"),
            body("labelInfo"),
        )
        assertEquals(
            "$what: readsLabel",
            listOf("check-cast ${sites.labelType}", "invoke-interface ${sites.labelType}->${sites.reads}()$BOOLEAN", "move-result-object", "return-object"),
            body("readsLabel"),
        )
        assertEquals(
            "$what: labelText",
            listOf("check-cast ${sites.labelType}", "invoke-interface ${sites.labelType}->${sites.text}()$STRING", "move-result-object", "return-object"),
            body("labelText"),
        )
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.loads(value: Int) = code().any { it is NarrowLiteralInstruction && it.opcode.name.startsWith("const") && it.narrowLiteral == value }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.method() = reference() as? MethodReference

    private fun Instruction.string() = (reference() as? StringReference)?.string

    private fun Instruction.referenceText(): String? = when (val reference = reference()) {
        is MethodReference -> "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"
        is TypeReference -> reference.type
        is com.android.tools.smali.dexlib2.iface.reference.FieldReference -> "${reference.definingClass}->${reference.name}:${reference.type}"
        else -> null
    }

    private companion object {
        const val LABEL_SUBTITLE = "ClipsMediaItemInfoUtil_shouldShowAIGMMediaLabelSubtitle"
        const val LABEL = "Lfixture/Label;"
        const val TREE = "Lfixture/LabelTree;"
        const val ITEM = "Lfixture/ClipsItem;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val OBJECT = "Ljava/lang/Object;"
        const val BOOLEAN = "Ljava/lang/Boolean;"
        const val STRING = "Ljava/lang/String;"
        val PUBLIC = AccessFlags.PUBLIC.value
        val ABSTRACT = AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value

        /** The page's sites as far as the stubs read them: the item type and its post field. */
        fun page() = ReelsPageSites("Lfixture/Callback;", "success", "Lfixture/Source;", 0, 0, 0, "module", ITEM, "post")

        /**
         * Stand-ins shaped like 451's: Media with one getter loading ai_label_info's hash, the label
         * interface it answers, the tree-backed class answering XDTAILabelInfo with a getter per
         * field, and Instagram's own copy of a label, which holds the type name and no hashes.
         */
        fun standIns(
            getters: Int = 1,
            getterPublic: Boolean = true,
            labelInterface: Boolean = true,
            typeName: String = AI_LABEL_TYPE_NAME,
            treeImplements: Boolean = true,
            twoTrees: Boolean = false,
            declaresReads: Boolean = true,
            textReturns: String = STRING,
            extension: Boolean = true,
        ): List<ClassDef> {
            val labelGetters = (1..getters).map { i ->
                method(
                    MEDIA, if (i == 1) "label" else "label$i", emptyList(), LABEL,
                    if (getterPublic) PUBLIC or AccessFlags.FINAL.value else AccessFlags.PRIVATE.value, 2,
                    """
                        const v0, ${AI_LABEL_FIELD.hashCode()}
                        const/4 v0, 0x0
                        return-object v0
                    """,
                )
            }
            val media = ImmutableClassDef(MEDIA, PUBLIC, OBJECT, null, null, null, null, labelGetters)
            val interfaceMethods = listOfNotNull(
                if (declaresReads) abstract(LABEL, "reads", BOOLEAN) else null,
                abstract(LABEL, "text", STRING),
            )
            val label = if (labelInterface) {
                ImmutableClassDef(LABEL, ABSTRACT or AccessFlags.INTERFACE.value, OBJECT, null, null, null, null, interfaceMethods)
            } else {
                ImmutableClassDef(LABEL, PUBLIC, OBJECT, null, null, null, null, emptyList())
            }
            fun tree(type: String) = ImmutableClassDef(
                type, PUBLIC or AccessFlags.FINAL.value, OBJECT, if (treeImplements) listOf(LABEL) else null, null, null, null,
                listOf(
                    method(type, "getTypeName", emptyList(), STRING, PUBLIC, 1, "const-string v0, \"$typeName\"\nreturn-object v0"),
                    method(type, "reads", emptyList(), BOOLEAN, PUBLIC, 2, "const v0, ${READS_LABEL_FIELD.hashCode()}\nconst/4 v0, 0x0\nreturn-object v0"),
                    method(type, "text", emptyList(), textReturns, PUBLIC, 2, "const v0, ${LABEL_TEXT_FIELD.hashCode()}\nconst/4 v0, 0x0\nreturn-object v0"),
                ),
            )
            val copy = ImmutableClassDef(
                "Lfixture/LabelData;", PUBLIC or AccessFlags.FINAL.value, OBJECT, listOf(LABEL), null, null, null,
                listOf(
                    method("Lfixture/LabelData;", "<init>", emptyList(), "V", PUBLIC or AccessFlags.CONSTRUCTOR.value, 2,
                        "const-string v0, \"$AI_LABEL_TYPE_NAME\"\nreturn-void"),
                    method("Lfixture/LabelData;", "reads", emptyList(), BOOLEAN, PUBLIC, 1, "const/4 v0, 0x0\nreturn-object v0"),
                    method("Lfixture/LabelData;", "text", emptyList(), STRING, PUBLIC, 1, "const/4 v0, 0x0\nreturn-object v0"),
                ),
            )
            return listOfNotNull(
                media, label, tree(TREE), copy,
                if (twoTrees) tree("Lfixture/LabelTree2;") else null,
                if (extension) ExtensionDex.classDef(AI_LABELED_REELS) else null,
            )
        }

        fun abstract(type: String, name: String, returns: String): Method =
            ImmutableMethod(type, name, emptyList(), returns, ABSTRACT, null, null, null)

        fun method(type: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, body: String): Method =
            ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n")) },
            )
    }
}
