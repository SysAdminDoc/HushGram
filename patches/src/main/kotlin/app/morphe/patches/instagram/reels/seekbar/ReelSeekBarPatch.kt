/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Keep a seek bar on Reels"
internal const val REEL_SEEK_BAR = "$EXTENSION_PACKAGE/reels/ReelSeekBar;"
internal const val MIN_SECONDS = "$REEL_SEEK_BAR->minSeconds(J)J"
internal const val LAZY = "$REEL_SEEK_BAR->lazy(I)Z"
internal const val PROGRESS = "$REEL_SEEK_BAR->progress(Landroid/widget/SeekBar;I)V"
internal const val BIND = "$REEL_SEEK_BAR->bind(Ljava/lang/Object;I)V"

/**
 * The shortest ordinary reel, in seconds, that gets Instagram's attached seek bar: a parameter of
 * the server setting ig_android_iv_video_scrubber. 450 reads it three times: in the check of
 * whether a reel gets the bar, in the check of whether its bar is the hidden kind, and where the
 * Reels progress controller keeps its limits. The first two read an ad's own minimum in the same
 * place, picked in a branch.
 */
internal const val ORGANIC_MIN_SECONDS = 0x82092100291499L
internal const val ORGANIC_MIN_SECONDS_READS = 3

/**
 * Whether a short ordinary reel's bar is the hidden kind, shown only while you hold the reel. 450
 * reads it once, where the seek bar row's state is worked out, in the same place as the ads' flag.
 */
internal const val ORGANIC_LAZY = 0x81092100103399L
internal const val ORGANIC_LAZY_READS = 1

/** The markers, after Instagram's release prefix, of the methods that prove the reads are the seek bar's. */
internal const val SHOULD_SHOW_ATTACHED = "ClipsExperimentUtil_shouldShowAttachedScrubber"
internal const val CALCULATE_SHOULD_SHOW = "ClipsItemUseCase_calculateShouldShowAttachedScrubber"
internal const val SCRUBBER_ROW_STATE = "ClipsScrubberRowUseCase_getUiState"

/** Instagram's reel seek bar, a kept class, and the listener method the label hook goes in. */
internal const val SEEK_BAR = "Lcom/instagram/ui/mediaactions/VideoScrubberSeekBar;"
internal const val PROGRESS_CHANGED = "onProgressChanged"
private const val ANDROID_SEEK_BAR = "Landroid/widget/SeekBar;"
private val PROGRESS_CHANGED_PARAMETERS = listOf(ANDROID_SEEK_BAR, "I", "Z")

/**
 * The tag 449 gives a reel's seek bar container, then the reel's id. The one method setting it is
 * the binder that ties the container, whose first child is the bar, to its reel.
 */
internal const val SCRUBBER_TAG = "clips_scrubber_"
private const val SET_TAG = "Landroid/view/View;->setTag(Ljava/lang/Object;)V"

/**
 * Keeps Instagram's seek bar under every reel, with the time played and the reel's length. Off in
 * the default selection: it changes how every reel looks, so it's the user's pick. Asked for in #10.
 *
 * Instagram 449 decides per reel whether to draw its seek bar under it from a server minimum
 * length, and on short reels draws none, or one hidden until a hold. The patch answers the minimum
 * for ordinary reels as one second and the hidden kind as off, through the extension, at each of
 * Instagram's reads of them; where a read also serves the ads' value, only the ordinary reel's
 * branch gets the answer. The bar's own onProgressChanged hands the extension the bar and its
 * position, for the time label. Ads use the same bar, so the binder that ties a seek bar container
 * to its reel hands the extension the container and Instagram's own answer for whether the reel is
 * an ad, the one the seek bar row reads to pick the ads' setting, and only a bar in a container
 * bound to an ordinary reel gets the label.
 *
 * Every read is found by its server id and counted, each is checked against Instagram's own markers
 * for the seek bar, and the bar's class and method and the binder are checked, all before anything
 * changes. A build that differs stops the patch naming what it couldn't find, and nothing is half
 * done.
 */
