/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.captionwords

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patch's own part: the extension's hooks, the caption it reads and what it refuses. The
 * caption bridges are read on every build of 451 by DownloadVideoHookTest, and the Reels page join
 * by the paid partnership tests.
 */
class HideCaptionWordReelsHookTest {
    /** The page filter and the item stub the patch fills are in the CaptionWordReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(CAPTION_WORD_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(CAPTION_PAGE_FILTER.substringAfter("->"), "media(Ljava/lang/Object;)Ljava/lang/Object;")) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
    }

    /**
     * The item stub reads the item's post, and the caption bridges read Media's caption getter, the
     * one loading the hash of caption, then the comment's text through its interface.
     */
    @Test
    fun theStubAndTheCaptionBridgesAreFilled() {
        val context = PatchContexts.of(standIns())
        val writeCaption = context.findCaptionWordReels()

        context.fillReelsItemMediaForTest(writeCaption)

        assertEquals(
            listOf("instance-of $ITEM", "if-nez", "const/4", "return-object", "check-cast $ITEM", "iget-object $ITEM->post:$MEDIA", "return-object"),
            context.body(CAPTION_WORD_REELS, "media"),
        )
        // The bridge goes in ahead of the stub's own null return, which is left behind it unreached.
        assertEquals(
            listOf("check-cast $MEDIA", "invoke-virtual $MEDIA->cap()$COMMENT", "move-result-object", "return-object", "const/4", "return-object"),
            context.body(INSTAGRAM_MEDIA, "caption"),
        )
        assertEquals(
            listOf("check-cast $COMMENT", "invoke-interface $COMMENT->words()$STRING", "move-result-object", "return-object", "const/4", "return-object"),
            context.body(INSTAGRAM_MEDIA, "captionText"),
        )
    }

    /** A caption bridge Download any video wrote first is left as it is, so both patches together write each one once. */
    @Test
    fun captionBridgesWrittenFirstAreLeftAsTheyAre() {
        val context = PatchContexts.of(standIns())
        val first = context.findCaptionWordReels()
        val second = context.findCaptionWordReels()
        first()
        val written = listOf(context.body(INSTAGRAM_MEDIA, "caption"), context.body(INSTAGRAM_MEDIA, "captionText"))
        second()

        assertEquals(written, listOf(context.body(INSTAGRAM_MEDIA, "caption"), context.body(INSTAGRAM_MEDIA, "captionText")))
        for (bridge in written) assertEquals(bridge.toString(), 1, bridge.count { it.startsWith("check-cast") })
    }

    /** A build the finder can't read exactly fails at patch time, before anything is written. */
    @Test
    fun anythingItCantFindExactlyFailsThePatch() {
        val cases = listOf(
            standIns(extension = false) to "the extension has no $CAPTION_WORD_REELS",
            standIns(captionGetters = 0) to "can't read a post's caption in this Instagram build",
            standIns(captionGetters = 2) to "can't read a post's caption in this Instagram build",
            standIns(commentInterface = false) to "can't read a post's caption in this Instagram build",
            standIns(treeImplements = false) to "can't read a post's caption in this Instagram build",
            standIns(declaresText = false) to "can't read a post's caption in this Instagram build",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findCaptionWordReels() }
            assertEquals(expected, "Hide reels by caption words: $expected", failure.message)
        }
    }

    private fun BytecodePatchContext.fillReelsItemMediaForTest(writeCaption: () -> Unit) {
        fillReelsItemMedia(CAPTION_WORD_REELS, page())
        writeCaption()
    }

    private fun BytecodePatchContext.body(type: String, name: String): List<String> =
        mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(Any::toString) == listOf(OBJECT) }
            .code().map { listOfNotNull(it.opcode.name, it.referenceText()).joinToString(" ") }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = when (val reference = (this as? ReferenceInstruction)?.reference) {
        is MethodReference -> "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"
        is TypeReference -> reference.type
        is FieldReference -> "${reference.definingClass}->${reference.name}:${reference.type}"
        else -> null
    }

    private companion object {
        const val COMMENT = "Lfixture/Comment;"
        const val TREE = "Lfixture/CommentTree;"
        const val ITEM = "Lfixture/ClipsItem;"
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"
        val PUBLIC = AccessFlags.PUBLIC.value
        val ABSTRACT = AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value

        /** The page's sites as far as the stub reads them: the item type and its post field. */
        fun page() = ReelsPageSites("Lfixture/Callback;", "success", "Lfixture/Source;", 0, 0, 0, "module", ITEM, "post")

        /**
         * Stand-ins shaped like 451's: Media with one getter loading caption's hash, the comment
         * interface it answers, and the tree-backed comment whose text getter loads text's hash.
         */
        fun standIns(
            captionGetters: Int = 1,
            commentInterface: Boolean = true,
            treeImplements: Boolean = true,
            declaresText: Boolean = true,
            extension: Boolean = true,
        ): List<ClassDef> {
            val getters = (1..captionGetters).map { i ->
                method(
                    MEDIA, if (i == 1) "cap" else "cap$i", COMMENT, PUBLIC or AccessFlags.FINAL.value, 2,
                    "const v0, ${"caption".hashCode()}\nconst/4 v0, 0x0\nreturn-object v0",
                )
            }
            val media = ImmutableClassDef(MEDIA, PUBLIC, OBJECT, null, null, null, null, getters)
            val comment = if (commentInterface) {
                ImmutableClassDef(
                    COMMENT, ABSTRACT or AccessFlags.INTERFACE.value, OBJECT, null, null, null, null,
                    if (declaresText) listOf(ImmutableMethod(COMMENT, "words", emptyList(), STRING, ABSTRACT, null, null, null)) else emptyList(),
                )
            } else {
                ImmutableClassDef(COMMENT, PUBLIC, OBJECT, null, null, null, null, emptyList())
            }
            val tree = ImmutableClassDef(
                TREE, PUBLIC or AccessFlags.FINAL.value, "Lfixture/TreeModel;", if (treeImplements) listOf(COMMENT) else null, null, null, null,
                listOf(method(TREE, "words", STRING, PUBLIC, 2, "const v0, ${"text".hashCode()}\nconst/4 v0, 0x0\nreturn-object v0")),
            )
            return listOfNotNull(
                media, comment, tree, ExtensionDex.classDef(INSTAGRAM_MEDIA),
                if (extension) ExtensionDex.classDef(CAPTION_WORD_REELS) else null,
            )
        }

        fun method(type: String, name: String, returns: String, flags: Int, registers: Int, body: String): Method =
            ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, emptyList<ImmutableMethodParameter>(), returns, flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body) },
            )
    }
}
