/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanUpReelsHookTest {
    private val scope = "Lfixture/ComponentScope;"
    private val component = "Lfixture/Component;"

    /** The hooks the patch writes are in the ReelDeclutter the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(
            HIDE_FOLLOW_BUTTON, HIDE_CHIPS, HIDE_SOCIAL_FOOTER, HIDE_SOCIAL_CONTEXT, HIDE_COMMENT_BAR, HIDE_EVERY_COMMENT_BAR,
            HIDE_SAVE_BUTTON, HIDE_SAVE_COUNT, HIDE_DIRECT_REPLY_BAR,
        )) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Each part's method asks its hook first; the Legacy Follow button of the suggested accounts' cards stays. */
    @Test
    fun eachPartAsksItsHookFirst() {
        val context = PatchContexts.of(classes())

        context.hideReelParts()

        REEL_PARTS.forEach { part ->
            val method = context.mutableClassDefBy(owner(part.marker)).methods.single()
            assertGuardFirst(part.marker, part, method)
        }
        val ufi = context.mutableClassDefBy(UFI).methods
        assertEquals(setOf("A0D", "A06"), ufi.map { it.name }.toSet())
        SAVE_PARTS.zip(listOf("A0D", "A06")).forEach { (part, name) -> assertGuardFirst(part.marker, part, ufi.single { it.name == name }) }
        assertGuardFirst(PRODUCT_CTA.marker, PRODUCT_CTA, context.mutableClassDefBy(owner(PRODUCT_CTA.marker)).methods.single())
        val legacy = context.mutableClassDefBy(owner("ClipsFollowButtonComponentLegacy_render")).methods.single()
        assertEquals("the Legacy Follow button was touched", 3, legacy.instructions().size)
        assertBubblesGuarded("stand-in", context.mutableClassDefBy(BUBBLES).methods.single(), "$NO_BUBBLES->A00:$NO_BUBBLES")
        assertLineGuarded("stand-in", context.mutableClassDefBy(VIEW_UTIL).methods.single(), 4, "$LINE->type:$LINE_TYPE")
        val bar = context.mutableClassDefBy(BAR)
        assertCommentBarGuarded("stand-in", bar.methods.single { it.name == "A0t" }, bar.methods.single { it.name == "onViewCreated" },
            "$BAR->source:$CLIPS_VIEWER_SOURCE", "$BAR->A0r()V", "$BAR->bar:$VIEW", 0)
        val back = context.mutableClassDefBy(UNVANISH).methods.single()
        assertUnVanishGuarded("stand-in", back, 5, 2, "$BAR->source:$CLIPS_VIEWER_SOURCE", "$BAR->A0r()V", 0)
        val show = context.mutableClassDefBy(REPLY_BASE).methods.single { it.name == "A0u" }
        val created = context.mutableClassDefBy(REPLY).methods.single { it.name == "onViewCreated" }
        val before = classes().flatMap { it.methods }
        assertEquals("the show grows by the guard", before.single { DIRECT_REPLY_BAR_SHOW in it.markers() }.instructions().size + 4,
            show.instructions().size)
        assertEquals("onViewCreated grows by the guard", before.single { DIRECT_REPLY_BAR_CREATED in it.markers() }.instructions().size + 4,
            created.instructions().size)
        assertReplyBarGuarded("stand-in", show, created, "$REPLY->hidden:Z", "$REPLY->source:$CLIPS_VIEWER_SOURCE", "A0t")
        // The flag's registers, as the stand-in wrote them: v0 off the controller in v1, and v0 off v4.
        val shown = show.instructions().first { it.opcode == Opcode.IGET_BOOLEAN } as TwoRegisterInstruction
        assertEquals(0 to 1, shown.registerA to shown.registerB)
        val made = created.instructions().last { it.opcode == Opcode.IGET_BOOLEAN } as TwoRegisterInstruction
        assertEquals(0 to 4, made.registerA to made.registerB)
        // Instagram's hide and the Spotlight reply bar's branch of the merged show are as they were.
        assertEquals(2, context.mutableClassDefBy(REPLY_BASE).methods.single { it.name == "A0t" }.instructions().size)
    }

    /**
     * On 451's 385713073 the controller declares the hide itself, over an abstract one of the class it
     * extends, and onViewCreated calls it through that class. The call still runs the hide.
     */
    @Test
    fun aHideTheControllerDeclaresIsCalledThroughTheClassItExtends() {
        val context = PatchContexts.of(classes(replyBar = "hide in the controller"))

        context.hideReelParts()

        val show = context.mutableClassDefBy(REPLY_BASE).methods.single { it.name == "A0u" }
        val created = context.mutableClassDefBy(REPLY).methods.single { it.name == "onViewCreated" }
        assertReplyBarGuarded("hide in the controller", show, created, "$REPLY->hidden:Z", "$REPLY->source:$CLIPS_VIEWER_SOURCE", "A0t")
    }

    /**
     * A reply bar controller without the viewer's source, or whose hidden flag can't be told, or
     * read where a hook right after it would be skipped or lose the controller, fails the patch,
     * and so does a viewer's source that doesn't name a chat.
     */
    @Test
    fun aReplyBarControllerItCantReadFailsThePatch() {
        for (case in listOf(
            "no source", "two flags", "over the controller", "jumped", "read twice", "no hide", "hide on another register",
            "show elsewhere", "no chat source", "hide overridden",
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes(replyBar = case)).hideReelParts() }
            assertTrue("$case: ${failure.message}", failure.message!!.contains("direct reply bar"))
        }
    }

    /** A controller that doesn't keep where its viewer was opened from, or keeps its bar twice, fails the patch. */
    @Test
    fun aCommentBarControllerItCantReadFailsThePatch() {
        for ((case, classes) in listOf(
            "no source" to classes(barSource = false),
            "stored twice" to classes(barStores = 2),
            "this reused" to classes(barThisReused = true),
            "unVanish hiding twice" to classes(unVanish = "twice"),
            "unVanish not reading the controller after its trace" to classes(unVanish = "no read"),
            "unVanish jumping past the hook" to classes(unVanish = "jumped"),
            "unVanish without a trace" to classes(unVanish = "no trace"),
            "unVanish's hide reading what's written after the trace" to classes(unVanish = "rewritten"),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).hideReelParts() }
            assertTrue("$case: ${failure.message}", failure.message!!.contains("comment bar"))
        }
    }

    /**
     * Only a write on the way from the hook to the hide stops the patch. The shown branch writing
     * what the hidden branch answers never reaches the hide, so the hook's jump skips nothing.
     */
    @Test
    fun onlyAWriteOnTheWayToTheHideFailsThePatch() {
        val context = PatchContexts.of(classes(unVanish = "shown rewrites"))
        context.hideReelParts()
        assertUnVanishGuarded("shown rewrites", context.mutableClassDefBy(UNVANISH).methods.single(), 5, 2,
            "$BAR->source:$CLIPS_VIEWER_SOURCE", "$BAR->A0r()V", 0)

        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes(unVanish = "rewritten")).hideReelParts() }
        assertTrue(failure.message!!, failure.message!!.contains("writes v1 between its trace and the hide"))
    }

    @Test
    fun aBubblesUseCaseWithoutItsOwnNoneStateFailsThePatch() {
        val context = PatchContexts.of(classes(noneOfItsOwn = false))
        val failure = assertThrows(PatchException::class.java) { context.hideReelParts() }
        assertTrue(failure.message!!, failure.message!!.contains(FLOATING_BUBBLES))
    }

    /** A check whose type names neither Followed by nor Liked by isn't the social context line's. */
    @Test
    fun aLineTypeNamingNeitherFailsThePatch() {
        val context = PatchContexts.of(classes(typeNames = listOf("FOLLOWER_COUNT")))
        val failure = assertThrows(PatchException::class.java) { context.hideReelParts() }
        assertTrue(failure.message!!, failure.message!!.contains("FOLLOWED_BY"))
    }

    /** The hook reads the line before the check runs, so the check must read the parameter itself. */
    @Test
    fun aCheckReadingAnotherLineFailsThePatch() {
        val context = PatchContexts.of(classes(overwritesLine = true))
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    @Test
    fun aMissingPartFailsThePatch() {
        val context = PatchContexts.of(classes(leaveOut = "ClipsMetaAiPillComponent_render"))
        val failure = assertThrows(PatchException::class.java) { context.hideReelParts() }
        assertTrue(failure.message!!, failure.message!!.contains("ClipsMetaAiPillComponent_render"))
    }

    /** The Save button's builder has to be an instance method of a scope and two longs, and each of the two has to be found. */
    @Test
    fun aSaveBuilderItCantReadFailsThePatch() {
        for ((case, classes) in listOf(
            "static" to classes(saveShape = "static"),
            "other parameters" to classes(saveShape = "parameters"),
            "returning a boolean" to classes(saveShape = "boolean"),
            "no save button" to classes(leaveOut = SAVE_PARTS[0].marker),
            "no save count" to classes(leaveOut = SAVE_PARTS[1].marker),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).hideReelParts() }
            assertTrue("$case: ${failure.message}", failure.message!!.contains("maybeCreateSave"))
        }
    }

    /** One method holding both markers would be guarded twice, so it fails the patch. */
    @Test
    fun aSingleMethodHoldingBothSaveMarkersFailsThePatch() {
        val both = classes(saveShape = "both")
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(both).hideReelParts() }
        assertTrue(failure.message!!, failure.message!!.contains("held by one method"))
    }

    @Test
    fun aMarkerInTwoMethodsFailsThePatch() {
        val copy = render("Lfixture/Copy;", "ClipsFollowButtonComponent_render")
        val context = PatchContexts.of(classes() + copy)
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    @Test
    fun aRenderOfAnotherShapeFailsThePatch() {
        val context = PatchContexts.of(classes(staticRender = "ClipsFriendlyViewerComponent_render"))
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    /** Both kinds of suggestion pill answer to the one switch, the shopping routes to the other (#332). */
    @Test
    fun theRelatedAdsPivotAndTheShopRoutesAnswerToTheirSwitches() {
        assertEquals(
            setOf("ClipsContentDeepDiveComponent_render", "ClipsRelatedAdsPivotComponent_render"),
            REEL_PARTS.filter { it.hook == HIDE_SEARCH_PILL }.map { it.marker }.toSet(),
        )
        assertEquals(HIDE_PRODUCT_PILLS, PRODUCT_CTA.hook)
        assertEquals(HIDE_PRODUCT_PILLS, REEL_PARTS.single { it.marker == "ShopLensCtaComponent_render" }.hook)
    }

    @Test
    fun aProductButtonOfAnotherShapeFailsThePatch() {
        val context = PatchContexts.of(classes(productCtaLabel = "I"))
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    @Test
    fun aCheckThatIsNotABooleanFailsThePatch() {
        val context = PatchContexts.of(classes(checkReturns = "I"))
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    /**
     * In each declared build every part is one method of its shape, and each gets its hook first,
     * and so do the floating bubbles use case and the social context check.
     */
    @Test
    fun eachDeclaredBuildHidesEveryPart() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hidesEveryPart(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The same in the other builds of each declared version, which are compiled on their own and
     * name every class differently. The Reels tab's own comment bar, the in-viewer component's
     * render, is one method of a render's shape in each of them too (#119).
     */
    @Test
    fun eachOtherBuildHidesEveryPart() {
        val builds = Fixtures.otherBuilds()
        for (apk in builds) hidesEveryPart(apk, apk.parentFile.name)
        assertTrue("no other build was read", builds.isNotEmpty())
    }

    private fun hidesEveryPart(bundle: File, label: String) {
        val markers = CLEANUP_MARKERS.toSet()
        val classes = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { string ->
                string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in markers
            }
            if (!holds) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.markers().any { it in markers } }) {
                    classes += ImmutableClassDef.of(classDef)
                }
            }
        }
        val check = classes.flatMap { it.methods }.single { SOCIAL_CONTEXT_CHECK in it.markers() }
        val typeRead = check.instructions().zipWithNext().single { (read, asked) ->
            read.opcode == Opcode.IGET_OBJECT &&
                ((asked as? ReferenceInstruction)?.reference as? MethodReference)?.name == "ordinal"
        }.first
        val typeField = (typeRead as ReferenceInstruction).reference as FieldReference
        classes += FixtureDex.classes(bundle, setOf(typeField.type, CLIPS_VIEWER_SOURCE)).values.map { ImmutableClassDef.of(it) }
        val context = PatchContexts.of(classes.distinctBy { it.type })

        context.hideReelParts()

        fun after(before: Method) = context.mutableClassDefBy(before.definingClass).methods.single {
            it.name == before.name && it.parameterTypes.map(Any::toString) == before.parameterTypes.map(Any::toString)
        }
        REEL_PARTS.forEach { part ->
            val before = classes.flatMap { it.methods }.single { part.marker in it.markers() }
            val after = after(before)
            assertEquals("$label: ${part.marker} size", before.instructions().size + 5, after.instructions().size)
            assertGuardFirst("$label: ${part.marker}", part, after)
        }
        // The Save button and the save count: one method each, an instance builder of a scope and two
        // longs, guarded first. No other method of the build asks either hook, a feed post's included.
        SAVE_PARTS.forEach { part ->
            val before = classes.flatMap { it.methods }.single { part.marker in it.markers() }
            assertFalse("$label: ${part.marker} is static", AccessFlags.STATIC.isSet(before.accessFlags))
            assertEquals("$label: ${part.marker} parameters", listOf("J", "J"), before.parameterTypes.drop(1).map { it.toString() })
            val after = after(before)
            assertEquals("$label: ${part.marker} size", before.instructions().size + 5, after.instructions().size)
            assertGuardFirst("$label: ${part.marker}", part, after)
            val askers = classes.flatMap { classDef -> context.mutableClassDefBy(classDef.type).methods.map { classDef.type to it } }
                .filter { (_, method) -> method.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == part.hook } }
            assertEquals("$label: who asks ${part.hook}", listOf("${before.definingClass}->${before.name}"),
                askers.map { (type, method) -> "$type->${method.name}" })
        }
        // The reel CTA's product button: the one builder, guarded first and growing by the guard alone.
        val productBefore = classes.flatMap { it.methods }.single { PRODUCT_CTA.marker in it.markers() }
        val productAfter = after(productBefore)
        assertEquals("$label: ${PRODUCT_CTA.marker} size", productBefore.instructions().size + 5, productAfter.instructions().size)
        assertGuardFirst("$label: ${PRODUCT_CTA.marker}", PRODUCT_CTA, productAfter)
        // The in-viewer comment bar's render is guarded by the switch for all reels, nothing else.
        val inViewer = after(classes.flatMap { it.methods }.single { COMMENT_BAR_PARTS.single().marker in it.markers() })
        assertEquals("$label: the in-viewer bar's hook", HIDE_EVERY_COMMENT_BAR, (inViewer.instructions()[0] as ReferenceInstruction).reference.toString())
        assertEquals("$label: the in-viewer bar asks once", 1, inViewer.instructions().count {
            (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(HIDE_EVERY_COMMENT_BAR.substringBefore("->")) == true
        })
        val bubbles = classes.flatMap { it.methods }.single { FLOATING_BUBBLES in it.markers() }
        val none = bubbles.instructions().zipWithNext().first { (read, answer) ->
            read.opcode == Opcode.SGET_OBJECT && answer.opcode == Opcode.RETURN_OBJECT
        }.first
        assertEquals("$label: bubbles size", bubbles.instructions().size + 5, after(bubbles).instructions().size)
        assertBubblesGuarded("$label: bubbles", after(bubbles), (none as ReferenceInstruction).reference.toString())
        assertEquals("$label: line check size", check.instructions().size + 7, after(check).instructions().size)
        assertLineGuarded("$label: line check", after(check), (typeRead as TwoRegisterInstruction).registerB, typeField.toString())
        // The comment bar: guarded where it's shown and right after onViewCreated keeps it.
        val show = classes.flatMap { it.methods }.single { COMMENT_BAR_SHOW in it.markers() }
        val hide = classes.flatMap { it.methods }.single { COMMENT_BAR_HIDE in it.markers() }
        val created = classes.flatMap { it.methods }.single { COMMENT_BAR_CREATED in it.markers() }
        val source = classes.single { it.type == show.definingClass }.fields.single { it.type == CLIPS_VIEWER_SOURCE }
        val bar = hide.instructions().mapNotNull { ((it as? ReferenceInstruction)?.reference as? FieldReference) }
            .single { it.type == VIEW }
        assertEquals("$label: show size", show.instructions().size + 6, after(show).instructions().size)
        assertEquals("$label: onViewCreated size", created.instructions().size + 5, after(created).instructions().size)
        assertCommentBarGuarded("$label: comment bar", after(show), after(created),
            "${source.definingClass}->${source.name}:${source.type}", "${hide.definingClass}->${hide.name}()V",
            "${bar.definingClass}->${bar.name}:${bar.type}", null)
        // And in unVanish, right after the marker's trace, on the register the hide is called on.
        val unVanish = classes.flatMap { it.methods }.single { COMMENT_BAR_UNVANISH in it.markers() }
        val unVanishCode = unVanish.instructions()
        val traced = unVanishCode.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.endsWith(COMMENT_BAR_UNVANISH) == true
        } + 2
        val hideCall = unVanishCode.single {
            it.opcode == Opcode.INVOKE_VIRTUAL && (it as ReferenceInstruction).reference.toString() == "${hide.definingClass}->${hide.name}()V"
        } as FiveRegisterInstruction
        assertEquals("$label: unVanish size", unVanishCode.size + 4, after(unVanish).instructions().size)
        assertUnVanishGuarded("$label: unVanish", after(unVanish), traced, hideCall.registerC,
            "${source.definingClass}->${source.name}:${source.type}", "${hide.definingClass}->${hide.name}()V", null)
        // The reply bar of a reel from a chat (#177): the controller its onViewCreated names keeps the
        // viewer's source in one field, its show reads one flag of it, and that flag gets the hook's
        // answer where the show and onViewCreated read it. Nothing else in the build asks the hook.
        val replyShow = classes.flatMap { it.methods }.single { DIRECT_REPLY_BAR_SHOW in it.markers() }
        val replyHide = classes.flatMap { it.methods }.single { DIRECT_REPLY_BAR_HIDE in it.markers() }
        val replyCreated = classes.flatMap { it.methods }.single { DIRECT_REPLY_BAR_CREATED in it.markers() }
        val controller = classes.single { it.type == replyCreated.definingClass }
        val replySource = controller.fields.single { it.type == CLIPS_VIEWER_SOURCE }
        val hidden = replyShow.instructions().filter { it.opcode == Opcode.IGET_BOOLEAN }
            .map { (it as ReferenceInstruction).reference as FieldReference }.single { it.definingClass == controller.type }
        assertEquals("$label: the reply bar's show size", replyShow.instructions().size + 4, after(replyShow).instructions().size)
        assertEquals("$label: the reply bar's onViewCreated size", replyCreated.instructions().size + 4, after(replyCreated).instructions().size)
        assertReplyBarGuarded("$label: reply bar", after(replyShow), after(replyCreated), hidden.toString(),
            "${replySource.definingClass}->${replySource.name}:${replySource.type}", replyHide.name)
        val askers = classes.flatMap { classDef -> context.mutableClassDefBy(classDef.type).methods.map { classDef.type to it } }
            .filter { (_, method) -> method.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == HIDE_DIRECT_REPLY_BAR } }
        assertEquals("$label: who asks $HIDE_DIRECT_REPLY_BAR",
            setOf("${replyShow.definingClass}->${replyShow.name}", "${replyCreated.definingClass}->${replyCreated.name}"),
            askers.map { (type, method) -> "$type->${method.name}" }.toSet())
        aChatHandsItsReelsTheDirectSource(bundle, label)
    }

    /**
     * A reel opened from a chat is told by its source: the viewer's source enum names DIRECT in the
     * build, and a method of the build makes the reply bar's data with that constant.
     */
    private fun aChatHandsItsReelsTheDirectSource(bundle: File, label: String) {
        val source = FixtureDex.classes(bundle, setOf(CLIPS_VIEWER_SOURCE)).getValue(CLIPS_VIEWER_SOURCE)
        val initializer = source.methods.single { it.name == "<clinit>" }.instructions()
        val named = initializer.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == CHAT_SOURCE }
        assertTrue("$label: the viewer's source names $CHAT_SOURCE", named >= 0)
        val constant = initializer.drop(named).first {
            it.opcode == Opcode.SPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == CLIPS_VIEWER_SOURCE
        }.let { (it as ReferenceInstruction).reference.toString() }
        val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == REPLY_BAR_DATA } }) { method ->
            val code = method.implementation?.instructions
            code != null &&
                code.any { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == REPLY_BAR_DATA } &&
                code.any { it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == constant }
        }
        assertTrue("$label: nothing makes the reply bar's data with $constant", builders.isNotEmpty())
    }

    /**
     * Right after each read of the hidden flag, a flag that's clear becomes the hook's answer for
     * the source read off the same controller, and a flag that's set skips the hook. The show goes
     * on to its own next instruction, and onViewCreated to its own check and Instagram's [hide].
     */
    private fun assertReplyBarGuarded(what: String, show: Method, created: Method, flag: String, source: String, hide: String) {
        fun Method.flagRead() = instructions().indexOfFirst {
            it.opcode == Opcode.IGET_BOOLEAN && (it as ReferenceInstruction).reference.toString() == flag
        }
        for ((where, method) in listOf("show" to show, "onViewCreated" to created)) {
            val code = method.instructions()
            val read = method.flagRead()
            assertTrue("$what: the $where reads the flag", read >= 0)
            val registers = code[read] as TwoRegisterInstruction
            val guard = code.subList(read + 1, read + 5)
            assertEquals(
                "$what: the $where's guard",
                listOf(Opcode.IF_NEZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT),
                guard.map { it.opcode },
            )
            assertEquals("$what: the $where checks the flag", registers.registerA, (guard[0] as OneRegisterInstruction).registerA)
            assertEquals("$what: the $where reads the source off the controller", registers.registerB, (guard[1] as TwoRegisterInstruction).registerB)
            assertEquals("$what: the $where reads the source over the flag", registers.registerA, (guard[1] as TwoRegisterInstruction).registerA)
            assertEquals("$what: the $where's source", source, (guard[1] as ReferenceInstruction).reference.toString())
            assertEquals("$what: the $where's hook", HIDE_DIRECT_REPLY_BAR, (guard[2] as ReferenceInstruction).reference.toString())
            assertEquals("$what: the $where hands the source over", registers.registerA, (guard[2] as FiveRegisterInstruction).registerC)
            assertEquals("$what: the $where's answer is the flag", registers.registerA, (guard[3] as OneRegisterInstruction).registerA)
            assertEquals("$what: a set flag skips the hook in the $where", guard.sumOf { it.codeUnits }, (guard[0] as OffsetInstruction).codeOffset)
            assertEquals("$what: the $where asks once", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == HIDE_DIRECT_REPLY_BAR })
            assertEquals("$what: the $where reads the flag once", 1, code.count {
                it.opcode == Opcode.IGET_BOOLEAN && (it as ReferenceInstruction).reference.toString() == flag
            })
        }
        val made = created.instructions()
        val read = created.flagRead()
        val registers = made[read] as TwoRegisterInstruction
        val check = made[read + 5]
        assertEquals("$what: onViewCreated's own check follows the guard", Opcode.IF_EQZ, check.opcode)
        assertEquals("$what: the check reads the answer", registers.registerA, (check as OneRegisterInstruction).registerA)
        val call = made[read + 6]
        assertEquals("$what: Instagram's hide follows the check", Opcode.INVOKE_VIRTUAL, call.opcode)
        assertEquals("$what: Instagram's hide", hide, ((call as ReferenceInstruction).reference as MethodReference).name)
        assertEquals("$what: the hide on the controller", registers.registerB, (call as FiveRegisterInstruction).registerC)
    }

    /** The no-bubbles state answered first, when the hook says to hide, before the use case's own code. */
    private fun assertBubblesGuarded(what: String, method: Method, none: String) {
        val code = method.instructions()
        assertEquals(
            "$what: the guard's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT),
            code.take(5).map { it.opcode },
        )
        assertEquals("$what: the hook called", HIDE_SOCIAL_FOOTER, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the state answered", none, (code[3] as ReferenceInstruction).reference.toString())
        val jump = code[2] as OffsetInstruction
        assertEquals("$what: the branch lands on the method's own code", code.subList(2, 5).sumOf { it.codeUnits }, jump.codeOffset)
    }

    /**
     * The line's type read off the parameter the check reads it from, handed to the hook before any
     * branch, and yes answered when the hook says so. The branch lands on the check's own first
     * instruction.
     */
    private fun assertLineGuarded(what: String, method: Method, line: Int, type: String) {
        val code = method.instructions()
        assertEquals(
            "$what: the guard's opcodes",
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN,
            ),
            code.take(7).map { it.opcode },
        )
        assertEquals("$what: the line moved in", line, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the type read", type, (code[1] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the hook called", HIDE_SOCIAL_CONTEXT, (code[2] as ReferenceInstruction).reference.toString())
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("$what: the branch lands on the check's own code", addresses[7], addresses[4] + (code[4] as OffsetInstruction).codeOffset)
    }

    /**
     * The show asks the hook with the controller's source first and hides with Instagram's own hide
     * when it says so; onViewCreated does the same right after it keeps the bar, and goes on to its
     * own next instruction either way. [free] is the local the stand-in's guard borrows, when known.
     */
    private fun assertCommentBarGuarded(what: String, show: Method, created: Method, source: String, hide: String, bar: String, free: Int?) {
        val code = show.instructions()
        assertEquals(
            "$what: the show's guard",
            listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
            code.take(6).map { it.opcode },
        )
        val self = show.implementation!!.registerCount - 1
        assertEquals("$what: the source read off the controller", self, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the source", source, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the hook", HIDE_COMMENT_BAR, (code[1] as ReferenceInstruction).reference.toString())
        assertEquals("$what: Instagram's hide", hide, (code[4] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the show's own code", code.subList(3, 6).sumOf { it.codeUnits }, (code[3] as OffsetInstruction).codeOffset)

        val made = created.instructions()
        val stored = made.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference.toString() == bar }
        val guard = made.subList(stored + 1, stored + 6)
        assertEquals(
            "$what: onViewCreated's guard",
            listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL),
            guard.map { it.opcode },
        )
        val thisRegister = created.implementation!!.registerCount - 1 - created.parameterTypes.size
        assertEquals("$what: the bar stored on the controller", thisRegister, (made[stored] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the source read off the controller", thisRegister, (guard[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the source", source, (guard[0] as ReferenceInstruction).reference.toString())
        free?.let { assertEquals("$what: the borrowed local", it, (guard[0] as TwoRegisterInstruction).registerA) }
        assertEquals("$what: the hook", HIDE_COMMENT_BAR, (guard[1] as ReferenceInstruction).reference.toString())
        assertEquals("$what: Instagram's hide", hide, (guard[4] as ReferenceInstruction).reference.toString())
        assertEquals("$what: past the hide", guard.subList(3, 5).sumOf { it.codeUnits }, (guard[3] as OffsetInstruction).codeOffset)
    }

    /**
     * unVanish asks the hook with the source read off the controller in [controller] at [at], right
     * after its trace, and goes to its own call of Instagram's hide when it says so, or on to its
     * own next instruction.
     */
    private fun assertUnVanishGuarded(what: String, method: Method, at: Int, controller: Int, source: String, hide: String, free: Int?) {
        val code = method.instructions()
        val guard = code.subList(at, at + 4)
        assertEquals(
            "$what: the guard",
            listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
            guard.map { it.opcode },
        )
        assertEquals("$what: the trace just ahead", Opcode.INVOKE_STATIC, code[at - 1].opcode)
        assertEquals("$what: the source read off the controller", controller, (guard[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the source", source, (guard[0] as ReferenceInstruction).reference.toString())
        free?.let { assertEquals("$what: the borrowed local", it, (guard[0] as TwoRegisterInstruction).registerA) }
        assertEquals("$what: the hook", HIDE_COMMENT_BAR, (guard[1] as ReferenceInstruction).reference.toString())
        val asked = (guard[0] as TwoRegisterInstruction).registerA
        assertEquals("$what: the answer read", asked, (guard[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the answer checked", asked, (guard[3] as OneRegisterInstruction).registerA)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val landed = addresses.indexOf(addresses[at + 3] + (guard[3] as OffsetInstruction).codeOffset)
        assertEquals("$what: the branch lands on Instagram's hide", hide, (code[landed] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the hide on the controller", controller, (code[landed] as FiveRegisterInstruction).registerC)
        assertEquals("$what: one hide", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == hide })
    }

    private fun assertGuardFirst(what: String, part: ReelPart, method: Method) {
        val code = method.instructions()
        val answer = if (part.check) Opcode.RETURN else Opcode.RETURN_OBJECT
        assertEquals(
            "$what: the guard's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, answer),
            code.take(5).map { it.opcode },
        )
        assertEquals("$what: the hook called", part.hook, (code[0] as ReferenceInstruction).reference.toString())
        val jump = code[2] as OffsetInstruction
        assertEquals("$what: the branch lands on the method's own code", code.subList(2, 5).sumOf { it.codeUnits }, jump.codeOffset)
    }

    private fun owner(marker: String) = "Lfixture/${marker.substringBefore('_')};"

    /**
     * Stand-ins shaped like Instagram 449's: a render per part and the Legacy Follow button's, each
     * the one method of its class loading its marker first, the bubbles check, a static method
     * taking three objects, the bubbles use case answering its no-bubbles state or another, and the
     * social context check asking its line's type for its ordinal.
     */
    private fun classes(
        leaveOut: String? = null,
        staticRender: String? = null,
        checkReturns: String = "Z",
        noneOfItsOwn: Boolean = true,
        typeNames: List<String> = listOf("FOLLOWED_BY", "LIKED_BY", "FOLLOWER_COUNT"),
        overwritesLine: Boolean = false,
        saveShape: String = "ok",
        barSource: Boolean = true,
        barStores: Int = 1,
        barThisReused: Boolean = false,
        unVanish: String = "ok",
        replyBar: String = "ok",
        productCtaLabel: String = "Ljava/lang/String;",
    ): List<ClassDef> {
        val renders = (REEL_PARTS.filter { !it.check }.map { it.marker } + "ClipsFollowButtonComponentLegacy_render")
            .filter { it != leaveOut }
            .map { render(owner(it), it, static = it == staticRender) }
        val builders = SAVE_PARTS.filter { it.marker != leaveOut && (saveShape != "both" || it === SAVE_PARTS[0]) }
            .map { save(UFI, if (it === SAVE_PARTS[0]) "A0D" else "A06", it.marker, saveShape) }
        val saves = if (builders.isEmpty()) emptyList() else listOf(classOf(UFI, "Lfixture/KComponent;", *builders.toTypedArray()))
        val checks = REEL_PARTS.filter { it.check && it.marker != leaveOut }.map { check(owner(it.marker), it.marker, checkReturns) }
        val none = if (noneOfItsOwn) "$NO_BUBBLES->A00:$NO_BUBBLES" else "$BUBBLES->none:$NO_BUBBLES"
        val bubbles = classOf(
            BUBBLES, "Ljava/lang/Object;",
            method(
                BUBBLES, "A00", listOf("Lfixture/Item;"), BUBBLES_STATE, 3,
                """
                    const-string v0, "android_purge_26_q3_$FLOATING_BUBBLES"
                    if-eqz p1, :none
                    const/4 v0, 0x0
                    return-object v0
                    :none
                    sget-object v0, $none
                    return-object v0
                """,
                static = false,
            ),
        )
        val overwrite = if (overwritesLine) "move-object/from16 p2, p1" else ""
        val lineCheck = classOf(
            VIEW_UTIL, "Ljava/lang/Object;",
            method(
                VIEW_UTIL, "A06", listOf("Lfixture/Item;", LINE, "Ljava/lang/String;"), "Z", 6,
                """
                    const-string v0, "android_purge_26_q3_$SOCIAL_CONTEXT_CHECK"
                    $overwrite
                    iget-object v1, p2, $LINE->type:$LINE_TYPE
                    invoke-virtual { v1 }, Ljava/lang/Enum;->ordinal()I
                    move-result v0
                    if-eqz v0, :keep
                    const/4 v0, 0x1
                    return v0
                    :keep
                    const/4 v0, 0x0
                    return v0
                """,
                static = false,
            ),
        )
        val names = typeNames.joinToString("\n") { "const-string v0, \"$it\"" }
        val lineType = classOf(
            LINE_TYPE, "Ljava/lang/Enum;",
            method(LINE_TYPE, "<clinit>", emptyList(), "V", 1, "$names\nreturn-void", constructor = true),
        )
        val line = ImmutableClassDef(
            LINE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(LINE, "type", LINE_TYPE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
            null,
        )
        val productCta = classOf(
            owner(PRODUCT_CTA.marker), "Lfixture/KComponent;",
            method(
                owner(PRODUCT_CTA.marker), "A01", listOf(scope, productCtaLabel), component, 4,
                """
                    const-string v0, "android_purge_26_q3_${PRODUCT_CTA.marker}"
                    const/4 v0, 0x0
                    return-object v0
                """,
                static = false,
            ),
        )
        return renders + saves + checks + productCta + bubbles + lineCheck + lineType + line + commentBar(barSource, barStores, barThisReused) +
            unVanish(unVanish) + replyBar(replyBar)
    }

    /**
     * A direct reply bar controller shaped like Instagram 450's: its show and hide merged into its
     * superclass, the show reaching its code through an instance check and turning the controller's
     * hidden flag into the bar's visibility, and its onViewCreated calling the hide when the flag is
     * already set, with the viewer's source in a field of its own, an enum naming DIRECT. [shape]
     * breaks one part of that.
     */
    private fun replyBar(shape: String): List<ClassDef> {
        val fields = listOfNotNull(
            ImmutableField(REPLY, "bar", VIEW, AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(REPLY, "hidden", "Z", AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(REPLY, "typing", "Z", AccessFlags.PUBLIC.value, null, null, null),
            if (shape == "no source") null else ImmutableField(REPLY, "source", CLIPS_VIEWER_SOURCE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
        )
        val jump = if (shape == "jumped") "if-eqz v0, :visibility" else ""
        val landing = if (shape == "jumped") ":visibility" else ""
        val read = if (shape == "over the controller") "iget-boolean v1, v1, $REPLY->hidden:Z" else "iget-boolean v0, v1, $REPLY->hidden:Z"
        val other = if (shape == "two flags") "iget-boolean v2, v1, $REPLY->typing:Z" else ""
        val show = method(REPLY_BASE, "A0u", emptyList(), "V", 4, """
            instance-of v0, p0, $REPLY
            if-eqz v0, :spotlight
            move-object v1, p0
            check-cast v1, $REPLY
            const-string v0, "android_purge_26_q3_$DIRECT_REPLY_BAR_SHOW"
            invoke-static { v0 }, Lfixture/Trace;->A00(Ljava/lang/String;)V
            iget-object v2, v1, $REPLY->bar:$VIEW
            if-eqz v2, :done
            $jump
            $read
            $landing
            $other
            invoke-static { v0 }, Lfixture/Visibility;->A00(I)I
            move-result v1
            invoke-virtual { v2, v1 }, Landroid/view/View;->setVisibility(I)V
            :done
            return-void
            :spotlight
            const-string v0, "android_purge_26_q3_ClipsViewerSpotlightReplyBarController_showReplyBar"
            return-void
        """, static = false)
        val marked = """
            const-string v0, "android_purge_26_q3_$DIRECT_REPLY_BAR_HIDE"
            return-void
        """
        // On 451 the controller declares the hide itself, over an abstract one of the class it extends.
        val hide = if (shape == "hide in the controller") {
            ImmutableMethod(REPLY_BASE, "A0t", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
        } else {
            method(REPLY_BASE, "A0t", emptyList(), "V", 2, marked, static = false)
        }
        val own = when (shape) {
            "hide in the controller" -> method(REPLY, "A0t", emptyList(), "V", 2, marked, static = false)
            "hide overridden" -> method(REPLY, "A0t", emptyList(), "V", 1, "return-void", static = false)
            else -> null
        }
        val again = if (shape == "read twice") "iget-boolean v0, v4, $REPLY->hidden:Z" else ""
        val hidden = when (shape) {
            "no hide" -> "const/4 v1, 0x0"
            "hide on another register" -> "invoke-virtual { p0 }, $REPLY_BASE->A0t()V"
            else -> "invoke-virtual { v4 }, $REPLY_BASE->A0t()V"
        }
        // Like 450's, it copies the controller out of p0 first, and something jumps to the flag's read.
        val created = method(REPLY, "onViewCreated", listOf(VIEW, "Landroid/os/Bundle;"), "V", 8, """
            const-string v0, "android_purge_26_q3_$DIRECT_REPLY_BAR_CREATED"
            move-object v4, p0
            iput-object p1, v4, $REPLY->bar:$VIEW
            iget-boolean v0, v4, $REPLY->typing:Z
            if-eqz v0, :flag
            const/4 v1, 0x0
            :flag
            iget-boolean v0, v4, $REPLY->hidden:Z
            $again
            if-eqz v0, :done
            $hidden
            :done
            return-void
        """, static = false)
        val controller = ImmutableClassDef(
            REPLY, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, if (shape == "show elsewhere") "Ljava/lang/Object;" else REPLY_BASE,
            null, null, null, fields, listOfNotNull(created, own),
        )
        val name = if (shape == "no chat source") "DIRECT_SEARCH" else CHAT_SOURCE
        val source = classOf(
            CLIPS_VIEWER_SOURCE, "Ljava/lang/Enum;",
            method(CLIPS_VIEWER_SOURCE, "<clinit>", emptyList(), "V", 1, "const-string v0, \"direct\"\nconst-string v0, \"$name\"\nreturn-void", constructor = true),
        )
        return listOf(classOf(REPLY_BASE, "Ljava/lang/Object;", show, hide), controller, source)
    }

    /**
     * unVanish shaped like Instagram 450's lambda: it reads the controller into v2 over its own
     * parameter, traces its marker, reads a flag of the controller and, on its hidden branch,
     * calls the controller's hide and goes to the shared return. [shape] breaks one part of that.
     */
    private fun unVanish(shape: String): ClassDef {
        val trace = if (shape == "no trace") "" else "invoke-static { v0 }, Lfixture/Trace;->A00(Ljava/lang/String;)V"
        val read = if (shape == "no read") "const/4 v0, 0x0" else "iget-boolean v0, v2, $BAR->vanished:Z"
        val again = if (shape == "jumped") "if-nez v1, :again" else ""
        val twice = if (shape == "twice") "invoke-virtual { v2 }, $BAR->A0r()V" else ""
        // The hidden branch answers v1. "rewritten" writes it after the trace on the way to the hide,
        // which the hook's jump would skip; "shown rewrites" leaves only the shown branch writing it.
        val rewrite = if (shape == "rewritten") "iget-object v1, v2, $BAR->bar:$VIEW" else ""
        val hiddenEnd = if (shape == "rewritten" || shape == "shown rewrites") "return-object v1" else "goto :done"
        return classOf(
            UNVANISH, "Ljava/lang/Object;",
            method(UNVANISH, "A0B", listOf(UNVANISH), "Ljava/lang/Object;", 3, """
                iget-object v2, p0, $UNVANISH->controller:$BAR
                if-eqz v2, :done
                const/4 v1, 0x0
                const-string v0, "android_purge_26_q2_$COMMENT_BAR_UNVANISH"
                $trace
                :again
                $read
                $rewrite
                if-nez v0, :hide
                iget-object v1, v2, $BAR->bar:$VIEW
                $again
                :done
                const/4 v0, 0x0
                return-object v0
                :hide
                $twice
                invoke-virtual { v2 }, $BAR->A0r()V
                $hiddenEnd
            """),
        )
    }

    /**
     * A comment bar controller shaped like Instagram 450's: a show and a hide taking nothing, the
     * hide reading the bar, and onViewCreated keeping the bar it inflates, with the viewer's source
     * in a field of its own.
     */
    private fun commentBar(source: Boolean, stores: Int, thisReused: Boolean): ClassDef {
        val fields = listOfNotNull(
            ImmutableField(BAR, "bar", VIEW, AccessFlags.PUBLIC.value, null, null, null),
            if (source) ImmutableField(BAR, "source", CLIPS_VIEWER_SOURCE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) else null,
        )
        val store = (1..stores).joinToString("\n") { "iput-object v1, p0, $BAR->bar:$VIEW" }
        val reuse = if (thisReused) "move-object p0, v1" else ""
        val methods = listOf(
            method(BAR, "A0t", emptyList(), "V", 2, """
                const-string v0, "android_purge_26_q2_$COMMENT_BAR_SHOW"
                return-void
            """, static = false),
            method(BAR, "A0r", emptyList(), "V", 2, """
                const-string v0, "android_purge_26_q2_$COMMENT_BAR_HIDE"
                iget-object v0, p0, $BAR->bar:$VIEW
                return-void
            """, static = false),
            method(BAR, "onViewCreated", listOf(VIEW, "Landroid/os/Bundle;"), "V", 5, """
                const-string v0, "android_purge_26_q2_$COMMENT_BAR_CREATED"
                $reuse
                move-object v1, p1
                $store
                const/4 v0, 0x0
                return-void
            """, static = false),
        )
        return ImmutableClassDef(BAR, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)
    }

    private fun method(
        type: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        static: Boolean = true, constructor: Boolean = false,
    ): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else AccessFlags.FINAL.value) or
            (if (constructor) AccessFlags.CONSTRUCTOR.value else 0)
        return ImmutableMethod.of(
            MutableMethod(
                ImmutableMethod(
                    type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body.trimIndent()) },
        )
    }

    private fun classOf(type: String, superclass: String, vararg methods: Method): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null, methods.toList(),
    )

    private fun render(type: String, marker: String, static: Boolean = false): ClassDef {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Lfixture/KComponent;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    type, "A0m", listOf(ImmutableMethodParameter(scope, null, null)), component, flags, null, null,
                    ImmutableMethodImplementation(
                        3,
                        listOf(
                            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("android_purge_26_q3_$marker")),
                            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ),
                        null, null,
                    ),
                ),
            ),
        )
    }

    /**
     * Shaped like the clips UFI's builders on 450: a private instance method taking the component
     * scope and two longs, loading its marker first and answering a component or nothing.
     */
    private fun save(type: String, name: String, marker: String, shape: String): Method {
        val static = shape == "static"
        val parameters = if (shape == "parameters") listOf(scope, "I", "J") else listOf(scope, "J", "J")
        val returns = if (shape == "boolean") "Z" else component
        val extra = if (shape == "both") "\n                    const-string v2, \"android_purge_26_q2_${SAVE_PARTS[1].marker}\"" else ""
        return method(
            type, name, parameters, returns, 8,
            """
                const-string v0, "android_purge_26_q2_$marker"$extra
                const/4 v0, 0x0
                return${if (shape == "boolean") "" else "-object"} v0
            """,
            static = static,
        )
    }

    private fun check(type: String, marker: String, returns: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "A01", listOf("Lfixture/A;", "Lfixture/B;", "Lfixture/C;").map { ImmutableMethodParameter(it, null, null) },
                returns, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(
                    5,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("android_purge_26_q3_$marker")),
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction11x(Opcode.RETURN, 0),
                    ),
                    null, null,
                ),
            ),
        ),
    )

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val UFI = "Lfixture/ClipsUfiComponent;"
        const val BUBBLES = "Lfixture/FloatingBubblesUseCase;"
        const val BUBBLES_STATE = "Lfixture/BubblesState;"
        const val NO_BUBBLES = "Lfixture/NoBubbles;"
        const val VIEW_UTIL = "Lfixture/MediaSocialContextViewUtil;"
        const val LINE = "Lfixture/SocialContext;"
        const val LINE_TYPE = "Lfixture/SocialContextType;"
        const val BAR = "Lfixture/ClipsViewerCommentBarController;"
        const val UNVANISH = "Lfixture/CommentBarLambda;"
        const val REPLY_BASE = "Lfixture/ClipsViewerReplyBarController;"
        const val REPLY = "Lfixture/ClipsViewerDirectReplyBarController;"
        const val REPLY_BAR_DATA = "Lcom/instagram/clips/model/ClipsReplyBarData;"
        const val VIEW = "Landroid/view/View;"
    }
}