@Suppress("unused")
val reelSeekBarPatch = bytecodePatch(
    name = "Keep a seek bar on Reels",
    description = "Keeps Instagram's seek bar under every reel, short ones too, with the time played and the reel's " +
        "length above it, like 0:10 / 0:55. Ads keep Instagram's own rules.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelSeekBar")
        applyReelSeekBar(findReelSeekBarSites())
        enableStatus("reelSeekBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The three reads of the minimum, the read of the hidden kind, the seek bar's listener method and the binder. */
internal class ReelSeekBarSites(
    val lengths: List<FlagLoad>,
    val lazy: FlagLoad,
    val progress: Method,
    val bind: BindSite,
)

/**
 * Where the binder reads whether its reel is an ad: [readAt] is the `iget-boolean` of that field into
 * [flag], and [view] is the register holding the container all through the method.
 */
internal class BindSite(
    val method: Method,
    val readAt: Int,
    val flag: Int,
    val view: Int,
)

/**
 * Finds every read of [ORGANIC_MIN_SECONDS] and [ORGANIC_LAZY] and checks there are exactly
 * [ORGANIC_MIN_SECONDS_READS] and [ORGANIC_LAZY_READS], then that they're the seek bar's: the
 * method marked [SHOULD_SHOW_ATTACHED] reads the minimum and the one marked
 * [CALCULATE_SHOULD_SHOW] calls it, and the one marked [SCRUBBER_ROW_STATE] reads the hidden kind
 * and calls a method that reads the minimum. Then finds [SEEK_BAR]'s one [PROGRESS_CHANGED]. Fails
 * before anything changes when any of it isn't so.
 */
internal fun BytecodePatchContext.findReelSeekBarSites(): ReelSeekBarSites {
    val lengths = findFlagLoads(PATCH, ORGANIC_MIN_SECONDS, "J")
    if (lengths.size != ORGANIC_MIN_SECONDS_READS) {
        refuse(
            "expected $ORGANIC_MIN_SECONDS_READS reads of the shortest reel with a seek bar " +
                "(${ORGANIC_MIN_SECONDS.toString(16)}), found ${lengths.size}",
        )
    }
    val lazies = findFlagLoads(PATCH, ORGANIC_LAZY, "Z")
    if (lazies.size != ORGANIC_LAZY_READS) {
        refuse("expected $ORGANIC_LAZY_READS read of the hidden seek bar flag (${ORGANIC_LAZY.toString(16)}), found ${lazies.size}")
    }
    val lazy = lazies.single()

    val marked = markedMethods(listOf(SHOULD_SHOW_ATTACHED, CALCULATE_SHOULD_SHOW, SCRUBBER_ROW_STATE))
    val attached = marked.getValue(SHOULD_SHOW_ATTACHED)
    if (lengths.none { it.signature() == attached.signature() }) {
        refuse("${attached.signature()} ($SHOULD_SHOW_ATTACHED) doesn't read the shortest reel with a seek bar")
    }
    val calculate = marked.getValue(CALCULATE_SHOULD_SHOW)
    if (attached.signature() !in calculate.calledSignatures()) {
        refuse("${calculate.signature()} ($CALCULATE_SHOULD_SHOW) doesn't ask ${attached.signature()}")
    }
    val row = marked.getValue(SCRUBBER_ROW_STATE)
    if (lazy.signature() != row.signature()) {
        refuse("the hidden seek bar flag is read in ${lazy.signature()}, not in ${row.signature()} ($SCRUBBER_ROW_STATE)")
    }
    if (lengths.none { it.signature() in row.calledSignatures() }) {
        refuse("${row.signature()} ($SCRUBBER_ROW_STATE) asks no method reading the shortest reel with a seek bar")
    }
    return ReelSeekBarSites(lengths, lazy, findProgressChanged(), findBindSite(row, lazy))
}

/**
 * The binder's read of whether its reel is an ad. The field is the one [adField] proves the seek
 * bar row's state ([row]) picks the ads' hidden kind by. The binder is the one method setting a
 * [SCRUBBER_TAG] tag on a view, and it reads a field of the primitive that makes [SEEK_BAR], so the
 * view is that bar's container. It reads the ad field once; the call goes right after the read,
 * where nothing jumps, with the read's register and the tagged view, a parameter's register that
 * nothing writes over but a cast.
 */
internal fun BytecodePatchContext.findBindSite(row: Method, lazy: FlagLoad): BindSite {
    val field = adField(row, lazy)
    val binders = mutableListOf<Method>()
    val tagging = classesHolding(SCRUBBER_TAG).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in tagging || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions ?: return@forEach
            if (code.any { it.string() == SCRUBBER_TAG } && code.any { it.methodSignature() == SET_TAG }) binders += method
        }
    }
    val binder = binders.singleOrNull() ?: refuse("expected one method tagging a view $SCRUBBER_TAG, found ${binders.size}")
    val where = binder.signature()
    val code = binder.implementation!!.instructions.toList()
    if (code.none { it.opcode == Opcode.IGET_OBJECT && makesSeekBar(it.fieldReference()!!.type) }) {
        refuse("$where reads no field of the primitive that makes $SEEK_BAR")
    }
    val reads = code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].fieldReference()?.text() == field }
    val readAt = reads.singleOrNull() ?: refuse("expected $where to read $field once, found ${reads.size}")
    val flag = (code[readAt] as OneRegisterInstruction).registerA
    val views = code.filter { it.methodSignature() == SET_TAG }.map { it.arguments().first() }.toSet()
    val view = views.singleOrNull() ?: refuse("$where tags ${views.size} views, not the one it's handed")
    if (view < binder.localRegisterCount()) refuse("$where tags a view of its own, not the one it's handed")
    val overwrites = code.indices.filter { code[it].writes(view) && code[it].opcode != Opcode.CHECK_CAST }
    if (overwrites.isNotEmpty()) refuse("$where writes over the view it tags at instruction(s) ${overwrites.joinToString()}")
    if (readAt == code.lastIndex || readAt + 1 in binder.jumpTargets()) {
        refuse("$where jumps to just after its read of $field, where the call would go")
    }
    if (flag > 15 || view > 15) refuse("$where keeps the view or the ad answer in a register past v15")
    return BindSite(binder, readAt, flag, view)
}

