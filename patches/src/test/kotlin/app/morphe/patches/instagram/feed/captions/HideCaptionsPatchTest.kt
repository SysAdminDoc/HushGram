/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.captions

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide captions switches the status method on, hooks the feed caption's render so a yes draws
 * nothing (#395), hooks each reel caption render the same way so no room is left under the username
 * (#680), and the extension finds a caption host by the id Instagram's layout gives it.
 */
class HideCaptionsPatchTest {
    private val views = listOf("media_caption_component", "clips_caption_component")

    /** The extension the bundle ships has the install the application hook calls. */
    @Test
    fun theExtensionHasTheInstall() {
        val install = ExtensionDex.classDef("$EXTENSION_PACKAGE/feed/HideCaptions;").methods.single { it.name == "install" }
        assertTrue(AccessFlags.PUBLIC.isSet(install.accessFlags) && AccessFlags.STATIC.isSet(install.accessFlags))
    }

    /** The hook the patch writes is in the HideCaptions the bundle ships, public and static. */
    @Test
    fun theFeedHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HIDE_CAPTIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDE_FEED_CAPTION is not in the extension: $declared", HIDE_FEED_CAPTION.substringAfter("->") in declared)
    }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes())
        hideCaptionsPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, HIDE_CAPTIONS_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideComments").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test
    fun theRenderIsFound() {
        val found = PatchContexts.of(classes()).findFeedCaption()

        assertEquals(CAPTION_TYPE, found.type)
        assertEquals("A0o", found.name)
        assertEquals(SCOPE, found.parameter)
        assertEquals(COMPONENT, found.returnType)
    }

    /**
     * The hook asks first and returns null on a yes; a no goes on to the render as it was. The
     * preview comment row, tagged the same way, is left alone.
     */
    @Test
    fun theHookReturnsNullBeforeTheRender() {
        val context = PatchContexts.of(classes())
        val found = context.findFeedCaption()
        val before = context.render(found).size
        val comment = context.classDefBy(COMMENT_TYPE).methods.single().code()

        found.write(context)

        val code = context.render(found)
        assertEquals("five instructions in front", before + 5, code.size)
        assertHooked("stand-in", code)
        assertEquals("the render's own check is first after it", Opcode.IGET_OBJECT, code[5].opcode)
        assertEquals("the comment row is unchanged", comment.map { it.opcode },
            context.mutableClassDefBy(COMMENT_TYPE).methods.single().code().map { it.opcode })
    }

    /** Both looks of the reel caption are found, each the render the feed caption overrides (#680). */
    @Test
    fun theReelRendersAreFound() {
        val context = PatchContexts.of(classes())
        val found = context.findReelCaptions(context.findFeedCaption())

        assertEquals(setOf(REEL_TYPE, OTHER_REEL_TYPE), found.map { it.type }.toSet())
        assertEquals("each found once", 2, found.size)
        found.forEach { render ->
            assertEquals("A0o", render.name)
            assertEquals(SCOPE, render.parameter)
            assertEquals(COMPONENT, render.returnType)
            assertEquals(HIDE_REEL_CAPTION, render.hook)
        }
    }

    /** The whole patch asks the reel question at the start of each reel render and the feed one at the feed's. */
    @Test
    fun eachReelRenderAsksBeforeItDraws() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes())
        val before = context.classDefBy(REEL_TYPE).methods.single().code().size

        hideCaptionsPatch.execute(context)

        for (type in listOf(REEL_TYPE, OTHER_REEL_TYPE)) {
            val code = context.mutableClassDefBy(type).methods.single().code()
            assertHooked(type, code, HIDE_REEL_CAPTION)
        }
        assertEquals("five instructions in front", before + 5, context.mutableClassDefBy(REEL_TYPE).methods.single().code().size)
        assertHooked("feed", context.mutableClassDefBy(CAPTION_TYPE).methods.single().code())
    }

    @Test
    fun noReelRenderFailsThePatch() = refusedReel("found 0", classes(reels = 0))

    @Test
    fun aReelRenderOnAnotherParentFailsThePatch() = refusedReel("isn't the render", classes(reelParent = OBJECT))

    @Test
    fun aStaticReelRenderFailsThePatch() = refusedReel("isn't the render", classes(reelStatic = true))

    @Test
    fun aReelRenderOfAnotherShapeFailsThePatch() = refusedReel("isn't the render", classes(reelParameters = listOf(SCOPE, SCOPE)))

    @Test
    fun noRenderFailsThePatch() = refused("found 0", classes(leaveOut = true))

    @Test
    fun twoRendersFailThePatch() = refused("found 2", classes(second = true))

    @Test
    fun aStaticLoaderFailsThePatch() = refused("isn't a render", classes(static = true))

    @Test
    fun aLoaderTakingTwoParametersFailsThePatch() = refused("isn't a render", classes(parameters = listOf(SCOPE, SCOPE)))

    @Test
    fun aRenderThatNeverReturnsNullFailsThePatch() = refused("never returns null", classes(variant = "nonull"))

    /** On each declared build the patch applies, and the build declares the ids the extension looks for. */
    @Test
    fun eachDeclaredBuildHidesItsCaptions() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hidesCaptions(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHidesItsCaptionsToo() {
        var checked = 0
        for (base in Fixtures.otherBuilds()) {
            hidesCaptions(base, base.parentFile.name)
            checked++
        }
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, checked)
    }

    /**
     * Slices the fixture the way the finders read it, every class loading the feed caption's tag or
     * the reel caption's key with the string pools they call, and runs the whole patch over it: the
     * feed caption's render is hooked once, the other renders holding the tag (the preview comments)
     * aren't, and each reel caption render asks the reel question (#680).
     */
    private fun hidesCaptions(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(),
            ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), views))
        val holders = FixtureDex.classesHolding(bundle, FEED_CAPTION_TAG)
        assertTrue("$label: no other render holds the tag", holders.size >= 2)
        val reelHolders = FixtureDex.classesLoading(bundle, REEL_CAPTION_EXPANDED)
        val sliced = (holders + reelHolders).distinctBy { it.type }
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + FixtureDex.withStringPools(bundle, sliced))
        val found = context.findFeedCaption()
        val reels = context.findReelCaptions(found)
        assertTrue("$label: no reel caption render", reels.isNotEmpty())

        hideCaptionsPatch.execute(context)

        val status = context.method(SETTINGS_STATUS, HIDE_CAPTIONS_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        assertHooked("$label ${found.type}->${found.name}", context.render(found))
        reels.forEach { assertHooked("$label ${it.type}->${it.name}", context.render(it), HIDE_REEL_CAPTION) }
        fun hookedWith(hook: String) = sliced.flatMap { holder ->
            context.mutableClassDefBy(holder.type).methods.filter { method ->
                method.code().any { it.reference()?.toString() == hook }
            }.map { "${it.definingClass}->${it.name}" }
        }
        assertEquals("$label: only the caption is hooked", listOf("${found.type}->${found.name}"), hookedWith(HIDE_FEED_CAPTION))
        assertEquals("$label: only the reel captions ask the reel question", reels.map { "${it.type}->${it.name}" }.sorted(),
            hookedWith(HIDE_REEL_CAPTION).sorted())
    }

    /** invoke the hook, its answer in v0, a no jumps past a null return to what was the render's first instruction. */
    private fun assertHooked(what: String, code: List<Instruction>, hook: String = HIDE_FEED_CAPTION) {
        val calls = code.indices.filter { code[it].reference()?.toString() == hook }
        assertEquals("$what: one hook, at the start", listOf(0), calls)
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$what: with nothing", 0, (code[0] as FiveRegisterInstruction).registerCount)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: in v0", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: on the answer", 0, (code[2] as OneRegisterInstruction).registerA)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("$what: a no draws as before", 5, addresses.indexOf(addresses[2] + (code[2] as OffsetInstruction).codeOffset))
        assertEquals("$what: a yes loads null", Opcode.CONST_4, code[3].opcode)
        assertEquals("$what: zero", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: and returns it", Opcode.RETURN_OBJECT, code[4].opcode)
        assertEquals("$what: the same register", 0, (code[4] as OneRegisterInstruction).registerA)
    }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findFeedCaption() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    /** The whole patch stops on a reel caption it hasn't seen, before it changes anything. */
    private fun refusedReel(why: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes)
        val failure = assertThrows(PatchException::class.java) { hideCaptionsPatch.execute(context) }
        assertTrue(failure.message!!, failure.message!!.contains("the reel caption: ") && failure.message!!.contains(why))
        val feed = context.mutableClassDefBy(CAPTION_TYPE).methods.single().code()
        assertTrue("the feed caption is left as it was", feed.none { it.reference()?.toString() == HIDE_FEED_CAPTION })
    }

    private fun BytecodePatchContext.render(caption: CaptionRender): List<Instruction> =
        mutableClassDefBy(caption.type).methods.single {
            it.name == caption.name && it.parameterTypes.map(CharSequence::toString) == listOf(caption.parameter)
        }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val CAPTION_TYPE = "Lfixture/Caption;"
        const val COMMENT_TYPE = "Lfixture/CommentRow;"
        const val SCOPE = "Lfixture/Scope;"
        const val COMPONENT = "Lfixture/Component;"
        const val REEL_TYPE = "Lfixture/ReelCaption;"
        const val OTHER_REEL_TYPE = "Lfixture/OtherReelCaption;"

        /** The parent the caption renders override, like 456's LX/00hO. */
        const val PARENT = "Lfixture/KComponent;"

        /**
         * Shaped like 453's LX/018d->A0o: null when the post has no data, else the text tagged with
         * the caption's tag and its animation named. "nonull" returns the scope it was given
         * instead. "comment" is LX/08FS->A0o's shape, the tag and no animation.
         */
        fun smali(owner: String, variant: String): String {
            if (variant == "reel") {
                return """
                    const-string v0, "$REEL_CAPTION_EXPANDED"
                    invoke-static {v0}, Lfixture/Modifier;->tag(Ljava/lang/String;)$COMPONENT
                    move-result-object v0
                    return-object v0
                """.trimIndent()
            }
            val early = if (variant == "nonull") "return-object p1" else "return-object v1"
            val animation = if (variant == "comment") "" else "const-string v2, \"$CAPTION_TRANSITION\""
            return """
                iget-object v0, p0, $owner->data:$OBJECT
                const/4 v1, 0x0
                if-nez v0, :draw
                $early
                :draw
                $animation
                const-string v0, "$FEED_CAPTION_TAG"
                invoke-static {v0}, Lfixture/Modifier;->tag(Ljava/lang/String;)$COMPONENT
                move-result-object v0
                return-object v0
            """.trimIndent()
        }

        fun render(owner: String, variant: String, static: Boolean, parameters: List<String>): Method {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            val method = MutableMethod(
                ImmutableMethod(
                    owner, "A0o", parameters.map { ImmutableMethodParameter(it, null, null) }, COMPONENT, flags, null, null,
                    ImmutableMethodImplementation(5, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali(owner, variant))
            return ImmutableMethod.of(method)
        }

        fun classes(
            leaveOut: Boolean = false,
            second: Boolean = false,
            static: Boolean = false,
            parameters: List<String> = listOf(SCOPE),
            variant: String = "ok",
            reels: Int = 2,
            reelParent: String = PARENT,
            reelStatic: Boolean = false,
            reelParameters: List<String> = listOf(SCOPE),
        ): List<ClassDef> {
            fun component(type: String, method: Method, parent: String = PARENT) = ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, parent, null, null, null, null, listOf(method),
            )
            val caption = if (leaveOut) emptyList() else listOf(component(CAPTION_TYPE, render(CAPTION_TYPE, variant, static, parameters)))
            val other = if (second) listOf(component("Lfixture/OtherCaption;", render("Lfixture/OtherCaption;", "ok", false, listOf(SCOPE)))) else emptyList()
            val comment = component(COMMENT_TYPE, render(COMMENT_TYPE, "comment", false, listOf(SCOPE)))
            // The first reel look takes the odd shapes, the second stays as 456 has it.
            val reel = listOf(REEL_TYPE, OTHER_REEL_TYPE).take(reels).mapIndexed { index, type ->
                if (index == 0) component(type, render(type, "reel", reelStatic, reelParameters), reelParent)
                else component(type, render(type, "reel", false, listOf(SCOPE)))
            }
            return caption + other + comment + reel +
                ImmutableClassDef("Lfixture/Unrelated;", AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, emptyList())
        }
    }
}
