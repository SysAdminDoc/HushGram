/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.photos

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
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.pickerSizesBridge
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullResolutionPhotosHookTest {
    private val contextType = "Landroid/content/Context;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val useCase = "Lfixture/FeedImageUseCase;"
    private val forScreen = "$MEDIA_EXT->forScreen($contextType$MEDIA)$EXTENDED_IMAGE_URL"
    private val parameters = listOf(contextType, session, MEDIA)

    /** The hook the patch writes is in the FullResolution the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FULL_RESOLUTION).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$PHOTO is not in the extension: $declared", PHOTO.substringAfter("->") in declared)
    }

    /**
     * Right after the size is kept: the call with the post and the size, the answer back in the
     * size's register, cast to a size, and then what came next before. The branch past the pick
     * still lands where it did, and the method's other code is unchanged.
     */
    @Test
    fun theSizeIsHandedOverRightAfterThePick() {
        val classes = classes()
        val before = classes.single().methods.single().code().size
        val patched = PatchContexts.of(classes)

        patched.load()

        val method = patched.mutableClassDefBy(useCase).methods.single()
        assertHooked("stand-in", method, media = 3, size = 4)
        assertEquals("the method grew by more than the hook", before + 3, method.code().size)
        val none = method.code().indexOfFirst { it.string() == NO_IMAGE_URL }
        val branch = method.implementation!!.instructions.first { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch past the pick moved", none, branch.target.location.index)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val method = "feed photo address method logging that it found none in this Instagram build"
        val where = "$useCase->source"
        val cases = listOf(
            classes(methods = 0) to "expected exactly one $method, found none",
            classes(methods = 2) to "expected exactly one $method, found $where, $useCase->source2",
            classes(static = true) to "expected exactly one $method, found none",
            classes(calls = 0) to "expected $where to ask Media's helpers once for the size for a Context, found 0",
            classes(calls = 2) to "expected $where to ask Media's helpers once for the size for a Context, found 2",
            classes(keepsSize = false) to "$where doesn't keep the size it's handed",
            classes(sizeRegister = 3) to "$where keeps the size in the post's own register v3",
            classes(sizeRegister = 17) to "$where keeps the post in v3 and the size in v17, past what a plain invoke can name",
            classes(jumpPastPick = true) to "something in $where jumps in right after the size it's handed",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.load() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == PHOTO } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * The picker's own read of the post's sizes is found through the helper the use case asks: its
     * one ask for the size for a width, and in that one the one read of the post's sizes, kept and
     * handed first to what picks the size. Anything else stops the patch, saying what it found.
     */
    @Test
    fun thePickersReadIsFoundOrThePatchStops() {
        val forWidth = "$MEDIA_EXT->forWidth(${MEDIA}I)$EXTENDED_IMAGE_URL"
        val once = "expected $forScreen to ask Media's helpers once for the size for a width"
        val handed = "$forWidth doesn't pick the size from the sizes it reads"
        val cases = listOf(
            helpers() to null,
            helpers(widths = 0) to once,
            helpers(widths = 2) to once,
            helpers(reads = 0) to "expected $forWidth to ask Media's helpers once for the post's sizes, found 0",
            helpers(reads = 2) to "expected $forWidth to ask Media's helpers once for the post's sizes, found 2",
            helpers(keeps = false) to "$forWidth doesn't keep the sizes it reads",
            helpers(handsOn = false) to handed,
            helpers(overwritten = true) to handed,
        )
        for ((helpers, expected) in cases) {
            val context = PatchContexts.of(classes() + helpers)
            val site = context.findFullResolution()
            if (expected == null) {
                assertEquals("$MEDIA_EXT->sizes($MEDIA)$IMAGE_INFO", context.findPickerSizes(site).toString())
                continue
            }
            val failure = assertThrows(expected, PatchException::class.java) { context.findPickerSizes(site) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /**
     * In each declared build the method picking a feed photo's address is found by its log line
     * alone, and the hook lands right after its one ask for the size for the screen.
     */
    @Test
    fun eachDeclaredBuildHandsOverTheFeedPhotosSize() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.code().any { it.string() == NO_IMAGE_URL } }) {
                            holders += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(holders)

                val site = context.findFullResolution()
                context.loadFullResolution(site)

                val method = context.mutableClassDefBy(site.definingClass).methods.single {
                    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == parameters
                }
                assertTrue("${bundle.name}: the method is an instance one", !AccessFlags.STATIC.isSet(method.accessFlags))
                assertHooked(bundle.name, method, site.media, site.size)
                val written = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                    .filter { written -> written.code().any { it.referenceText() == PHOTO } }
                assertEquals("${bundle.name}: methods hooked", 1, written.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * In each declared build the readers the hook goes through point where Instagram's picker
     * reads: the post's own sizes through the getter the picker calls on the post, the picker's
     * sizes through the helper it asks, their candidates through the call made by the method it
     * hands them to, and every candidate the tree-backed sizes list is of the pick's class, an
     * ImageUrl, read through that interface's own getters.
     */
    @Test
    fun eachDeclaredBuildReadsTheSizesThePickerReads() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val wanted = setOf(MEDIA_EXT, MEDIA)
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val pool = linkedMapOf<String, ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        val type = classDef.type
                        if (type in wanted || SIZE_PACKAGES.any(type::startsWith) ||
                            classDef.methods.any { method -> method.code().any { it.string() == NO_IMAGE_URL } }
                        ) {
                            pool.putIfAbsent(type, ImmutableClassDef.of(classDef))
                        }
                    }
                }
                val site = PatchContexts.of(pool.values).findFullResolution()
                val forWidth = pool.getValue(MEDIA_EXT).method(site.forScreen).code().mapNotNull { it.call() }
                    .single { it.definingClass == MEDIA_EXT && it.returnType == EXTENDED_IMAGE_URL }
                val picker = pool.getValue(MEDIA_EXT).method(forWidth).code().mapNotNull { it.call() }
                val onPost = picker.single { it.definingClass == MEDIA && it.returnType == IMAGE_INFO }
                val read = picker.single { it.definingClass == MEDIA_EXT && it.returnType == IMAGE_INFO }
                val choose = picker.single { it.returnType == EXTENDED_IMAGE_URL && it.parameterTypes.firstOrNull()?.toString() == IMAGE_INFO }
                pool.putAll(FixtureDex.classes(bundle, setOf(choose.definingClass)))
                val candidates = pool.getValue(choose.definingClass).method(choose).code().mapNotNull { it.call() }
                    .single { it.definingClass == IMAGE_INFO && it.returnType == "Ljava/util/List;" }
                val context = PatchContexts.of(pool.values + ExtensionDex.classDef(INSTAGRAM_MEDIA))

                val helper = context.findPickerSizes(context.findFullResolution())
                context.imageBridges("test")()
                context.pickerSizesBridge("test", helper)()

                val what = bundle.name
                val bridges = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
                fun bridge(name: String) = bridges.single { it.name == name }.code()
                fun assertReads(name: String, receiver: String, call: MethodReference) {
                    val code = bridge(name)
                    assertEquals("$what: $name casts to", receiver, (code[0].reference() as TypeReference).type)
                    assertEquals("$what: $name calls", call.toString(), code[1].call().toString())
                }
                assertEquals("$what: the helper found", read.toString(), helper.toString())
                assertReads("imageVersions", MEDIA, onPost)
                assertReads("pickerImageVersions", MEDIA, read)
                assertReads("imageCandidates", IMAGE_INFO, candidates)
                val listed = pool.getValue(PANDO_IMAGE_INFO).methods.single { it.name == candidates.name && it.parameterTypes.isEmpty() }
                assertTrue(
                    "$what: the candidates aren't of the pick's class",
                    listed.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as TypeReference).type == EXTENDED_IMAGE_URL },
                )
                assertTrue("$what: a size isn't an ImageUrl", IMAGE_URL in supertypes(EXTENDED_IMAGE_URL, pool))
                for ((name, getter) in listOf("candidateUrl" to "getUrl", "candidateWidth" to "getWidth", "candidateHeight" to "getHeight")) {
                    val call = bridge(name)[1].call()!!
                    assertEquals("$what: $name", "$IMAGE_URL->$getter", "${call.definingClass}->${call.name}")
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun ClassDef.method(reference: MethodReference): Method = methods.single {
        it.name == reference.name && it.returnType == reference.returnType &&
            it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
    }

    /** [type], its superclasses and every interface they implement, as far as [pool] holds them. */
    private fun supertypes(type: String, pool: Map<String, ClassDef>): Set<String> {
        val seen = linkedSetOf<String>()
        val next = ArrayDeque(listOf(type))
        while (next.isNotEmpty()) {
            val current = next.removeFirst()
            if (!seen.add(current)) continue
            val classDef = pool[current] ?: continue
            classDef.superclass?.let(next::add)
            next.addAll(classDef.interfaces)
        }
        return seen
    }

    /**
     * Once in the method: the static call to Media's helpers taking a Context and the post, the
     * move-result keeping the size, then the hook with the post and the size, its answer back in
     * the size's register and the cast to a size. Nothing jumps onto the hook.
     */
    private fun assertHooked(what: String, method: MutableMethod, media: Int, size: Int) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == PHOTO })
        val hook = code.indexOfFirst { it.referenceText() == PHOTO }
        val pick = code[hook - 2]
        val picked = (pick as ReferenceInstruction).reference as MethodReference
        assertEquals("$what: what comes before", MEDIA_EXT, picked.definingClass)
        assertEquals("$what: what it's handed", listOf(contextType, MEDIA), picked.parameterTypes.map(CharSequence::toString))
        assertEquals("$what: what it answers", EXTENDED_IMAGE_URL, picked.returnType)
        assertEquals("$what: the post handed to the pick", media, (pick as FiveRegisterInstruction).registerD)
        assertEquals("$what: the kept size", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertEquals("$what: the kept size's register", size, (code[hook - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[hook].opcode)
        val call = code[hook] as FiveRegisterInstruction
        assertEquals("$what: the post and the size", listOf(2, media, size), listOf(call.registerCount, call.registerC, call.registerD))
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the answer's register", size, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[hook + 2].opcode)
        assertEquals("$what: the cast register", size, (code[hook + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast type", EXTENDED_IMAGE_URL, ((code[hook + 2] as ReferenceInstruction).reference as TypeReference).type)
        val jumps = method.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
        assertTrue("$what: something jumps onto the hook", jumps.none { it.target.location.index in hook..hook + 2 })
    }

    private fun BytecodePatchContext.load() = loadFullResolution(findFullResolution())

    /**
     * A feed image use case shaped like Instagram 450's. Its one method takes the Context, the
     * session and the post, copies the post into v3, asks Media's helpers
     * for the size for the screen with the Context in v0 and keeps it in v4. A size goes on to the
     * end; none, or no picture at all, logs [NO_IMAGE_URL] first.
     */
    private fun classes(
        methods: Int = 1,
        static: Boolean = false,
        calls: Int = 1,
        keepsSize: Boolean = true,
        sizeRegister: Int = 4,
        jumpPastPick: Boolean = false,
    ): List<ClassDef> {
        fun source(name: String): Method {
            val ask = "invoke-static {v0, v3}, $forScreen"
            val keep = if (keepsSize) "move-result-object v$sizeRegister" else "nop"
            val asks = when (calls) {
                0 -> listOf("invoke-static {v3}, $MEDIA_EXT->cached($MEDIA)$EXTENDED_IMAGE_URL", "move-result-object v4")
                else -> List(calls) { listOf(ask, keep) }.flatten()
            }
            // Twenty locals, then this when there is one, so the Context is v20 or v21 and the post two on.
            val context = if (static) 20 else 21
            return method(
                name, 20, static,
                body = (
                    listOf(
                        "move-object/from16 v3, v${context + 2}",
                        "invoke-static {v3}, $MEDIA_EXT->hasPicture($MEDIA)Z",
                        "move-result v1",
                        "if-eqz v1, :none",
                        if (jumpPastPick) "if-nez v1, :after" else "nop",
                        "move-object/from16 v0, v$context",
                    ) + asks + listOf(
                        ":after",
                        "if-nez v4, :found",
                        ":none",
                        "const-string v0, \"$NO_IMAGE_URL\"",
                        "invoke-static {v0}, Lfixture/Log;->report(Ljava/lang/String;)V",
                        "const/4 v4, 0x0",
                        ":found",
                        "return-object v4",
                    )
                    ).filter { it != "nop" }.joinToString("\n"),
            )
        }
        val sources = (1..methods).map { source(if (it == 1) "source" else "source$it") }
        val padding = if (methods == 0) {
            listOf(method("unrelated", 2, static = false, body = "const/4 v0, 0x0\nreturn-object v0"))
        } else {
            emptyList()
        }
        return listOf(classDef(useCase, sources + padding))
    }

    /**
     * Media's helpers shaped like Instagram 450's: the one for the screen asks the one for a width,
     * which reads the post's sizes into v1 and hands them first to what picks the size.
     */
    private fun helpers(
        widths: Int = 1,
        reads: Int = 1,
        keeps: Boolean = true,
        handsOn: Boolean = true,
        overwritten: Boolean = false,
    ): ClassDef {
        val forWidth = "$MEDIA_EXT->forWidth(${MEDIA}I)$EXTENDED_IMAGE_URL"
        val sizes = "$MEDIA_EXT->sizes($MEDIA)$IMAGE_INFO"
        val screen = List(widths) { "invoke-static {p1, v0}, $forWidth\nmove-result-object v0" }
        val read = List(reads) { "invoke-static {p0}, $sizes" + if (keeps) "\nmove-result-object v1" else "" }
        val handOn = "invoke-static {${if (handsOn) "v1" else "v0"}, p1}, Lfixture/Sizes;->choose(${IMAGE_INFO}I)$EXTENDED_IMAGE_URL"
        return classDef(
            MEDIA_EXT,
            listOf(
                helper("forScreen", listOf(contextType, MEDIA), (listOf("const/4 v0, 0x0") + screen + "return-object v0").joinToString("\n")),
                helper(
                    "forWidth", listOf(MEDIA, "I"),
                    (listOf("const/4 v0, 0x0", "const/4 v1, 0x0") + read + listOfNotNull(if (overwritten) "const/4 v1, 0x0" else null) +
                        listOf(handOn, "move-result-object v0", "return-object v0")).joinToString("\n"),
                ),
                helper("sizes", listOf(MEDIA), "const/4 v0, 0x0\nreturn-object v0", returns = IMAGE_INFO),
            ),
        )
    }

    /** A static helper of [MEDIA_EXT] with two locals, answering a size unless [returns] says otherwise. */
    private fun helper(name: String, parameters: List<String>, body: String, returns: String = EXTENDED_IMAGE_URL): Method {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val mutable = MutableMethod(
            ImmutableMethod(
                MEDIA_EXT, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(2 + parameters.size, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body)
        return ImmutableMethod.of(mutable)
    }

    private fun method(name: String, locals: Int, static: Boolean, body: String): Method {
        var flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        if (static) flags = flags or AccessFlags.STATIC.value
        val total = locals + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                useCase, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/lang/Object;", flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string

    private fun Instruction.call(): MethodReference? = reference() as? MethodReference

    private companion object {
        /** Where Instagram keeps its picture sizes and their address classes. */
        val SIZE_PACKAGES = listOf("Lcom/instagram/model/mediasize/", "Lcom/instagram/common/typedurl/")
    }
}