/**
 * The boolean field of the reel item ([row]'s second parameter) that [row] picks the ads' hidden
 * kind by: the one boolean of the item it reads, into a register that an `if-eqz` tests on the only
 * way to [lazy], the ordinary reel's flag, with every way to that test going through the read and
 * nothing on the way writing over the register. So the field is false for an ordinary reel and true
 * for an ad, as 449's row reads the ads' flag and the ads' minimum when it's true.
 */
private fun adField(row: Method, lazy: FlagLoad): String {
    val where = row.signature()
    val item = row.parameterTypes.getOrNull(1)?.toString() ?: refuse("$where takes no reel item")
    val code = row.implementation!!.instructions.toList()
    val reads = code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].fieldReference()!!.definingClass == item }
    val readAt = reads.singleOrNull() ?: refuse("expected $where to read one boolean of $item, found ${reads.size}")
    val field = code[readAt].fieldReference()!!.text()
    val register = (code[readAt] as OneRegisterInstruction).registerA
    val flow = ControlFlow.of(row)
    val predecessors = Array(code.size) { mutableSetOf<Int>() }
    code.indices.forEach { from -> (flow.normal[from] + flow.exceptional[from]).forEach { predecessors[it] += from } }
    val test = predecessors[lazy.loadAt].singleOrNull()
        ?: refuse("$where reaches its ordinary reel's hidden kind from ${predecessors[lazy.loadAt].size} places, not one test")
    val branch = code[test]
    if (branch.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != register ||
        flow.normal[test].first() != lazy.loadAt || test + 1 == lazy.loadAt
    ) {
        refuse("$where doesn't read its ordinary reel's hidden kind only when $field is false")
    }
    fun reach(from: Collection<Int>, next: (Int) -> Collection<Int>, stopAt: Int, avoid: Int): Set<Int> {
        val seen = mutableSetOf<Int>()
        val pending = ArrayDeque(from.filter { it != avoid })
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (!seen.add(at) || at == stopAt) continue
            next(at).filter { it != avoid }.forEach(pending::addLast)
        }
        return seen
    }
    val successors = { at: Int -> flow.normal[at] + flow.exceptional[at] }
    if (test in reach(listOf(0), successors, test, readAt)) {
        refuse("$where can test its register before reading $field into it")
    }
    val after = reach(successors(readAt), successors, test, readAt)
    if (test !in after) refuse("$where never tests $field after reading it")
    val before = reach(listOf(test), { predecessors[it] }, readAt, readAt)
    val overwrites = (after intersect before).filter { it != test && code[it].writes(register) }.sorted()
    if (overwrites.isNotEmpty()) refuse("$where writes over $field before testing it, at instruction(s) ${overwrites.joinToString()}")
    return field
}

