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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
    private val sizer = "Lfixture/PhotoSizer;"
    private val reply = "Lfixture/UploadSettingsReply;"
    private val transcoder = "Lfixture/Transcoder;"
    private val transcodeUpload = "Lfixture/TranscodeUpload;"
    private val mediaSource = "Lfixture/MediaSource;"
    private val sharedSource = "Lfixture/SharedSource;"
    private val uploadNetwork = "Lfixture/UploadNetwork;"
    private val reader = "$prefs->A00($store)Z"

    /** The hook the patch writes is in the StoryUploadQuality the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_UPLOAD_QUALITY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FULL_QUALITY is not in the extension: $declared", FULL_QUALITY.substringAfter("->") in declared)
        for (hook in listOf(BITRATE_HOOK, RESOLUTION_HOOK, RENDER_WIDTH_HOOK, FRAME_RATE_HOOK, PHOTO_QUALITY_HOOK, PHOTO_TIER_HOOK, UPLOAD_SIZE_HOOK)) {
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
        assertEquals("the call", Opcode.INVOKE_VIRTUAL, method.code()[3].opcode)
        assertCapHooked("stand-in", method)
    }

    /** An upload that's an interface gets its share type through invoke-interface. */
    @Test
    fun anInterfaceUploadIsAskedThroughTheInterface() {
        val context = PatchContexts.of(widthClasses(interfaceUpload = true))
        val step = context.findWidthStep()
        context.raiseWidth(step)
        val method = context.mutableClassDefBy(widths).methods.single { it.name == "A00" }
        assertEquals("the call", Opcode.INVOKE_INTERFACE, method.code()[3].opcode)
    }

    /** A width step the patch can't read fails at patch time, saying what it found. */
    @Test
    fun aWidthStepThePatchCantReadFails() {
        val cases = listOf(
            widthClasses(second = 1280) to "doesn't load both $WIDTH_1080 and $WIDTH_1440",
            widthClasses(getter = false) to "has 0 share type getters, expected one",
            widthClasses(calls = false) to "expected one width step called from $VIDEO_INGESTION_STEP, found 0",
            widthClasses(cap = WidthCapShape.NONE) to "expected a width cap after the $WIDTH_1440 step in $widths->A00",
            widthClasses(cap = WidthCapShape.DROPPED) to "doesn't keep its width cap's answer",
            widthClasses(cap = WidthCapShape.OVER_SOURCE) to "answers over the clip's width",
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

    /** The photo size step hands the hook each of its 3072, 4096 and 8192 flags first, and takes the answers. */
    @Test
    fun thePhotoSizeStepAsksTheHookForEachSize() {
        val context = PatchContexts.of(photoSizeClasses())
        val step = context.findPhotoSizeStep()
        assertEquals("the step", sizer to "A01", step.method.definingClass to step.method.name)
        // p9, p10 and p11 past eight locals and `this`, as on 454.
        assertEquals("the flags", mapOf(3072 to 17, 4096 to 18, 8192 to 19), step.tiers)
        context.raisePhotoSize(step)
        assertPhotoSizeHooked("stand-in", context.mutableClassDefBy(sizer).methods.single { it.name == "A01" })
    }

    /** A photo size step the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aPhotoSizeStepThePatchCantReadFails() {
        val cases = listOf(
            photoSizeClasses(error = "Output size error: ") to "expected one photo size step loading \"$PHOTO_SIZE_ERROR\", found 0",
            photoSizeClasses(sizes = listOf(8192, 3072, 2048)) to "$sizer->A01 tests the 4096 px size on 0 boolean parameters, expected one",
            photoSizeClasses(sharedFlag = true) to "$sizer->A01 tests two of its sizes on one parameter",
            photoSizeClasses(loopsToStart = true) to "something jumps back to the first instruction of $sizer->A01",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.raisePhotoSize(context.findPhotoSizeStep()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = context.mutableClassDefBy(sizer).methods.filter { m -> m.code().any { it.referenceText() == PHOTO_TIER_HOOK } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** Every declared build has the one width step and the one photo setup, and each is hooked once, first. */
    @Test
    fun eachBuildAsksForTheWidthAndThePhotoQuality() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        val failures = mutableListOf<String>()
        for (bundle in bundles) {
            val wanted = (FixtureDex.classesLoading(bundle, "image/webp") + FixtureDex.classesLoading(bundle, PHOTO_SIZE_ERROR)).toMutableList()
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
            val size = runCatching { context.findPhotoSizeStep() }.getOrElse {
                failures += "${bundle.name}: ${it.message}"
                continue
            }
            context.raiseWidth(step)
            context.raisePhotoQuality(setup)
            context.raisePhotoSize(size)

            val everything = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            assertEquals("${bundle.name}: photo size hooks", listOf(PHOTO_TIERS.size), everything.map { m -> m.code().count { it.referenceText() == PHOTO_TIER_HOOK } }.filter { it > 0 })
            assertPhotoSizeHooked(bundle.name, context.mutableClassDefBy(size.method.definingClass).methods.single { it.name == size.method.name && it.returnType == size.method.returnType })
            assertEquals("${bundle.name}: width hooks", 1, everything.count { m -> m.code().any { it.referenceText() == RESOLUTION_HOOK } })
            assertEquals("${bundle.name}: width cap hooks", 1, everything.count { m -> m.code().any { it.referenceText() == RENDER_WIDTH_HOOK } })
            assertEquals("${bundle.name}: photo hooks", 1, everything.count { m -> m.code().any { it.referenceText() == PHOTO_QUALITY_HOOK } })
            val widthStep = context.mutableClassDefBy(step.method.definingClass).methods.single { it.name == step.method.name && it.parameterTypes.size == 8 }
            assertWidthHooked(bundle.name, widthStep, step)
            assertCapHooked(bundle.name, widthStep)
            assertPhotoHooked(bundle.name, context.mutableClassDefBy(setup.definingClass).methods.single { it.name == setup.name && it.parameterTypes.size == 4 })
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The server's upload settings hand the transcoder a size and frame rate the hooks answer first, and nothing else changes. */
    @Test
    fun theServersUploadSettingsAskTheHooks() {
        val classes = uploadSettingsClasses()
        val context = PatchContexts.of(classes)
        val before = classes.associate { c -> c.type to c.methods.associate { it.name to it.code().size } }

        val settings = context.findUploadSettings()
        assertEquals("the method", reply to "A01", settings.method.definingClass to settings.method.name)
        assertEquals(
            "upload, size, frame rate, scale-up and short side",
            listOf(5, 7, 9, 10, 11),
            listOf(settings.upload, settings.size, settings.frameRate, settings.expand, settings.shortSide),
        )
        assertEquals(
            "the route to the share type, past the source only a subclass of holds one",
            listOf("$transcodeUpload->A0L", "$uploadNetwork->A02"),
            listOf("${settings.holder.definingClass}->${settings.holder.name}", "${settings.shareType.definingClass}->${settings.shareType.name}"),
        )
        assertEquals("the free locals", 0 to 1, settings.scratch to settings.answer)

        context.raiseUploadSize(settings)
        assertUploadSizeHooked("stand-in", context.mutableClassDefBy(reply).methods.single { it.name == "A01" }, settings)
        for (c in classes) {
            for (method in context.mutableClassDefBy(c.type).methods) {
                val added = if (c.type == reply && method.name == "A01") UPLOAD_SIZE_HOOK_SIZE else 0
                assertEquals("${c.type}->${method.name}", before.getValue(c.type).getValue(method.name) + added, method.code().size)
            }
        }
    }

    /** Upload settings the patch can't read fail at patch time, saying what it found, before anything is written. */
    @Test
    fun uploadSettingsThePatchCantReadFail() {
        val where = "$reply->A01"
        val cases = listOf(
            uploadSettingsClasses(key = "transcode_size") to "expected one method reading \"$TRANSCODE_DIMENSION\" from the server's upload settings, found 0",
            uploadSettingsClasses(calls = 2) to "expected one transcode builder call in $where, found 2",
            uploadSettingsClasses(sizeFromReply = false) to "the size $where hands the transcode builder in v7 isn't read from the server's reply",
            uploadSettingsClasses(jumpsToCall = true) to "something jumps to the transcode builder call in $where",
            uploadSettingsClasses(holders = 0) to "$transcodeUpload holds 0 fields of a class holding a share type, expected one",
            uploadSettingsClasses(holders = 2) to "$transcodeUpload holds 2 fields of a class holding a share type, expected one",
            uploadSettingsClasses(busy = true) to "has 0 local register(s) up to v15 that nothing reads",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.raiseUploadSize(context.findUploadSettings()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = context.mutableClassDefBy(reply).methods.filter { m -> m.code().any { it.referenceText() == UPLOAD_SIZE_HOOK } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * Every declared build reads the server's transcode size in one method and hands it to one
     * transcoder call, whose upload holds one class carrying a share type, and both hooks go in right
     * before that call, once. Builds reach the upload differently (a field of `this` on 386014758, a
     * field of `this` cast to a merged class on 386014704) and 386014757's transcoder tests no source.
     */
    @Test
    fun eachBuildAsksTheHooksBeforeTheServersEncode() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val readers = FixtureDex.classesLoading(bundle, TRANSCODE_DIMENSION)
            val calls = readers.flatMap { it.methods }.flatMap { it.code() }
                .filter { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
                .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
                .filter { it.parameterTypes.size == 9 }
            val uploads = calls.map { it.parameterTypes[2].toString() }.toSet()
            val linked = FixtureDex.classes(bundle, calls.map { it.definingClass }.toSet() + uploads).values
            val held = linked.filter { it.type in uploads }.flatMap { it.fields }.map { it.type }.filter { it.startsWith("L") }.toSet()
            val holders = FixtureDex.classes(bundle, held).values
            val classes = FixtureDex.withStringPools(bundle, (readers + linked + holders).distinctBy { it.type })
            val context = PatchContexts.of(classes)

            val settings = context.findUploadSettings()
            context.raiseUploadSize(settings)
            val everything = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            assertEquals("${bundle.name}: size hooks", 1, everything.sumOf { m -> m.code().count { it.referenceText() == UPLOAD_SIZE_HOOK } })
            val parameters = settings.method.parameterTypes.map(CharSequence::toString)
            val hooked = context.mutableClassDefBy(settings.method.definingClass).methods
                .single { it.name == settings.method.name && it.parameterTypes.map(CharSequence::toString) == parameters }
            assertUploadSizeHooked(bundle.name, hooked, settings)
        }
    }

    /**
     * Right before the transcoder call: the upload it's handed, its network settings, and their share
     * type, or null when it has none; the size hook with that, the size and the short side flag, its
     * answer replacing the size and turning the scale-up off only when it differs; then the frame rate
     * hook's answer replacing the frame rate.
     */
    private fun assertUploadSizeHooked(what: String, method: Method, settings: UploadSettings) {
        val code = method.code()
        val at = code.indexOfFirst { it.referenceText() == UPLOAD_SIZE_HOOK }
        val start = at - 4
        assertTrue("$what: hooked", start >= 0)
        fun field(index: Int) = ((code[index] as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}" }
        val address = code.runningFold(0) { here, instruction -> here + instruction.codeUnits }
        fun landing(index: Int) = address.indexOf(address[index] + (code[index] as OffsetInstruction).codeOffset)

        assertEquals("$what: the upload", Opcode.MOVE_OBJECT_FROM16, code[start].opcode)
        assertEquals(
            "$what: copied from the call's upload into the scratch register",
            settings.upload to settings.scratch,
            (code[start] as TwoRegisterInstruction).let { it.registerB to it.registerA },
        )
        assertEquals("$what: the call hands that register as the upload", settings.upload, (code[at + 7] as RegisterRangeInstruction).startRegister + 3)
        assertEquals(
            "$what: the route to the share type",
            listOf("${settings.holder.definingClass}->${settings.holder.name}", "${settings.shareType.definingClass}->${settings.shareType.name}"),
            listOf(field(start + 1), field(start + 3)),
        )
        assertEquals("$what: no network settings hands the hook null", at, landing(start + 2))

        val ask = code[at] as Instruction35c
        assertEquals("$what: the size hook's arguments", listOf(settings.scratch, settings.size, settings.shortSide), listOf(ask.registerC, ask.registerD, ask.registerE))
        assertEquals("$what: the same size changes nothing", at + 5, landing(at + 2))
        assertEquals("$what: the answer replaces the size", settings.size to settings.answer, (code[at + 3] as TwoRegisterInstruction).let { it.registerA to it.registerB })
        assertEquals("$what: and turns the scale-up off", settings.expand, (code[at + 4] as OneRegisterInstruction).registerA)
        val rate = code[at + 5] as Instruction35c
        assertEquals("$what: the frame rate hook", FRAME_RATE_HOOK, rate.referenceText())
        assertEquals("$what: its arguments", listOf(settings.scratch, settings.frameRate), listOf(rate.registerC, rate.registerD))
        assertEquals("$what: its answer replaces the frame rate", settings.frameRate, (code[at + 6] as OneRegisterInstruction).registerA)
        assertEquals("$what: then the transcoder call", Opcode.INVOKE_VIRTUAL_RANGE, code[at + 7].opcode)
        assertEquals("$what: handed the size", settings.size, (code[at + 7] as RegisterRangeInstruction).startRegister + 5)
    }

    /**
     * A null share type, the upload read, its getter skipped when it's null, the width read, the call
     * with whichever share type, and the answer written back to the width.
     */
    private fun assertWidthHooked(what: String, method: Method, step: WidthStep) {
        val code = method.code()
        val first = method.implementation!!.registerCount - method.parameterTypes.size
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == RESOLUTION_HOOK })
        assertEquals("$what: no share type yet", Opcode.CONST_4, code[0].opcode)
        assertEquals("$what: the upload", Opcode.MOVE_OBJECT_FROM16, code[1].opcode)
        assertEquals("$what: the upload's register", first + 2, (code[1] as TwoRegisterInstruction).registerB)
        assertEquals("$what: a null upload skips the getter", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the getter", "${step.shareType.definingClass}->${step.shareType.name}()$SHARE_TYPE", code[3].referenceText())
        assertEquals("$what: the width's register", first + 3, (code[5] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the hook", RESOLUTION_HOOK, code[6].referenceText())
        assertEquals("$what: written back", first + 3, (code[8] as TwoRegisterInstruction).registerA)
        val address = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        assertEquals("$what: a null upload still asks the hook", 5, address.indexOf(address[2] + (code[2] as OffsetInstruction).codeOffset))
    }

    /** Right after the width cap's answer is moved: the call with that answer and the clip's width it was handed, and the answer taken. */
    private fun assertCapHooked(what: String, method: Method) {
        val code = method.code()
        assertEquals("$what: cap hooks", 1, code.count { it.referenceText() == RENDER_WIDTH_HOOK })
        val at = code.indexOfFirst { it.referenceText() == RENDER_WIDTH_HOOK }
        val cap = code[at - 2] as Instruction35c
        val width = (code[at - 1] as OneRegisterInstruction).registerA
        val step = code.indexOfFirst { (it as? NarrowLiteralInstruction)?.narrowLiteral == WIDTH_1440 }
        assertTrue("$what: the cap after the $WIDTH_1440 step, not the landscape one", step in 0 until at - 2)
        assertEquals("$what: the cap", listOf("Landroid/content/Context;", "I", "Z", "Z", "Z"), (cap.reference as MethodReference).parameterTypes.map(CharSequence::toString))
        assertEquals("$what: the cap's answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        val hook = code[at] as Instruction35c
        assertEquals("$what: the hook's arguments", listOf(width, cap.registerD), listOf(hook.registerC, hook.registerD))
        assertEquals("$what: the answer taken", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: into the width", width, (code[at + 1] as OneRegisterInstruction).registerA)
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
     * For each of [PHOTO_TIERS] in turn: the size, its flag read, the call with both, and the answer written
     * back to the flag, where the flag is the parameter Instagram's own code branches on right before
     * loading that size.
     */
    private fun assertPhotoSizeHooked(what: String, method: Method) {
        val code = method.code()
        val stock = code.drop(PHOTO_TIERS.size * 5)
        assertTrue("$what: nothing else calls the hook", stock.none { it.referenceText() == PHOTO_TIER_HOOK })
        for ((i, tier) in PHOTO_TIERS.withIndex()) {
            val at = i * 5
            val load = stock.indexOfFirst { (it as? NarrowLiteralInstruction)?.narrowLiteral == tier && it.opcode == Opcode.CONST_16 }
            assertTrue("$what: Instagram loads $tier", load > 0)
            assertEquals("$what: Instagram branches on a flag before $tier", Opcode.IF_EQZ, stock[load - 1].opcode)
            val flag = (stock[load - 1] as OneRegisterInstruction).registerA
            assertEquals("$what: the size", tier, (code[at] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$what: into v0", 0, (code[at] as OneRegisterInstruction).registerA)
            assertEquals("$what: the $tier flag read", flag, (code[at + 1] as TwoRegisterInstruction).registerB)
            val call = code[at + 2] as Instruction35c
            assertEquals("$what: the hook", PHOTO_TIER_HOOK, call.referenceText())
            assertEquals("$what: the hook's arguments", listOf(0, 1), listOf(call.registerC, call.registerD))
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at + 3].opcode)
            assertEquals("$what: written back to the $tier flag", flag, (code[at + 4] as TwoRegisterInstruction).registerA)
        }
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

    private enum class WidthCapShape { CALLED, NONE, DROPPED, OVER_SOURCE }

    /**
     * Shaped like 454's: the ingestion step calling the width step, which caps a landscape width first,
     * then knows 1080 and 1440 and caps the width it settles on, with an upload that has a share type getter.
     */
    private fun widthClasses(
        second: Int = WIDTH_1440,
        getter: Boolean = true,
        calls: Boolean = true,
        interfaceUpload: Boolean = false,
        cap: WidthCapShape = WidthCapShape.CALLED,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val parameters = listOf("Landroid/content/Context;", "Lcom/instagram/common/session/UserSession;", upload, "I", "Z", "Z", "Z", "Z")
        val capCall = "invoke-static {p0, v1, v2, v2, v2}, $widths->A01(Landroid/content/Context;IZZZ)I"
        val capped = when (cap) {
            WidthCapShape.CALLED -> "$capCall\n move-result v0"
            WidthCapShape.NONE -> ""
            WidthCapShape.DROPPED -> capCall
            WidthCapShape.OVER_SOURCE -> "$capCall\n move-result v1"
        }
        val widthStep = method(
            widths, "A00", parameters, "Ljava/lang/Object;", 3, static,
            """
                const/4 v1, 0x0
                const/4 v2, 0x0
                $capCall
                move-result v0
                mul-int/lit8 v0, v0, 0x10
                const/16 v0, $WIDTH_1080
                move/from16 v1, p3
                if-eq v1, v0, :done
                const/16 v0, $second
                :done
                const/4 v2, 0x0
                $capped
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

    /**
     * Shaped like 454's `0D3B.A01`: an instance method taking a point, the session, two ints and seven
     * booleans, eight locals so its flags sit past v15, loading the aspect error, and testing each size
     * after branching past it on its own flag, the largest first.
     */
    private fun photoSizeClasses(
        error: String = PHOTO_SIZE_ERROR,
        sizes: List<Int> = listOf(8192, 4096, 3072, 2048),
        sharedFlag: Boolean = false,
        loopsToStart: Boolean = false,
    ): List<ClassDef> {
        val point = "Landroid/graphics/Point;"
        val parameters = listOf(point, "Lcom/instagram/common/session/UserSession;", "I", "I") + List(7) { "Z" }
        val flags = mapOf(8192 to "p11", 4096 to "p10", 3072 to if (sharedFlag) "p10" else "p9", 2048 to "p8")
        val tests = sizes.joinToString("\n") { size ->
            """
                if-eqz ${flags.getValue(size)}, :past$size
                const/16 v1, $size
                invoke-static {v2, v3, v1}, $sizer->A00(III)Z
                move-result v4
                :past$size
            """
        }
        val step = method(
            sizer, "A01", parameters, point, 8, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            """
                ${if (loopsToStart) ":start" else ""}
                const-string v0, "$error"
                move/from16 v2, p3
                move/from16 v3, p4
                $tests
                ${if (loopsToStart) "if-nez v4, :start" else ""}
                const/4 v0, 0x0
                return-object v0
            """,
        )
        val test = method(sizer, "A00", listOf("I", "I", "I"), "Z", 1, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, "const/4 v0, 0x0\n return v0")
        return listOf(classDef(sizer, listOf(test, step)))
    }

    /**
     * Shaped like 454's `0K64.A01`: twelve locals and `this` past them, the server's transcode size read
     * with optInt into the register its range call to the transcoder hands the size in, and the upload
     * from a field of `this`. The upload holds its network settings (`0Wua` on 454), whose class
     * declares the share type, and a media source only one subclass of declares one, which the
     * transcoder tests for and the patch passes over.
     */
    private fun uploadSettingsClasses(
        key: String = TRANSCODE_DIMENSION,
        calls: Int = 1,
        sizeFromReply: Boolean = true,
        holders: Int = 1,
        jumpsToCall: Boolean = false,
        busy: Boolean = false,
    ): List<ClassDef> {
        val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val picked = "Lfixture/PickedSource;"
        val otherNetwork = "Lfixture/OtherNetwork;"
        val parameters = listOf("Lfixture/Composer;", "Landroid/media/MediaFormat;", transcodeUpload, "F", "I", "I", "I", "Z", "Z")
        val call = "invoke-virtual/range {v2 .. v11}, $transcoder->A00(${parameters.joinToString("")})Ljava/util/ArrayList;"
        val optInt = "invoke-virtual {v1, v0}, Lorg/json/JSONObject;->optInt(Ljava/lang/String;)I"
        val settings = method(
            reply, "A01", listOf("Lorg/json/JSONObject;"), "V", 12, instance,
            """
                const-string v0, "$key"
                move-object/from16 v1, p1
                $optInt
                move-result v7
                ${if (sizeFromReply) "" else "const/16 v7, 0x438"}
                move-object/from16 v2, p0
                iget-object v2, v2, $reply->A00:$transcoder
                const/4 v3, 0x0
                const/4 v4, 0x0
                move-object/from16 v5, p0
                iget-object v5, v5, $reply->A03:$transcodeUpload
                const/4 v6, 0x0
                const v8, 0x7a1200
                const/16 v9, 0x1e
                const/4 v10, 0x1
                ${if (jumpsToCall) "if-eqz v10, :call" else ""}
                const/4 v11, 0x1
                ${if (jumpsToCall) ":call" else ""}
                ${List(calls) { call }.joinToString("\n")}
                ${if (busy) optInt else ""}
                return-void
            """,
        )
        val builder = method(
            transcoder, "A00", parameters, "Ljava/util/ArrayList;", 2, instance,
            """
                iget-object v0, p3, $transcodeUpload->A02:$mediaSource
                instance-of v1, v0, $picked
                instance-of v1, v0, $sharedSource
                const/4 v0, 0x0
                return-object v0
            """,
        )
        fun field(owner: String, name: String, type: String) = ImmutableField(owner, name, type, instance, null, null, null)
        fun source(type: String, fields: List<ImmutableField>) =
            ImmutableClassDef(type, instance, mediaSource, null, null, null, fields, emptyList())
        val networks = listOf("A0L" to uploadNetwork, "A0M" to otherNetwork).take(holders)
        return listOf(
            classDef(reply, listOf(settings), listOf(field(reply, "A00", transcoder), field(reply, "A03", transcodeUpload))),
            classDef(transcoder, listOf(builder)),
            classDef(
                transcodeUpload, emptyList(),
                listOf(field(transcodeUpload, "A00", "Ljava/lang/String;"), field(transcodeUpload, "A02", mediaSource)) +
                    networks.map { (name, type) -> field(transcodeUpload, name, type) },
            ),
            ImmutableClassDef(mediaSource, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;", null, null, null, emptyList(), emptyList()),
            source(picked, listOf(field(picked, "A00", "Ljava/lang/String;"))),
            source(sharedSource, listOf(field(sharedSource, "A00", "Ljava/lang/String;"), field(sharedSource, "A02", SHARE_TYPE))),
            classDef(uploadNetwork, emptyList(), listOf(field(uploadNetwork, "A01", "Ljava/lang/String;"), field(uploadNetwork, "A02", SHARE_TYPE))),
            classDef(otherNetwork, emptyList(), listOf(field(otherNetwork, "A00", SHARE_TYPE))),
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

        /** What the size and frame rate hooks add before the transcoder call: the share type's four, then the two calls and the five around them. */
        const val UPLOAD_SIZE_HOOK_SIZE = 11
    }
}
