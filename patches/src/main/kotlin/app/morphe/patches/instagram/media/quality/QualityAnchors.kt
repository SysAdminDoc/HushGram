/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.media.quality

import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Default playback quality hooks, found by kept names and shapes only (read from Instagram
 * 449, 2026-09-30). The obfuscated names in these comments are for reviewers; the code never
 * writes one down.
 *
 * - Instagram plays through the same Hero player as Facebook. The one method holding
 *   "HeroServicePlayer.setCustomQualityInternal" (449 LX/01Pf;->A11) hands a label to its DASH
 *   format evaluator's custom-quality setter, the one (String)V call there (LX/06xi;->A05). The
 *   evaluator's one constructor takes an AbrContextAwareConfiguration, a kept name.
 * - The setter looks for the label among the evaluator's tracks, its one array field (A0J), reading
 *   each track's label through the one static method taking a track (LX/07sD;->A00) and that
 *   answer's String field (A0A), and keeps the matching track's id in the one String field it
 *   writes (A0I), or null, which is what a missing label comes to.
 * - The evaluator's choice of a track (BHS) keeps the tracks of its first call in that array field,
 *   once: it reads the field, skips the write when it's set, and writes it otherwise. The same
 *   method reads the kept id and plays that track while it has one, and chooses by bandwidth while
 *   it hasn't. So the hook goes right after that one write, where the evaluator has its tracks and
 *   hasn't chosen one yet, and it runs once for each evaluator.
 * - Facebook's evaluator also carries a preselected label that its first choice hands the setter.
 *   Instagram's has no such field: its only String field is the kept id, and nothing but the Hero
 *   method above calls the setter. That method is reached only from Instagram's internal video
 *   debug overlay (the "Set Quality" dialog of LX/06o0), so Instagram has no quality menu a person
 *   sees.
 * - Prefetching and offline saves use the evaluator's other choosers, which never write the array
 *   field or read the kept id, so only playback sees the choice.
 * - (Read from 454, 2026-11-12.) The choice (LX/0Ghl;->BKb) takes eight objects, then a float and
 *   three longs: the playback position, how much is buffered and how much is available, in
 *   microseconds. Its bandwidth chooser (LX/0Gim;->BFr) reads the second long, through the LX/03yi
 *   the choice builds, as the buffer it hands getLiveLowBufferSystemicRiskFactor, and takes the first
 *   from the content's end. A kept track goes through a chooser of its own (LX/0hMZ) that answers
 *   that track whatever is buffered, which is why a rung the connection can't carry stalls (#599).
 *   So a second hook goes at the top of the choice, with all of its parameters.
 */

internal const val QUALITY_CHOICE = "$EXTENSION_PACKAGE/media/QualityChoice;"
internal const val FIRST_CHOICE = "$QUALITY_CHOICE->firstChoice(Ljava/lang/Object;)V"

private const val ANY = "Ljava/lang/Object;"

/** Every choice, with the evaluator and the choice's parameters, each object as Object. */
internal const val EACH_CHOICE = "$QUALITY_CHOICE->eachChoice($ANY$ANY$ANY$ANY$ANY$ANY$ANY$ANY${ANY}FJJJ)V"

/** The extension class holding the stubs the patch fills, apart from the hook. */
internal const val QUALITY_READER = "$EXTENSION_PACKAGE/media/QualityReader;"
internal const val CUSTOM_TRACK_STUB = "customTrack"
internal const val FORMATS_STUB = "trackFormats"
internal const val LABEL_STUB = "formatLabel"
internal const val SETTER_STUB = "setCustomQuality"

internal const val SET_CUSTOM_QUALITY = "HeroServicePlayer.setCustomQualityInternal"
internal const val ABR_CONFIGURATION = "Lcom/facebook/exoplayer/formatevaluator/configuration/AbrContextAwareConfiguration;"
private const val STRING = "Ljava/lang/String;"

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Instruction.field: FieldReference?
    get() = (this as? ReferenceInstruction)?.reference as? FieldReference

internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

/** Whether [method] loads exactly [string]. */
internal fun holdsString(method: Method, string: String): Boolean =
    method.code().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string }

/** The instance calls in [method] of a (String)V method, the shape of the evaluator's custom-quality setter. */
internal fun customQualityCalls(method: Method): List<MethodReference> = method.code()
    .filter { it.opcode == Opcode.INVOKE_VIRTUAL }
    .mapNotNull { it.call }
    .filter { it.returnType == "V" && it.parameters() == listOf(STRING) }

/** Whether [evaluator]'s constructor takes an AbrContextAwareConfiguration. */
internal fun takesAbrConfiguration(evaluator: ClassDef): Boolean = evaluator.methods.any { method ->
    method.name == "<init>" && method.parameterTypes.any { it.toString() == ABR_CONFIGURATION }
}

/** [setter]'s reads of an array field of its own class: the tracks it looks among. */
internal fun formatsFields(setter: Method): List<FieldReference> = setter.code()
    .filter { it.opcode == Opcode.IGET_OBJECT }
    .mapNotNull { it.field }
    .filter { it.definingClass == setter.definingClass && it.type.startsWith("[L") }
    .distinctBy { it.toString() }

/**
 * How [setter] reads a track's label: a static call taking one [format], then a String field of
 * what it answers. One pair per place it does.
 */
internal fun labelReads(setter: Method, format: String): List<Pair<MethodReference, FieldReference>> {
    val code = setter.code()
    return code.indices.mapNotNull { index ->
        val call = code[index].call?.takeIf {
            code[index].opcode == Opcode.INVOKE_STATIC && it.parameters() == listOf(format) && it.returnType.startsWith("L")
        } ?: return@mapNotNull null
        val read = code.drop(index + 1).take(3).firstOrNull { it.opcode == Opcode.IGET_OBJECT }?.field
            ?.takeIf { it.definingClass == call.returnType && it.type == STRING } ?: return@mapNotNull null
        call to read
    }
}

/** The String fields of its own class [setter] writes: where it keeps the id of the track it found. */
internal fun customTrackFields(setter: Method): List<FieldReference> = setter.code()
    .filter { it.opcode == Opcode.IPUT_OBJECT }
    .mapNotNull { it.field }
    .filter { it.definingClass == setter.definingClass && it.type == STRING }
    .distinctBy { it.toString() }

/** Every write of [formats] in [evaluator]'s methods, as the method and the write's index. */
internal fun formatsWrites(evaluator: ClassDef, formats: FieldReference): List<Pair<Method, Int>> =
    evaluator.methods.flatMap { method ->
        val code = method.code()
        code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].field?.toString() == formats.toString() }
            .map { method to it }
    }

/**
 * Whether the write of [formats] at [index] of [code] is guarded to happen once: the field read on
 * the same object right before it, and a branch past the write when what it read isn't null.
 */
internal fun writtenOnce(code: List<Instruction>, index: Int, formats: FieldReference): Boolean {
    val write = code.getOrNull(index) as? TwoRegisterInstruction ?: return false
    val read = code.getOrNull(index - 2)
    val branch = code.getOrNull(index - 1)
    if (read?.opcode != Opcode.IGET_OBJECT || read.field?.toString() != formats.toString()) return false
    if (branch?.opcode != Opcode.IF_NEZ) return false
    val fieldRead = read as TwoRegisterInstruction
    if (fieldRead.registerB != write.registerB || (branch as OneRegisterInstruction).registerA != fieldRead.registerA) return false
    return code.target(index - 1) > index
}

/** Whether [method] reads [field]. */
internal fun reads(method: Method, field: FieldReference): Boolean =
    method.code().any { it.opcode == Opcode.IGET_OBJECT && it.field?.toString() == field.toString() }

/** The index the branch at [index] lands on, or -1 when it lands on no instruction. */
internal fun List<Instruction>.target(index: Int): Int {
    val address = IntArray(size + 1)
    forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
    return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
}
