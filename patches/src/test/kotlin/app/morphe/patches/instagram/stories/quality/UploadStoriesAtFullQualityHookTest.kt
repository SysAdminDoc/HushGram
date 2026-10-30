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
    private val reader = "$prefs->A00($store)Z"

    /** The hook the patch writes is in the StoryUploadQuality the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_UPLOAD_QUALITY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FULL_QUALITY is not in the extension: $declared", FULL_QUALITY.substringAfter("->") in declared)
    }

    /** The check asks the extension first and returns its yes, and nothing else changes. */
    @Test
    fun theCheckAsksTheExtensionFirst() {
        val classes = classes()
        val context = PatchContexts.of(classes)
        val before = classes.associate { c -> c.type to c.methods.associate { it.name to it.code().size } }

        val check = context.findStoryUploadQuality()
        context.raiseStoryUploadQuality(check)

        assertEquals("the check", strategy to "A08", check.type to check.name)
        assertEquals("the share type field", "A03", check.shareType.name)
        assertHooked("stand-in", context.mutableClassDefBy(strategy).methods.single { it.name == "A08" }, check)
        for (c in classes) {
            for (method in context.mutableClassDefBy(c.type).methods) {
                val added = if (c.type == strategy && method.name == "A08") HOOK_SIZE else 0
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
            context.raiseStoryUploadQuality(check)

            assertTrue("${bundle.name}: the reader $pref", pref.parameterTypes.size == 1 && pref.returnType == "Z")
            val patched = context.mutableClassDefBy(check.type).methods.single { it.name == check.name && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            assertHooked(bundle.name, patched, check)
            val elsewhere = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method ->
                    !(method.definingClass == check.type && method.name == check.name && method.parameterTypes.isEmpty()) &&
                        method.code().any { it.referenceText() == FULL_QUALITY }
                }
            assertTrue("${bundle.name}: hooked elsewhere too: $elsewhere", elsewhere.isEmpty())
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
            strategy, "A07", emptyList(), "V", 2, instance,
            """
                invoke-virtual {p0}, $strategy->A08()Z
                move-result v0
                if-eqz v0, :done
                const v1, $bitrate
                iput v1, p0, $strategy->A05:I
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
    }
}