/** Whether [type] is a class with a method answering [SEEK_BAR]: the primitive that makes the bar. */
private fun BytecodePatchContext.makesSeekBar(type: String): Boolean =
    classDefByOrNull(type)?.methods?.any { it.returnType == SEEK_BAR } == true

/**
 * [SEEK_BAR]'s [PROGRESS_CHANGED]: the class has to be a SeekBar, and to have exactly one method of
 * that name, an instance `(SeekBar, int, boolean)void` with a body whose position parameter an
 * invoke can name.
 */
internal fun BytecodePatchContext.findProgressChanged(): Method {
    val bar = classDefByOrNull(SEEK_BAR) ?: refuse("this Instagram build has no $SEEK_BAR")
    var parent: String? = bar.superclass
    val seen = mutableSetOf<String>()
    while (parent != ANDROID_SEEK_BAR) {
        if (parent == null || !seen.add(parent)) refuse("$SEEK_BAR isn't a $ANDROID_SEEK_BAR")
        parent = classDefByOrNull(parent)?.superclass ?: refuse("$SEEK_BAR extends $parent, which isn't a $ANDROID_SEEK_BAR")
    }
    val named = bar.methods.filter { it.name == PROGRESS_CHANGED }
    val method = named.singleOrNull() ?: refuse("expected one $PROGRESS_CHANGED in $SEEK_BAR, found ${named.size}")
    if (method.parameterTypes.map(CharSequence::toString) != PROGRESS_CHANGED_PARAMETERS || method.returnType != "V" ||
        AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null
    ) {
        refuse("$SEEK_BAR's $PROGRESS_CHANGED isn't an instance (SeekBar, int, boolean)void with a body")
    }
    if (method.parameterRegisterNumber(1) > 15) {
        refuse("$SEEK_BAR's $PROGRESS_CHANGED keeps the position in a register past v15")
    }
    return method
}

/**
 * Answers each read of the minimum through [MIN_SECONDS] and the read of the hidden kind through
 * [LAZY], then puts the call to [PROGRESS] first in the seek bar's [PROGRESS_CHANGED], handing it
 * the bar (`this`) and the position, and the call to [BIND] right after the binder's read of
 * whether its reel is an ad, handing it the container and that answer.
 */
internal fun BytecodePatchContext.applyReelSeekBar(sites: ReelSeekBarSites) {
    answerFlagLoads(sites.lengths.map { it to MIN_SECONDS } + (sites.lazy to LAZY))
    val progress = sites.progress
    mutableClassDefBy(progress.definingClass).methods.single {
        it.name == progress.name && it.parameterTypes.map(CharSequence::toString) == PROGRESS_CHANGED_PARAMETERS
    }.addInstructions(0, "invoke-static { p0, p2 }, $PROGRESS")
    val bind = sites.bind
    mutableClassDefBy(bind.method.definingClass).methods.single {
        it.name == bind.method.name && it.returnType == bind.method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == bind.method.parameterTypes.map(CharSequence::toString)
    }.addInstructions(bind.readAt + 1, "invoke-static { v${bind.view}, v${bind.flag} }, $BIND")
}

/** The one method outside the extension holding each marker; fails when a marker isn't held by exactly one. */
private fun BytecodePatchContext.markedMethods(wanted: List<String>): Map<String, Method> {
    val found = wanted.associateWith { mutableListOf<Method>() }
    val marked = typesMarked(*wanted.toTypedArray())
    classDefForEach { classDef ->
        if (classDef.type !in marked) return@classDefForEach
        classDef.methods.forEach { method ->
            method.markers().toSet().forEach { marker -> found[marker]?.add(method) }
        }
    }
    return found.mapValues { (marker, methods) ->
        methods.singleOrNull() ?: refuse("expected one method marked $marker, found ${methods.size}")
    }
}

private fun FlagLoad.signature() = "$type->$name(${parameters.joinToString("")})$returnType"

private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.calledSignatures(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference) }
    ?.map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    ?.toSet() ?: emptySet()

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun FieldReference.text() = "$definingClass->$name:$type"

private fun Instruction.methodSignature(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)
    ?.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }

/** The registers an invoke passes, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether the instruction puts a new value in [register], half of a wide one included. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}
