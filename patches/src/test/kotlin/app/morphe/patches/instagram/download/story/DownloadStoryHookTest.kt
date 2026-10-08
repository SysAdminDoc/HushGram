/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.story

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import app.morphe.patches.instagram.download.PANDO_VIDEO_VERSION
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.download.VIDEO_VERSION
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoryHookTest {
    private val helper = "Lfixture/StoryMenu;"
    private val other = "Lfixture/OtherMenu;"
    private val dismiss = "Landroid/content/DialogInterface\$OnDismissListener;"
    private val labels = "[Ljava/lang/CharSequence;"
    private val label = "Ljava/lang/CharSequence;"
    private val listener = "Lfixture/OldDialogClick;"
    private val clickListener = "Landroid/content/DialogInterface\$OnClickListener;"

    /** The hooks the patch writes are in the extension the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(LABELS, SAVE_STORY, BUILDING)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * Each builder's labels go through labels() where it returns, the jump that lands on the return
     * included, and one returning from a register above v15 uses the range form. A class of another
     * name with the same shape stays Instagram's.
     */
    @Test
    fun theBuildersPassTheirLabelsThrough() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryStory()

        val code = context.method(helper, "A0o").code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("the returns", 2, returns.size)
        returns.forEach { at ->
            assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), code.subList(at - 2, at + 1).map { it.opcode })
            assertEquals(LABELS, code[at - 2].referenceText())
            val register = (code[at] as OneRegisterInstruction).registerA
            assertEquals("labels()'s argument", register, (code[at - 2] as Instruction35c).registerC)
            assertEquals("labels()'s answer", register, (code[at - 1] as OneRegisterInstruction).registerA)
        }
        assertEquals("the jump to the last return", returns.last() - 2, code.target(code.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        val high = context.method(helper, "A0m").code()
        val call = high[high.size - 3]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(LABELS, call.referenceText())
        assertEquals("the range", 20, (call as RegisterRangeInstruction).startRegister)
        assertEquals(20, (high.last() as OneRegisterInstruction).registerA)

        assertTrue("another class's builder changed", context.method(other, "A0o").code().none { it.referenceText() == LABELS })
    }

    /**
     * Each builder's first instruction hands building() the menu it was given, before the body can
     * reuse that register: from a parameter above v15 through the range form. Another class's
     * builder of the same shape stays Instagram's.
     */
    @Test
    fun eachBuilderNamesItsMenuFirst() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryStory()

        val low = context.method(helper, "A0o").code()
        assertEquals(BUILDING, low[0].referenceText())
        assertEquals("the menu, p0 of four registers", 3, (low[0] as Instruction35c).registerC)
        assertEquals("the original code moved", "$helper->story:$REEL_ITEM", low[1].referenceText())

        val high = context.method(helper, "A0m").code()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, high[0].opcode)
        assertEquals(BUILDING, high[0].referenceText())
        assertEquals("the menu, the second of three parameters in 24 registers", 22, (high[0] as RegisterRangeInstruction).startRegister)

        assertTrue("another class's builder changed", context.method(other, "A0o").code().none { it.referenceText() == BUILDING })
        assertEquals("a handler names its menu", 0, context.method(helper, "A0I").code().count { it.referenceText() == BUILDING })
    }

    /** A tap asks save() first, with the tapped label and the menu's class; any other label goes on. */
    @Test
    fun theHandlersAskSaveFirst() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryStory()

        for ((name, registers) in listOf("A0I" to (23 to 17), "A0P" to (9 to 8))) {
            val code = context.method(helper, name).code()
            assertEquals(
                name,
                listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                code.take(6).map { it.opcode },
            )
            assertEquals("$name: the label", registers.first, (code[0] as TwoRegisterInstruction).registerB)
            assertEquals("$name: the menu", registers.second, (code[1] as TwoRegisterInstruction).registerB)
            assertEquals(SAVE_STORY, code[2].referenceText())
            assertEquals("$name: the original code moved", Opcode.CONST_STRING, code[6].opcode)
            assertEquals("$name: the branch", 6, code.target(4))
        }
        assertEquals("a method taking no label changed", Opcode.RETURN_VOID, context.method(helper, "A0J").code().first().opcode)
    }

    /**
     * An old dialog's click listener, which looks the tapped label up in the labels itself, asks
     * save() right after the lookup with the label and the menu, and its answer takes the array's
     * register, which the listener sets anew next. A yes ends the click.
     */
    @Test
    fun theOldDialogsAskSaveAfterTheLookup() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryStory()

        val code = context.method(listener, "onClick").code()
        val lookup = code.indexOfFirst { it.opcode == Opcode.AGET_OBJECT }
        assertEquals(
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.IGET_OBJECT),
            code.subList(lookup + 1, lookup + 6).map { it.opcode },
        )
        val save = code[lookup + 1] as Instruction35c
        assertEquals(SAVE_STORY, save.referenceText())
        assertEquals("the label and the menu", listOf(2, 4), listOf(save.registerC, save.registerD))
        assertEquals("the answer", 0, (code[lookup + 2] as OneRegisterInstruction).registerA)
        assertEquals("the branch", lookup + 5, code.target(lookup + 3))
    }

    /** A listener that reads the array again after the lookup leaves no register to use, and nothing changes. */
    @Test
    fun aLookupWhoseArrayIsReadAgainFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(arrayReadAgain = true))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryStory() }
        assertUntouched(context)
        assertTrue("the listener changed", context.method(listener, "onClick").code().none { it.referenceText() == SAVE_STORY })
    }

    /** A jump onto the lookup reaches it without the builder's call, so the menu isn't known there, and nothing changes. */
    @Test
    fun aJumpOntoTheLookupFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(jumpToLookup = true))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryStory() }
        assertTrue(failure.message!!, failure.message!!.contains("a jump lands between"))
        assertUntouched(context)
        assertTrue("the listener changed", context.method(listener, "onClick").code().none { it.referenceText() == SAVE_STORY })
    }

    /** storyMedia() reads the menu's story, then the story's Media the builders read. */
    @Test
    fun theStoryBridgeReadsTheMenusMedia() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryStory()

        val code = context.method(INSTAGRAM_MEDIA, "storyMedia").code()
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT),
            code.take(5).map { it.opcode },
        )
        assertEquals(helper, code[0].referenceText())
        assertEquals("$helper->story:$REEL_ITEM", code[1].referenceText())
        assertEquals("$REEL_ITEM->A16:$MEDIA", code[3].referenceText())
        assertEquals("a null story skips the Media", 4, code.target(2))
    }

    /** Each picture bridge casts its argument and calls the getter that reads its field. */
    @Test
    fun thePictureBridgesCallTheGetters() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryStory()

        val expected = mapOf(
            "imageVersions" to ("$MEDIA->A3F()$IMAGE_INFO" to Opcode.MOVE_RESULT_OBJECT),
            "imageCandidates" to ("$IMAGE_INFO->Bd1()Ljava/util/List;" to Opcode.MOVE_RESULT_OBJECT),
            "candidateUrl" to ("$IMAGE_URL->getUrl()Ljava/lang/String;" to Opcode.MOVE_RESULT_OBJECT),
            "candidateWidth" to ("$IMAGE_URL->getWidth()I" to Opcode.MOVE_RESULT),
            "candidateHeight" to ("$IMAGE_URL->getHeight()I" to Opcode.MOVE_RESULT),
            "storyImageWithMusic" to ("$MEDIA->A5d()Ljava/lang/Boolean;" to Opcode.MOVE_RESULT_OBJECT),
        )
        expected.forEach { (bridge, call) ->
            val code = context.method(INSTAGRAM_MEDIA, bridge).code()
            assertEquals(bridge, Opcode.CHECK_CAST, code[0].opcode)
            assertEquals(bridge, call.first, code[1].referenceText())
            assertEquals(bridge, call.second, code[2].opcode)
        }
        assertEquals("the video bridges", Opcode.CHECK_CAST, context.method(INSTAGRAM_MEDIA, "videoVersions").code().first().opcode)
    }

    /** With Download any reel's bridges written first, the story's are written once and the video's aren't written again. */
    @Test
    fun bridgesWrittenBeforeStayAsTheyAre() {
        val context = PatchContexts.of(classes())
        context.mediaBridges("Download any reel")()
        val written = context.method(INSTAGRAM_MEDIA, "videoVersions").code().size

        context.offerDownloadOnEveryStory()

        assertEquals("the video bridge was written again", written, context.method(INSTAGRAM_MEDIA, "videoVersions").code().size)
    }

    @Test
    fun aMissingMenuClassFailsThePatch() {
        val context = PatchContexts.of(classes(name = "SomethingElse"))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryStory() }
        assertTrue(failure.message!!, failure.message!!.contains(HELPER_NAME))
    }

    @Test
    fun aSecondMediaReadFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(secondMedia = true))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryStory() }
        assertUntouched(context)
    }

    @Test
    fun aMissingPictureGetterFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(leaveOutCandidates = true))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryStory() }
        assertTrue(failure.message!!, failure.message!!.contains("candidates"))
        assertUntouched(context)
    }

    @Test
    fun aMissingMusicGetterFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(leaveOutMusic = true))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryStory() }
        assertTrue(failure.message!!, failure.message!!.contains("is_story_image_with_music"))
        assertUntouched(context)
    }

    @Test
    fun aHandlerWithoutLocalsFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(handlerLocals = 1))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryStory() }
        assertUntouched(context)
    }

    /**
     * In each declared build, the story menu's class is found by its kept name, every builder's
     * returns pass the labels through labels() and no jump skips that, every handler asks save()
     * first, and every bridge is written.
     */
    @Test
    fun eachDeclaredBuildOffersDownloadOnEveryStory() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val types = setOf(MEDIA, USER, VIDEO_VERSION, PANDO_VIDEO_VERSION, IMAGE_INFO, PANDO_IMAGE_INFO, IMAGE_URL)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>(ExtensionDex.classDef(INSTAGRAM_MEDIA))
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type in types || classDef.isStoryMenu() || classDef.looksLabelsUp()) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(classes)

                context.offerDownloadOnEveryStory()

                val menu = classes.single { it.isStoryMenu() }
                val statics = menu.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
                val builders = statics.filter { it.returnType == labels && menu.type in it.parameterTypes.map(Any::toString) }
                assertTrue("${bundle.name}: no builder", builders.isNotEmpty())
                builders.forEach { builder ->
                    val code = context.method(menu.type, builder.name, builder.parameterTypes.map(Any::toString)).code()
                    val parameters = builder.parameterTypes.map(Any::toString)
                    val words = { types: List<String> -> types.sumOf { if (it == "J" || it == "D") 2 else 1 } }
                    val given = builder.implementation!!.registerCount - words(parameters) + words(parameters.take(parameters.indexOf(menu.type)))
                    assertEquals("${bundle.name}: ${builder.name} names its menu first", BUILDING, code[0].referenceText())
                    val named = (code[0] as? RegisterRangeInstruction)?.startRegister ?: (code[0] as Instruction35c).registerC
                    assertEquals("${bundle.name}: ${builder.name}'s menu parameter", given, named)
                    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
                    assertTrue("${bundle.name}: ${builder.name} doesn't return", returns.isNotEmpty())
                    returns.forEach { at ->
                        assertEquals("${bundle.name}: ${builder.name} before the return at $at", LABELS, code[at - 2].referenceText())
                    }
                    // Opcode.name is the smali name, "if-eqz" or "goto/16".
                    code.indices.filter { index -> code[index] is OffsetInstruction && code[index].opcode.name.let { it.startsWith("if-") || it.startsWith("goto") } }
                        .forEach { jump ->
                            val target = code.target(jump)
                            assertTrue("${bundle.name}: the jump at $jump in ${builder.name} skips labels()",
                                target !in returns && target !in returns.map { it - 1 })
                        }
                }
                val handlers = statics.filter { it.returnType == "V" && it.parameterTypes.lastOrNull()?.toString() == label && menu.type in it.parameterTypes.map(Any::toString) }
                assertTrue("${bundle.name}: fewer handlers than builders", handlers.size >= builders.size)
                handlers.forEach { handler ->
                    val code = context.method(menu.type, handler.name, handler.parameterTypes.map(Any::toString)).code()
                    assertEquals("${bundle.name}: ${handler.name}'s first call", SAVE_STORY, code[2].referenceText())
                }
                val lookups = classes.filter { it.looksLabelsUp() }.flatMap { listenerClass ->
                    val code = context.method(listenerClass.type, "onClick", listOf("Landroid/content/DialogInterface;", "I")).code()
                    code.indices.filter { index ->
                        code[index].opcode == Opcode.INVOKE_STATIC && code[index].referenceText()?.startsWith("${menu.type}->") == true &&
                            code[index].referenceText()!!.endsWith(")$labels")
                    }.map { call -> listenerClass.type to code[call + 3].referenceText() }
                }
                assertEquals("${bundle.name}: the old dialogs' lookups", 2, lookups.size)
                lookups.forEach { (owner, first) -> assertEquals("${bundle.name}: $owner after the lookup", SAVE_STORY, first) }
                val bridges = context.classDefBy(INSTAGRAM_MEDIA).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.name in storyBridges }
                bridges.forEach { assertEquals("${bundle.name}: ${it.name}", Opcode.CHECK_CAST, it.code().first().opcode) }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The bridges this patch writes; the feed menu's two belong to Download any video. */
    private val storyBridges = setOf(
        "videoVersions", "dashManifest", "mediaId", "owner", "takenAt", "username", "versionUrl", "versionWidth", "versionHeight",
        "imageVersions", "imageCandidates", "candidateUrl", "candidateWidth", "candidateHeight", "storyMedia", "storyImageWithMusic",
    )

    /** A dialog click listener that calls a static method answering labels. */
    private fun ClassDef.looksLabelsUp() = clickListener in interfaces && methods.any { method ->
        method.name == "onClick" && method.code().any { it.opcode == Opcode.INVOKE_STATIC && it.referenceText()?.endsWith(")$labels") == true }
    }

    private fun ClassDef.isStoryMenu() = fields.any {
        it.name == "__redex_internal_original_name" && (it.initialValue as? StringEncodedValue)?.value == HELPER_NAME
    }

    private fun assertUntouched(context: BytecodePatchContext) {
        assertTrue("a builder changed", context.method(helper, "A0o").code().none { it.referenceText() == LABELS || it.referenceText() == BUILDING })
        assertEquals("a handler changed", Opcode.CONST_STRING, context.method(helper, "A0I").code().first().opcode)
        assertEquals("the story bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "storyMedia").code().first().opcode)
        assertEquals("a bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "videoVersions").code().first().opcode)
        assertEquals("the music bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "storyImageWithMusic").code().first().opcode)
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.single { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The index [branch] at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private fun classes(
        name: String = HELPER_NAME,
        secondMedia: Boolean = false,
        leaveOutCandidates: Boolean = false,
        handlerLocals: Int = 11,
        leaveOutMusic: Boolean = false,
        arrayReadAgain: Boolean = false,
        jumpToLookup: Boolean = false,
    ): List<ClassDef> {
        // An old dialog's click listener: the tapped label is the builder's labels at `which`.
        val oldDialog = ImmutableClassDef(
            listener, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", listOf(clickListener), null, null,
            listOf(ImmutableField(listener, "menu", "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null, null)),
            listOf(
                method(listener, "onClick", listOf("Landroid/content/DialogInterface;", "I"), "V", 8, static = false, body = """
                    iget-object v4, p0, $listener->menu:Ljava/lang/Object;
                    ${if (jumpToLookup) "if-eqz v4, :lookup" else ""}
                    check-cast v4, $helper
                    invoke-static { v4 }, $helper->A0o($helper)$labels
                    move-result-object v0
                    :lookup
                    aget-object v2, v0, p2
                    ${if (arrayReadAgain) "array-length v0, v0" else "iget-object v0, v4, $helper->other:$label"}
                    return-void
                """),
            ),
        )
        val builder = { owner: String ->
            method(owner, "A0o", listOf(owner), labels, 4, static = true, body = """
                iget-object v0, p0, $owner->story:$REEL_ITEM
                iget-object v1, v0, $REEL_ITEM->A16:$MEDIA
                ${if (secondMedia) "iget-object v1, v0, $REEL_ITEM->A08:$MEDIA" else ""}
                const/4 v2, 0x0
                new-array v1, v2, $labels
                if-eqz v2, :done
                const/4 v2, 0x1
                new-array v1, v2, $labels
                return-object v1
                :done
                return-object v1
            """)
        }
        val menu = ImmutableClassDef(
            helper, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOf(
                ImmutableField(helper, "__redex_internal_original_name", "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, ImmutableStringEncodedValue(name), null, null),
                ImmutableField(helper, "story", REEL_ITEM, AccessFlags.PUBLIC.value, null, null, null),
            ),
            listOf(
                builder(helper),
                // A builder whose labels end up above v15.
                method(helper, "A0m", listOf("Lfixture/Kind;", helper, "Lfixture/Logger;"), labels, 24, static = true, body = """
                    const/4 v0, 0x0
                    new-array v20, v0, $labels
                    return-object v20
                """),
                // The new sheet's handler: the menu seventh of thirteen, the label last.
                method(helper, "A0I", listOf(dismiss) + List(5) { "Lfixture/Arg$it;" } + listOf(helper) + List(5) { "Lfixture/More$it;" } + listOf(label),
                    "V", 13 + handlerLocals, static = true, body = """
                    const-string v0, "ReelOptionsDialog"
                    return-void
                """),
                method(helper, "A0P", listOf("Lfixture/Sheet;", helper, label), "V", 10, static = true, body = """
                    const-string v0, "about"
                    return-void
                """),
                // Takes the menu, but no label: not a handler.
                method(helper, "A0J", listOf(dismiss, helper), "V", 2, static = true, body = "return-void"),
            ),
        )
        val otherMenu = classDef(other, listOf(builder(other)), listOf(ImmutableField(other, "story", REEL_ITEM, AccessFlags.PUBLIC.value, null, null, null)))
        val mediaGetters = listOf(
            "AAh" to ("video_versions" to "Ljava/util/List;"),
            "A8P" to ("video_dash_manifest" to "Ljava/lang/String;"),
            "A3Q" to ("user" to USER),
            "A6v" to ("taken_at" to "Ljava/lang/Long;"),
            "A3F" to ("image_versions2" to IMAGE_INFO),
        ).plus(if (leaveOutMusic) emptyList() else listOf("A5d" to ("is_story_image_with_music" to "Ljava/lang/Boolean;")))
            .map { (name, field) -> getter(MEDIA, name, field.first, field.second) } +
            method(MEDIA, "getId", emptyList(), "Ljava/lang/String;", 1, static = false, body = """
                const/4 v0, 0x0
                return-object v0
            """)
        val versionGetters = listOf("getUrl" to ("url" to "Ljava/lang/String;"), "DvO" to ("width" to "Ljava/lang/Integer;"),
            "CK7" to ("height" to "Ljava/lang/Integer;"))
        val imageGetters = if (leaveOutCandidates) emptyList() else listOf("Bd1" to ("candidates" to "Ljava/util/List;"))
        return listOf(
            menu, otherMenu, oldDialog,
            classDef(MEDIA, mediaGetters),
            classDef(USER, listOf(getter(USER, "A89", "username", "Ljava/lang/String;"))),
            anInterface(VIDEO_VERSION, versionGetters.map { it.first to it.second.second }),
            classDef(PANDO_VIDEO_VERSION, versionGetters.map { (name, field) -> getter(PANDO_VIDEO_VERSION, name, field.first, field.second) }),
            anInterface(IMAGE_INFO, imageGetters.map { it.first to it.second.second }),
            classDef(PANDO_IMAGE_INFO, imageGetters.map { (name, field) -> getter(PANDO_IMAGE_INFO, name, field.first, field.second) }),
            anInterface(IMAGE_URL, listOf("getUrl" to "Ljava/lang/String;", "getWidth" to "I", "getHeight" to "I")),
            ExtensionDex.classDef(INSTAGRAM_MEDIA),
        )
    }

    private fun anInterface(type: String, methods: List<Pair<String, String>>): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
        "Ljava/lang/Object;", null, null, null, null,
        methods.map { (name, returns) ->
            ImmutableMethod(type, name, emptyList(), returns, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
        },
    )

    private fun getter(owner: String, name: String, field: String, returns: String) =
        method(owner, name, emptyList(), returns, 2, static = false, body = """
            const v0, ${field.hashCode()}
            const/4 v0, 0x0
            return-object v0
        """)

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)
}
