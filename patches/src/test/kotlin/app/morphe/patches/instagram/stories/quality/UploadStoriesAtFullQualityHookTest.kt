/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.quality

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Upload stories at full quality: Instagram's high quality upload check, found and answered first for a story. */
class UploadStoriesAtFullQualityHookTest {
    private val prefs = "Lfixture/Prefs;"
    private val strategy = "Lfixture/TranscodeStrategy;"
    private val logger = "Lfixture/UploadLogger;"
    private val store = "Lfixture/Store;"
    private val wrapper = "Lfixture/TranscodeJob;"
    private val params = "Lfixture/TranscodeParams;"
    private val widths = "Lfixture/Widths;"
    private val upload = "Lfixture/Upload;"
    private val encoder = "Lfixture/PhotoEncoder;"
    private val renderer = "Lfixture/PhotoRenderer;"
    private val reader = "$prefs->A00($store)Z"

    /** The hook the patch writes is in the StoryUploadQuality the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_UPLOAD_QUALITY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FULL_QUALITY is not in the extension: $declared", FULL_QUALITY.substringAfter("->") in declared)
        for (hook in listOf(BITRATE_HOOK, RESOLUTION_HOOK, FRAME_RATE_HOOK, PHOTO_QUALITY_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The check asks the extension first and returns its yes, and nothing else changes. */
    @Test
    fun theCheckAsksTheExtensionFirst() {
        val classes = classes()
        val context = PatchContexts.of(classes)
        val before = classes.associate { c -> c.type to c.methods.associate { it.name to it.code().size } }

        val check = context.findStoryUploadQuality()
        val setter = context.findBitrateSetter(check)
        context.raiseStoryUploadQuality(check)
        context.raiseBitrate(check, setter)

        assertEquals("the check", strategy to "A08", check.type to check.name)
        assertEquals("the share type field", "A03", check.shareType.name)
        assertEquals("the setter", listOf("A07", wrapper, "A02", "A01", "A04"), listOf(setter.name, setter.parameter, setter.holder.name) + setter.bitrate.map { it.name })
        assertEquals("the frame rate", "A02", setter.frameRate.name)
        assertHooked("stand-in", context.mutableClassDefBy(strategy).methods.single { it.name == "A08" }, check)
        assertBitrateHooked("stand-in", context.mutableClassDefBy(strategy).methods.single { it.name == "A07" }, check, setter)
        for (c in classes) {
            for (method in context.mutableClassDefBy(c.type).methods) {
                val added = when {
                    c.type == strategy && method.name == "A08" -> HOOK_SIZE
                    c.type == strategy && method.name == "A07" -> BITRATE_HOOK_SIZE
                    else -> 0
                }
                assertEquals("${c.type}->${method.name}", before.getValue(c.type).getValue(method.name) + added, method.code().size)
            }
        }
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(key = "high_quality_upload") to "expected one reader of the $HIGH_QUALITY_PREF setting, found 0",
            classes(secondReader = true) to "expected one reader of the $HIGH_QUALITY_PREF setting, found 2",
            classes(shareTypeField = false) to "expected one method asking the $HIGH_QUALITY_PREF setting about an upload's share type, found 0",
            classes(readsShareType = false) to "expected one method asking the $HIGH_QUALITY_PREF setting about an upload's share type, found 0",
            classes(secondCheck = true) to "$strategy has 2 methods asking the $HIGH_QUALITY_PREF setting about its share type",
            classes(ordinal = false) to "$strategy->A08 doesn't pick share types by their ordinal",
            classes(bitrate = 8_000_000) to "no method of $strategy asks $strategy->A08 and gives the $HIGH_QUALITY_BITRATE bps",
            classes(loopsToStart = true) to "something jumps back to the first instruction of $strategy->A08",
            classes(locals = 17) to "$strategy->A08 has 18 registers",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.raiseStoryUploadQuality(context.findStoryUploadQuality())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertNothingWritten(expected, context, classes)
        }
    }

    /**
     * In each of the three 452 builds there's one reader of Instagram's setting, and among the
     * classes holding an upload's share type one check asking it about that share type, by the
     * field or, as on 451's 385713073, through a static helper reading its ordinal. The check is
     * answered first, and nothing else is.
     */
    @Test
    fun eachBuildAnswersTheCheckFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val wanted = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val holdsShareType = classDef.fields.any { it.type == SHARE_TYPE && !AccessFlags.STATIC.isSet(it.accessFlags) }
                    if (holdsShareType || classDef.methods.any { it.readsAnIntSetting() }) wanted += ImmutableClassDef.of(classDef)
                }
            }
            val classes = FixtureDex.withStringPools(bundle, wanted)
            val context = PatchContexts.of(classes)

            val pref = context.findHighQualityPref()
            val check = context.findStoryUploadQuality()
            val setter = context.findBitrateSetter(check)
            context.raiseStoryUploadQuality(check)
            context.raiseBitrate(check, setter)

            assertTrue("${bundle.name}: the reader $pref", pref.parameterTypes.size == 1 && pref.returnType == "Z")
            val patched = context.mutableClassDefBy(check.type).methods.single { it.name == check.name && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            assertHooked(bundle.name, patched, check)
            val elsewhere = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method ->
                    !(method.definingClass == check.type && method.name == check.name && method.parameterTypes.isEmpty()) &&
                        method.code().any { it.referenceText() == FULL_QUALITY }
                }
            assertTrue("${bundle.name}: hooked elsewhere too: $elsewhere", elsewhere.isEmpty())

            val bitrateSetter = context.mutableClassDefBy(check.type).methods.single { it.name == setter.name && it.parameterTypes.size == 1 }
            assertBitrateHooked(bundle.name, bitrateSetter, check, setter)
            val bitrateElsewhere = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method !== bitrateSetter && method.code().any { it.referenceText() == BITRATE_HOOK } }
            assertTrue("${bundle.name}: bitrate hooked elsewhere too: $bitrateElsewhere", bitrateElsewhere.isEmpty())
        }
    }

    /** A setter the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aSetterThePatchCantReadFailsBeforeAnythingChanges() {
        val classes = classes(twoBitrateFields = false)
        val context = PatchContexts.of(classes)
        val check = context.findStoryUploadQuality()
        val failure = assertThrows(PatchException::class.java) { context.findBitrateSetter(check) }
        assertTrue(failure.message, failure.message!!.contains("writes 1 int fields of the transcode params, expected two of one class"))
        assertTrue("nothing written", classes.flatMap { c -> context.mutableClassDefBy(c.type).methods }.none { m -> m.code().any { it.referenceText() == BITRATE_HOOK } })
    }

    /** A setter with no frame rate fallback to find fails at patch time, saying so. */
    @Test
    fun aSetterWithNoFrameRateFails() {
        val context = PatchContexts.of(classes(frameRateFallback = false))
        val check = context.findStoryUploadQuality()
        val failure = assertThrows(PatchException::class.java) { context.findBitrateSetter(check) }
        assertTrue(failure.message, failure.message!!.contains("reads 0 frame rates with the $DEFAULT_FRAME_RATE fps fallback, expected one"))
    }

    /** The width step hands the hook the upload's share type and its width first, and takes the answer. */
    @Test
    fun theWidthStepAsksTheHookFirst() {
        val classes = widthClasses()
        val context = PatchContexts.of(classes)
        val step = context.findWidthStep()
        context.raiseWidth(step)
        assertEquals("the step", widths to "A00", step.method.definingClass to step.method.name)
        assertEquals("the getter", "DXk", step.shareType.name)
        val method = context.mutableClassDefBy(widths).methods.single { it.name == "A00" }
        assertWidthHooked("stand-in", method, step)
        assertEquals("the call", Opcode.INVOKE_VIRTUAL, method.code()[2].opcode)
    }

    /** An upload that's an interface gets its share type through invoke-interface. */
    @Test
    fun anInterfaceUploadIsAskedThroughTheInterface() {
        val context = PatchContexts.of(widthClasses(interfaceUpload = true))
        val step = context.findWidthStep()
        context.raiseWidth(step)
        val method = context.mutableClassDefBy(widths).methods.single { it.name == "A00" }
        assertEquals("the call", Opcode.INVOKE_INTERFACE, method.code()[2].opcode)
    }

    /** A width step the patch can't read fails at patch time, saying what it found. */
    @Test
    fun aWidthStepThePatchCantReadFails() {
        val cases = listOf(
            widthClasses(second = 1280) to "doesn't load both $WIDTH_1080 and $WIDTH_1440",
            widthClasses(getter = false) to "has 0 share type getters, expected one",
            widthClasses(calls = false) to "expected one width step called from $VIDEO_INGESTION_STEP, found 0",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findWidthStep() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** The photo setup swaps the quality it's handed for the hook's answer, first thing. */
    @Test
    fun thePhotoSetupAsksTheHookFirst() {
        val context = PatchContexts.of(photoClasses())
        val setup = context.findPhotoSetup()
        context.raisePhotoQuality(setup)
        assertEquals("the setup", encoder to "A02", setup.definingClass to setup.name)
        assertPhotoHooked("stand-in", context.mutableClassDefBy(encoder).methods.single { it.name == "A02" })
    }

    /** Some builds move Bitmap.compress into a helper, and a class handing that helper the format is still the setup. */
    @Test
    fun aSetupCompressingThroughAHelperIsStillFound() {
        val context = PatchContexts.of(photoClasses(throughHelper = true))
        val setup = context.findPhotoSetup()
        assertEquals("the setup", encoder to "A02", setup.definingClass to setup.name)
    }

    /** A photo setup with nothing compressing a bitmap beside it isn't the one, and the patch says so. */
    @Test
    fun aPhotoSetupThePatchCantReadFails() {
        val context = PatchContexts.of(photoClasses(compress = false))
        val failure = assertThrows(PatchException::class.java) { context.findPhotoSetup() }
        assertTrue(failure.message, failure.message!!.contains("expected one photo setup"))
    }

    /** Every declared build has the one width step and the one photo setup, and each is hooked once, first. */
    @Test
    fun eachBuildAsksForTheWidthAndThePhotoQuality() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        val failures = mutableListOf<String>()
        for (bundle in bundles) {
            val wanted = FixtureDex.classesLoading(bundle, "image/webp").toMutableList()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val relevant = classDef.type == VIDEO_INGESTION_STEP || classDef.methods.any { method ->
                        val types = method.parameterTypes.map(CharSequence::toString)
                        val shareTypeGetter = method.returnType == SHARE_TYPE && types.isEmpty()
                        val widthShape = types.size == 8 && types[3] == "I" && types.drop(4).all { it == "Z" } && types[0] == "Landroid/content/Context;"
                        val photoShape = types.size == 4 && types[0] == "Landroid/graphics/Bitmap;" && method.returnType == classDef.type
                        shareTypeGetter || widthShape || photoShape
                    }
                    if (relevant) wanted += ImmutableClassDef.of(classDef)
                }
            }
            val classes = FixtureDex.withStringPools(bundle, wanted.distinctBy { it.type })
            val context = PatchContexts.of(classes)

            val step = context.findWidthStep()
            val setup = runCatching { context.findPhotoSetup() }.getOrElse {
                failures += "${bundle.name}: ${it.message}"
                continue
            }
            context.raiseWidth(step)
            context.raisePhotoQuality(setup)

            val everything = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            assertEquals("${bundle.name}: width hooks", 1, everything.count { m -> m.code().any { it.referenceText() == RESOLUTION_HOOK } })
            assertEquals("${bundle.name}: photo hooks", 1, everything.count { m -> m.code().any { it.referenceText() == PHOTO_QUALITY_HOOK } })
            assertWidthHooked(bundle.name, context.mutableClassDefBy(step.method.definingClass).methods.single { it.name == step.method.name && it.parameterTypes.size == 8 }, step)
            assertPhotoHooked(bundle.name, context.mutableClassDefBy(setup.definingClass).methods.single { it.name == setup.name && it.parameterTypes.size == 4 })
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The upload read, skipped when null, its share type, the width read, the call, and the answer written back to the width. */
    private fun assertWidthHooked(what: String, method: Method, step: WidthStep) {
        val code = method.code()
        val first = method.implementation!!.registerCount - method.parameterTypes.size
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == RESOLUTION_HOOK })
        assertEquals("$what: the upload", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the upload's register", first + 2, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: a null upload skips it", Opcode.IF_EQZ, code[1].opcode)
        assertEquals("$what: the getter", "${step.shareType.definingClass}->${step.shareType.name}()$SHARE_TYPE", code[2].referenceText())
        assertEquals("$what: the width's register", first + 3, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the hook", RESOLUTION_HOOK, code[5].referenceText())
        assertEquals("$what: written back", first + 3, (code[7] as TwoRegisterInstruction).registerA)
        val address = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        assertEquals("$what: null lands on Instagram's first instruction", 8, address.indexOf(address[1] + (code[1] as OffsetInstruction).codeOffset))
    }

    /** The quality read, the call, and the answer written back to the quality. */
    private fun assertPhotoHooked(what: String, method: Method) {
        val code = method.code()
        val quality = method.implementation!!.registerCount - method.parameterTypes.size + 2
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == PHOTO_QUALITY_HOOK })
        assertEquals("$what: the quality's register", quality, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the hook", PHOTO_QUALITY_HOOK, code[1].referenceText())
        assertEquals("$what: written back", quality, (code[3] as TwoRegisterInstruction).registerA)
    }

    /**
     * The hook first: the share type field read off `this` into v0, the call with it, its answer in
     * v0, a branch past the yes to Instagram's first instruction, and the yes returned. One call in all.
     */
    private fun assertHooked(what: String, method: Method, check: QualityCheck) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == FULL_QUALITY })
        assertEquals("$what: the read", Opcode.IGET_OBJECT, code[0].opcode)
        val field = (code[0] as ReferenceInstruction).reference as FieldReference
        assertEquals("$what: the field", "${check.shareType.definingClass}->${check.shareType.name}:$SHARE_TYPE", "${field.definingClass}->${field.name}:${field.type}")
        val thisRegister = method.implementation!!.registerCount - 1
        assertEquals("$what: read off this", thisRegister, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: into v0", 0, (code[0] as TwoRegisterInstruction).registerA)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[1].opcode)
        assertEquals("$what: the hook", FULL_QUALITY, code[1].referenceText())
        assertEquals("$what: what it's handed", 0, (code[1] as Instruction35c).registerC)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[2].opcode)
        assertEquals("$what: the answer's register", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[3].opcode)
        val address = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        val target = address.indexOf(address[3] + (code[3] as OffsetInstruction).codeOffset)
        assertEquals("$what: past the yes to Instagram's first instruction", HOOK_SIZE, target)
        assertEquals("$what: the yes", Opcode.CONST_4, code[4].opcode)
        assertEquals("$what: returned", Opcode.RETURN, code[5].opcode)
    }

    /**
     * The setter hands the extension the share type and the bitrate it wrote, before every return, and a
     * branch that went straight to the return now lands on the hook. Nothing before it moved.
     */
    private fun assertBitrateHooked(what: String, method: Method, check: QualityCheck, setter: BitrateSetter) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        val hooks = code.indices.filter { code[it].referenceText() == BITRATE_HOOK }
        assertTrue("$what: hooked", hooks.isNotEmpty())
        assertEquals("$what: each return of Instagram's gets a hook and its own return", hooks.size * 2, returns.size)
        for (at in hooks) {
            val start = at - 4
            assertEquals("$what: params read", Opcode.IGET_OBJECT, code[start].opcode)
            assertEquals("$what: params field", setter.holder.name, ((code[start] as ReferenceInstruction).reference as FieldReference).name)
            assertEquals("$what: share type read", Opcode.IGET_OBJECT, code[at - 1].opcode)
            assertEquals("$what: share type field", check.shareType.name, ((code[at - 1] as ReferenceInstruction).reference as FieldReference).name)
            assertEquals("$what: both fields written", 2, code.subList(at, at + 6).count { it.opcode == Opcode.IPUT })
        }
        val rates = code.indices.filter { code[it].referenceText() == FRAME_RATE_HOOK }
        assertEquals("$what: a frame rate hook for each bitrate hook", hooks.size, rates.size)
        for (at in rates) {
            assertEquals("$what: frame rate read", setter.frameRate.name, ((code[at - 2] as ReferenceInstruction).reference as FieldReference).name)
            assertEquals("$what: share type read for it", check.shareType.name, ((code[at - 1] as ReferenceInstruction).reference as FieldReference).name)
            assertEquals("$what: frame rate written", Opcode.IPUT, code[at + 3].opcode)
            assertEquals("$what: frame rate field", setter.frameRate.name, ((code[at + 3] as ReferenceInstruction).reference as FieldReference).name)
        }
        if (hooks.size == 1) {
            val start = hooks[0] - 4
            val address = code.runningFold(0) { here, instruction -> here + instruction.codeUnits }
            val branch = code.indexOfFirst { it.opcode == Opcode.IF_EQZ }
            assertTrue("$what: Instagram's own branch comes before the hook", branch in 0 until start)
            val landing = address.indexOf(address[branch] + (code[branch] as OffsetInstruction).codeOffset)
            assertEquals("$what: Instagram's branch lands on the hook", start, landing)
        }
    }

    private fun assertNothingWritten(what: String, context: BytecodePatchContext, classes: List<ClassDef>) {
        val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            .filter { method -> method.code().any { it.referenceText() == FULL_QUALITY } }
        assertTrue("$what: something was written to $written", written.isEmpty())
    }

    /**
     * Shaped like 450's: the setting's static reader, a logger that asks it too, and the transcode
     * strategy holding the share type, whose check asks the reader about it and switches on its
     * ordinal, and whose bitrate setter asks the check and gives 9,000,000.
     */
    private fun classes(
        key: String = HIGH_QUALITY_PREF,
        secondReader: Boolean = false,
        shareTypeField: Boolean = true,
        readsShareType: Boolean = true,
        secondCheck: Boolean = false,
        ordinal: Boolean = true,
        bitrate: Int = HIGH_QUALITY_BITRATE,
        loopsToStart: Boolean = false,
        locals: Int = 4,
        twoBitrateFields: Boolean = true,
        frameRateFallback: Boolean = true,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val readerBody = """
            const-string v0, "$key"
            const/4 v1, 0x0
            invoke-virtual {p0, v0, v1}, $store->getInt(Ljava/lang/String;I)I
            move-result v0
            const/4 v1, 0x1
            if-ne v0, v1, :off
            return v1
            :off
            const/4 v0, 0x0
            return v0
        """
        val readers = listOf(method(prefs, "A00", listOf(store), "Z", 2, static, readerBody)) +
            if (secondReader) listOf(method(prefs, "A01", listOf(store), "Z", 2, static, readerBody)) else emptyList()
        val field = if (shareTypeField) "$strategy->A03:$SHARE_TYPE" else "$strategy->A04:Ljava/lang/Object;"
        // `this` is copied to v3 first, so the stand-in assembles with `this` past v15 too.
        val checkBody = """
            ${if (loopsToStart) ":start" else ""}
            move-object/from16 v3, p0
            iget-object v0, v3, $strategy->A02:$store
            invoke-static {v0}, $reader
            move-result v1
            if-eqz v1, :no
            ${if (readsShareType) "iget-object v2, v3, $field" else "const/4 v2, 0x0"}
            ${if (ordinal) "invoke-virtual {v2}, Ljava/lang/Enum;->ordinal()I" else "invoke-virtual {v2}, Ljava/lang/Object;->hashCode()I"}
            move-result v2
            const/4 v0, 0x4
            if-eq v2, v0, :yes
            ${if (loopsToStart) "if-nez v2, :start" else ""}
            :no
            const/4 v1, 0x0
            return v1
            :yes
            const/4 v1, 0x1
            return v1
        """
        val checks = listOf(method(strategy, "A08", emptyList(), "Z", locals, instance, checkBody)) +
            if (secondCheck) listOf(method(strategy, "A0A", emptyList(), "Z", locals, instance, checkBody)) else emptyList()
        val setter = method(
            strategy, "A07", listOf(wrapper), "V", 3, instance,
            """
                invoke-virtual {p0}, $strategy->A08()Z
                move-result v0
                if-eqz v0, :done
                const v1, $bitrate
                iget-object v2, p1, $wrapper->A02:$params
                ${if (frameRateFallback) "iget v0, v2, $params->A02:I\n if-gtz v0, :rate\n const/16 v0, 0x1e\n :rate" else ""}
                iput v1, v2, $params->A01:I
                ${if (twoBitrateFields) "iput v1, v2, $params->A04:I" else ""}
                :done
                return-void
            """,
        )
        val shareTypeFields = listOf(
            ImmutableField(strategy, if (shareTypeField) "A03" else "A04", if (shareTypeField) SHARE_TYPE else "Ljava/lang/Object;", instance, null, null, null),
            ImmutableField(strategy, "A02", store, instance, null, null, null),
        )
        val loggerAsk = method(
            logger, "A0H", emptyList(), "Ljava/lang/Object;", 2, instance,
            """
                const/4 v0, 0x0
                invoke-static {v0}, $reader
                move-result v1
                return-object v0
            """,
        )
        return listOf(
            classDef(prefs, readers),
            classDef(strategy, checks + setter, shareTypeFields),
            classDef(logger, listOf(loggerAsk)),
        )
    }

    /** Shaped like 454's: the ingestion step calling the width step, which knows 1080 and 1440, with an upload that has a share type getter. */
    private fun widthClasses(second: Int = WIDTH_1440, getter: Boolean = true, calls: Boolean = true, interfaceUpload: Boolean = false): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val parameters = listOf("Landroid/content/Context;", "Lcom/instagram/common/session/UserSession;", upload, "I", "Z", "Z", "Z", "Z")
        val widthStep = method(
            widths, "A00", parameters, "Ljava/lang/Object;", 3, static,
            """
                const/16 v0, $WIDTH_1080
                move/from16 v1, p3
                if-eq v1, v0, :done
                const/16 v0, $second
                :done
                const/4 v2, 0x0
                return-object v2
            """,
        )
        val call = "invoke-static/range {v0 .. v7}, $widths->A00(${parameters.joinToString("")})Ljava/lang/Object;"
        val ingestion = method(
            VIDEO_INGESTION_STEP, "A01", emptyList(), "V", 8, static,
            """
                const/4 v0, 0x0
                const/4 v1, 0x0
                const/4 v2, 0x0
                const/16 v3, $WIDTH_1440
                const/4 v4, 0x0
                const/4 v5, 0x0
                const/4 v6, 0x0
                const/4 v7, 0x0
                ${if (calls) call else ""}
                return-void
            """,
        )
        val shareType = method(upload, "DXk", emptyList(), SHARE_TYPE, 1, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "const/4 v0, 0x0\n return-object v0")
        val other = method(upload, "A00", emptyList(), "Ljava/lang/Object;", 1, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "const/4 v0, 0x0\n return-object v0")
        val flags = AccessFlags.PUBLIC.value or if (interfaceUpload) AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value else AccessFlags.FINAL.value
        return listOf(
            classDef(widths, listOf(widthStep)),
            classDef(VIDEO_INGESTION_STEP, listOf(ingestion)),
            ImmutableClassDef(upload, flags, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(if (getter) shareType else other)),
        )
    }

    /** Shaped like 454's: the renderer naming both picture types and calling the setup, whose class calls Bitmap.compress. */
    private fun photoClasses(compress: Boolean = true, throughHelper: Boolean = false): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val bitmap = "Landroid/graphics/Bitmap;"
        val parameters = listOf(bitmap, store, "I", "I")
        val format = "Landroid/graphics/Bitmap\$CompressFormat;"
        val compressCall = when {
            throughHelper -> "invoke-static {v0}, LX/compressHelper;->A02($format)Z"
            compress -> "invoke-virtual {v0, v0}, $bitmap->compress(Ljava/lang/Object;)Z"
            else -> "invoke-virtual {v0, v0}, $bitmap->sameAs(Ljava/lang/Object;)Z"
        }
        val setup = method(encoder, "A02", parameters, encoder, 1, static, "const/4 v0, 0x0\n return-object v0")
        val compressing = method(
            encoder, "A05", emptyList(), "Z", 2, instance,
            """
                const/4 v0, 0x0
                $compressCall
                move-result v1
                return v1
            """,
        )
        val render = method(
            renderer, "A01", emptyList(), "V", 5, static,
            """
                const-string v0, "image/webp"
                const-string v1, "image/jpeg"
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/16 v4, 0x5f
                invoke-static {v2, v3, v4, v4}, $encoder->A02(${parameters.joinToString("")})$encoder
                return-void
            """,
        )
        return listOf(classDef(encoder, listOf(setup, compressing)), classDef(renderer, listOf(render)))
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

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    /** A static one-argument boolean method reading an int setting: what a reader of Instagram's setting looks like. */
    private fun Method.readsAnIntSetting(): Boolean = AccessFlags.STATIC.isSet(accessFlags) && returnType == "Z" &&
        parameterTypes.size == 1 && code().any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.name == "getInt" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I")
            } == true
        }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        /** The instructions the hook adds: the field read, the call, its answer, the branch, the yes and its return. */
        const val HOOK_SIZE = 6

        /** What the bitrate and frame rate hooks add before one return: their sixteen instructions, Instagram's return staying after them. */
        const val BITRATE_HOOK_SIZE = 16
    }
}
