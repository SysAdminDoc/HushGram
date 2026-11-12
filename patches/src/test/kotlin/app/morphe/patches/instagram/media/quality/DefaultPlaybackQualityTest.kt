/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.media.quality

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Default playback quality's anchors: the one (String)V call in the method holding
 * "HeroServicePlayer.setCustomQualityInternal", on a DASH format evaluator built with an
 * AbrContextAwareConfiguration; that setter's one array of tracks, its one way of reading a track's
 * label and the one String field it keeps the found track's id in; and the one place the evaluator's
 * choice of a track keeps its tracks, once. Then the patch itself: the extension told right after
 * that write with the evaluator it wrote to, a branch landing after the write still skipping it,
 * and each stub reading or calling what it stands for. On stand-ins shaped like 449's, and on every
 * Instagram build the bundle declares, read from HUSHGRAM_FIXTURE_DIR.
 */
class DefaultPlaybackQualityTest {
    private val hero = "Lfixture/HeroServicePlayer;"
    private val evaluator = "Lfixture/Evaluator;"
    private val format = "Lfixture/Format;"
    private val info = "Lfixture/FormatInfo;"
    private val string = "Ljava/lang/String;"
    private val objectType = "Ljava/lang/Object;"
    private val tracks = "[$format"

    /** The hook and every stub the patch fills are in the extension the bundle ships, public or package static. */
    @Test
    fun theHookAndStubsAreInTheExtension() {
        val hooks = ExtensionDex.classDef(QUALITY_CHOICE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FIRST_CHOICE is not in the extension: $hooks", FIRST_CHOICE in hooks)
        assertTrue("$EACH_CHOICE is not in the extension: $hooks", EACH_CHOICE in hooks)
        val stubs = ExtensionDex.classDef(QUALITY_READER).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (stub in listOf(
            "$CUSTOM_TRACK_STUB($objectType)$string",
            "$FORMATS_STUB($objectType)[$objectType",
            "$LABEL_STUB($objectType)$string",
            "$SETTER_STUB($objectType$string)V",
        )) {
            assertTrue("the stub $stub is not in the extension: $stubs", stub in stubs)
        }
    }

    /**
     * The whole patch on stand-ins: the hook right after the one write of the tracks, handing over
     * the evaluator it wrote to, a branch that landed after the write still landing past the hook;
     * the hook on every choice first, handing over every parameter register, a branch back to the
     * top still landing past it; each stub filled, and the patch's status switched on.
     */
    @Test
    fun theHookGoesRightAfterTheTracksAreFirstKept() {
        val context = PatchContexts.of(classes() + ExtensionDex.classDef(SETTINGS_STATUS))
        defaultPlaybackQualityPatch.execute(context)

        val choice = context.method(evaluator, "BHS").code()
        val write = choice.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.referenceText() == "$evaluator->A0J:$tracks" }
        val hook = choice[write + 1]
        assertEquals("the hook right after the write", FIRST_CHOICE, hook.referenceText())
        assertEquals("the evaluator the write went to", listOf(2, 1), (hook as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("one hook", 1, choice.count { it.referenceText() == FIRST_CHOICE })
        val landings = landings(choice)
        assertTrue("a branch lands on the hook", write + 1 !in landings)
        assertTrue("the branch past the write no longer lands after the hook", write + 2 in landings)

        assertEquals("the hook on every choice first", EACH_CHOICE, choice[0].referenceText())
        assertEquals("every parameter register, the evaluator first", listOf(3, 16),
            (choice[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("one hook on every choice", 1, choice.count { it.referenceText() == EACH_CHOICE })
        assertTrue("a branch lands on the hook on every choice", 0 !in landings)
        assertTrue("the branch back to the top no longer lands after the hook", 1 in landings)

        assertEquals(listOf(evaluator, "$evaluator->A0I:$string"), filled(context, CUSTOM_TRACK_STUB))
        assertEquals(listOf(evaluator, "$evaluator->A0J:$tracks"), filled(context, FORMATS_STUB))
        assertEquals(listOf(format, "$info->A00($format)$info", "$info->A0A:$string"), filled(context, LABEL_STUB))
        assertEquals(listOf(evaluator, "$evaluator->A05($string)V"), filled(context, SETTER_STUB))

        val status = context.method(SETTINGS_STATUS, "defaultPlaybackQuality").code()
        assertEquals("SettingsStatus.defaultPlaybackQuality() isn't switched on", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** Each build the patch can't hook safely fails at patch time with what it found, before anything is written. */
    @Test
    fun anEvaluatorThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape(heroString = false) to "expected one method holding \"$SET_CUSTOM_QUALITY\", found 0",
            Shape(secondSetterCall = true) to "expected one (String)V call",
            Shape(abrConfiguration = false) to "takes no $ABR_CONFIGURATION",
            Shape(secondArray = true) to "to read one array of tracks, found 2",
            Shape(secondKeep = true) to "to keep a track's id in one String field, found 2",
            Shape(secondWrite = true) to "in one place, found 2",
            Shape(guarded = false) to "without checking it's still empty",
            Shape(readsKept = false) to "never reads A0I",
            Shape(publicKept = false) to "$evaluator->A0I:$string isn't public",
            Shape(publicInfo = false) to "$info isn't public",
            Shape(choiceTakes = false) to "->BHS takes ($objectType${tracks}J), not eight objects",
        )
        for ((shape, expected) in cases) {
            val context = PatchContexts.of(classes(shape) + ExtensionDex.classDef(SETTINGS_STATUS))
            val failure = assertThrows(PatchException::class.java) { defaultPlaybackQualityPatch.execute(context) }
            assertTrue("$shape: ${failure.message}", failure.message!!.startsWith(PATCH) && failure.message!!.contains(expected))
            assertUntouched(context)
        }
    }

    /**
     * In each declared build: the anchors are each found once, the evaluator's setter is called only
     * by the Hero player's method, the tracks are written only by the choice and the kept id only by
     * the setter, anywhere in the APK. Then the patch goes in on those classes as it does on the
     * stand-ins.
     */
    @Test
    fun eachDeclaredBuildKeepsItsFirstChoice() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildKeepsItsFirstChoiceToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val name = label
        val holders = FixtureDex.classesHolding(bundle, SET_CUSTOM_QUALITY)
        val holding = holders.flatMap { it.methods }.filter { holdsString(it, SET_CUSTOM_QUALITY) }
        assertEquals("$name: methods holding \"$SET_CUSTOM_QUALITY\"", 1, holding.size)
        val call = customQualityCalls(holding.single()).single()
        val evaluatorClass = FixtureDex.classes(bundle, setOf(call.definingClass)).values.single()
        val setter = evaluatorClass.methods.single { it.name == call.name && it.parameterTypes.map(Any::toString) == listOf(string) }
        val formatsField = formatsFields(setter).single()
        val formatType = formatsField.type.removePrefix("[")
        val (labelOf, label) = labelReads(setter, formatType).single()
        val classes = (holders + evaluatorClass + FixtureDex.classes(bundle, setOf(formatType, labelOf.definingClass, label.definingClass)).values +
            ExtensionDex.classDef(QUALITY_CHOICE) + ExtensionDex.classDef(QUALITY_READER) + ExtensionDex.classDef(SETTINGS_STATUS))
            .distinctBy { it.type }
        val context = PatchContexts.of(classes)
        val anchors = context.findQualityAnchors()
        assertEquals("$name: the evaluator", call.definingClass, anchors.evaluator.type)
        assertTrue("$name: the evaluator isn't built with an $ABR_CONFIGURATION", takesAbrConfiguration(anchors.evaluator))

        // Only the Hero player's method calls the setter, so Instagram has no menu of its
        // own that picks a quality; only the choice writes the tracks; only the setter
        // keeps a track.
        val callers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.methodSection.any { it.definingClass == anchors.evaluator.type && it.name == setter.name }
        }) { method -> method.code().any { (it.reference() as? MethodReference)?.let { c -> c.definingClass == anchors.evaluator.type && c.name == setter.name } == true } }
        assertEquals("$name: callers of ${setter.name}", setOf("${holding.single().definingClass}->${holding.single().name}"),
            callers.map { "${it.definingClass}->${it.name}" }.toSet())
        fun writers(field: FieldReference) = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.fieldSection.any { it.definingClass == field.definingClass && it.name == field.name }
        }) { method -> method.code().any { it.opcode == Opcode.IPUT_OBJECT && it.referenceText() == field.toString() } }
            .map { "${it.definingClass}->${it.name}" }.toSet()
        assertEquals("$name: writers of ${anchors.formats.name}", setOf("${anchors.evaluator.type}->${anchors.firstChoice.name}"), writers(anchors.formats))
        assertEquals("$name: writers of ${anchors.customTrack.name}", setOf("${anchors.evaluator.type}->${setter.name}"), writers(anchors.customTrack))

        defaultPlaybackQualityPatch.execute(context)

        val original = anchors.firstChoice.code()
        val choice = context.method(anchors.evaluator.type, anchors.firstChoice.name).code()
        assertEquals("$name: the choice's own code follows", original.size + 2, choice.size)
        // The hook on every choice went in at the top, after the first hook, so that one sits a line later.
        val hookAt = anchors.hookAt + 1
        val hook = choice[hookAt]
        assertEquals("$name: the write right before the hook", Opcode.IPUT_OBJECT to anchors.formats.toString(),
            choice[hookAt - 1].opcode to choice[hookAt - 1].referenceText())
        assertEquals("$name: the hook", FIRST_CHOICE, hook.referenceText())
        assertEquals("$name: the evaluator the write went to", (choice[hookAt - 1] as TwoRegisterInstruction).registerB,
            (hook as RegisterRangeInstruction).startRegister)
        assertTrue("$name: a branch lands on the hook", hookAt !in landings(choice))

        val taken = anchors.firstChoice.parameterTypes.map(Any::toString)
        assertEquals("$name: the choice's parameters", listOf("F", "J", "J", "J"), taken.takeLast(4))
        val words = 1 + taken.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val registers = anchors.firstChoice.implementation!!.registerCount
        assertEquals("$name: the hook on every choice first", EACH_CHOICE, choice[0].referenceText())
        assertEquals("$name: every parameter register, the evaluator first", listOf(registers - words, words),
            (choice[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertTrue("$name: a branch lands on the hook on every choice", 0 !in landings(choice))

        assertEquals("$name: the kept track stub", listOf(anchors.evaluator.type, anchors.customTrack.toString()), filled(context, CUSTOM_TRACK_STUB))
        assertEquals("$name: the tracks stub", listOf(anchors.evaluator.type, anchors.formats.toString()), filled(context, FORMATS_STUB))
        assertEquals("$name: the label stub", listOf(formatType, labelOf.toString(), label.toString()), filled(context, LABEL_STUB))
        assertEquals("$name: the setter stub", listOf(anchors.evaluator.type, "${anchors.evaluator.type}->${setter.name}($string)V"), filled(context, SETTER_STUB))
    }

    private fun assertUntouched(context: BytecodePatchContext) {
        context.classDefByOrNull(evaluator)?.let { owner ->
            owner.methods.forEach { method ->
                assertTrue("$evaluator->${method.name} changed", method.code().none { it.referenceText() == FIRST_CHOICE || it.referenceText() == EACH_CHOICE })
            }
        }
        val reader = context.classDefBy(QUALITY_READER)
        val original = ExtensionDex.classDef(QUALITY_READER)
        for (stub in listOf(CUSTOM_TRACK_STUB, FORMATS_STUB, LABEL_STUB, SETTER_STUB)) {
            assertEquals("the stub $stub was filled", original.methods.single { it.name == stub }.code().map { it.opcode },
                reader.methods.single { it.name == stub }.code().map { it.opcode })
        }
        val status = context.method(SETTINGS_STATUS, "defaultPlaybackQuality").code()
        assertEquals("SettingsStatus.defaultPlaybackQuality() was switched on", 0, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** What the filled stub [name] names before its first return. Its own body stays behind it, unreached. */
    private fun filled(context: BytecodePatchContext, name: String): List<String> =
        context.method(QUALITY_READER, name).code()
            .takeWhile { it.opcode != Opcode.RETURN_OBJECT && it.opcode != Opcode.RETURN_VOID }
            .mapNotNull { instruction ->
                when (val reference = instruction.reference()) {
                    is TypeReference -> reference.type
                    null -> null
                    else -> reference.toString()
                }
            }

    /** Every index a branch or a switch case of [code] lands on. */
    private fun landings(code: List<Instruction>): List<Int> {
        val address = IntArray(code.size + 1)
        code.forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        fun at(unit: Int) = address.indexOf(unit)
        return code.indices.flatMap { i ->
            val instruction = code[i]
            when {
                instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH -> {
                    val payload = code[at(address[i] + (instruction as OffsetInstruction).codeOffset)] as SwitchPayload
                    payload.switchElements.map { at(address[i] + it.offset) }
                }
                instruction.opcode == Opcode.FILL_ARRAY_DATA -> emptyList()
                instruction is OffsetInstruction -> listOf(at(address[i] + instruction.codeOffset))
                else -> emptyList()
            }
        }
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /** What differs from 449's shape in one stand-in build. */
    private data class Shape(
        val heroString: Boolean = true,
        val secondSetterCall: Boolean = false,
        val abrConfiguration: Boolean = true,
        val secondArray: Boolean = false,
        val secondKeep: Boolean = false,
        val secondWrite: Boolean = false,
        val guarded: Boolean = true,
        val readsKept: Boolean = true,
        val publicKept: Boolean = true,
        val publicInfo: Boolean = true,
        val choiceTakes: Boolean = true,
    )

    private fun classes(shape: Shape = Shape()): List<ClassDef> {
        val heroClass = classDef(
            hero,
            listOf(
                // The Hero player's setCustomQualityInternal: (String)V, two locals.
                method(hero, "A11", listOf(string), "V", 4, static = false, body = """
                    ${if (shape.heroString) "const-string v1, \"$SET_CUSTOM_QUALITY\"" else "const-string v1, \"HeroServicePlayer.other\""}
                    invoke-virtual { p0 }, $hero->BsG()$evaluator
                    move-result-object v0
                    if-eqz v0, :done
                    invoke-virtual { v0, p1 }, $evaluator->A05($string)V
                    ${if (shape.secondSetterCall) "invoke-virtual { v0, p1 }, $evaluator->A06($string)V" else ""}
                    :done
                    return-void
                """),
            ),
        )
        val abr = if (shape.abrConfiguration) ABR_CONFIGURATION else "Lcom/facebook/exoplayer/Other;"
        val keptFlags = if (shape.publicKept) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value
        val evaluatorClass = classDef(
            evaluator,
            listOfNotNull(
                method(evaluator, "<init>", listOf("Landroid/content/Context;", abr), "V", 3, static = false, body = """
                    invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                    return-void
                """).withFlags(AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value),
                // The custom-quality setter: looks the label up among the tracks and keeps the
                // matching track's id, or null. Six locals.
                method(evaluator, "A05", listOf(string), "V", 8, static = false, body = """
                    const/4 v5, 0x0
                    if-eqz p1, :store
                    iget-object v0, p0, $evaluator->A0J:$tracks
                    ${if (shape.secondArray) "iget-object v0, p0, $evaluator->A0K:$tracks" else ""}
                    if-eqz v0, :store
                    array-length v3, v0
                    const/4 v2, 0x0
                    :loop
                    if-ge v2, v3, :store
                    aget-object v1, v0, v2
                    invoke-static { v1 }, $info->A00($format)$info
                    move-result-object v4
                    iget-object v4, v4, $info->A0A:$string
                    invoke-virtual { p1, v4 }, $string->equals($objectType)Z
                    move-result v4
                    if-eqz v4, :next
                    iget-object v5, v1, $format->A0Y:$string
                    goto :store
                    :next
                    add-int/lit8 v2, v2, 0x1
                    goto :loop
                    :store
                    iput-object v5, p0, $evaluator->A0I:$string
                    ${if (shape.secondKeep) "iput-object v5, p0, $evaluator->A0L:$string" else ""}
                    return-void
                """),
                // The choice of a track: eight objects, the second the tracks, then a float and
                // three longs, as 454's; three locals. A branch from its first line lands right
                // after the write, and must still land after the hook. One from its end goes back
                // to its first line, and must still land after the hook on every choice.
                method(evaluator, "BHS", choiceParameters(shape), "V", if (shape.choiceTakes) 19 else 8, static = false, body = """
                    :top
                    move-object v2, p0
                    move-object v1, p2
                    if-eqz p1, :after
                    ${if (shape.guarded) "iget-object v0, v2, $evaluator->A0J:$tracks" else "const/4 v0, 0x0"}
                    ${if (shape.guarded) "if-nez v0, :kept" else "nop"}
                    iput-object v1, v2, $evaluator->A0J:$tracks
                    :after
                    const/4 v0, 0x1
                    :kept
                    ${if (shape.readsKept) "iget-object v0, v2, $evaluator->A0I:$string" else "const/4 v0, 0x0"}
                    if-eqz v0, :top
                    return-void
                """),
                if (shape.secondWrite) method(evaluator, "A04", listOf(tracks), "V", 2, static = false, body = """
                    iput-object p1, p0, $evaluator->A0J:$tracks
                    return-void
                """) else null,
            ),
            listOf(
                ImmutableField(evaluator, "A0J", tracks, AccessFlags.PUBLIC.value or AccessFlags.VOLATILE.value, null, null, null),
                ImmutableField(evaluator, "A0K", tracks, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(evaluator, "A0I", string, keptFlags or AccessFlags.VOLATILE.value, null, null, null),
                ImmutableField(evaluator, "A0L", string, AccessFlags.PUBLIC.value, null, null, null),
            ),
        )
        val formatClass = classDef(
            format, emptyList(),
            listOf(ImmutableField(format, "A0Y", string, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
        )
        val infoClass = classDef(
            info,
            listOf(
                method(info, "A00", listOf(format), info, 2, static = true, body = """
                    const/4 v0, 0x0
                    return-object v0
                """),
            ),
            listOf(ImmutableField(info, "A0A", string, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
            if (shape.publicInfo) AccessFlags.PUBLIC.value or AccessFlags.FINAL.value else AccessFlags.FINAL.value,
        )
        return listOf(heroClass, evaluatorClass, formatClass, infoClass, ExtensionDex.classDef(QUALITY_CHOICE), ExtensionDex.classDef(QUALITY_READER))
    }

    /** 454's (eight objects, F, J, J, J), or 449's (Object, tracks, long) for a build the hook on every choice can't take. */
    private fun choiceParameters(shape: Shape): List<String> =
        if (shape.choiceTakes) listOf(objectType, tracks) + List(6) { objectType } + listOf("F", "J", "J", "J")
        else listOf(objectType, tracks, "J")

    private fun Method.withFlags(flags: Int): Method =
        ImmutableMethod(definingClass, name, parameters, returnType, flags, null, null, implementation)

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(), flags: Int = AccessFlags.PUBLIC.value): ClassDef =
        ImmutableClassDef(type, flags, "Ljava/lang/Object;", null, null, null, fields, methods)
}
