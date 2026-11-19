/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val TRIM_PATCH = "Expand captions"

internal const val CAPTION_LENGTH = "$EXTENSION_PACKAGE/feed/CaptionLength;"
internal const val CAPTION_MAX_LINES = "$CAPTION_LENGTH->maxLines(Ljava/lang/Object;I)I"

/** The caption view whose onMeasure cuts its text with the same helper the feed caption's builder calls. */
internal const val EXPANDING_TEXT_VIEW = "Lcom/instagram/ui/widget/expandingtextview/ExpandingTextView;"

/** Names the surface enum keeps for the main feed and the Reels viewer, which only it holds together. */
internal val SURFACE_NAMES = listOf("MAIN_FEED", "CLIPS_VIEWER")

private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val SPANNABLE = "Landroid/text/SpannableStringBuilder;"
private const val CONTEXT = "Landroid/content/Context;"
private const val ENUM = "Ljava/lang/Enum;"

/**
 * Where a feed post's caption is cut (#771): in [type]->[name], the call at [call] to Instagram's
 * trim helper, whose line count sits in register [lines]. The caption's props come in as declared
 * parameter 1, and [surface] is their field naming the surface the post shows on.
 */
internal class CaptionTrim(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val call: Int,
    val lines: Int,
    val surface: String,
) {
    /**
     * Puts the extension's question in front of the cut: the surface and Instagram's count go in, and
     * the answer replaces the count. Two low locals nothing reads after the call carry them.
     */
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single {
            it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        val (holder, count) = method.freeLocalsAt(TRIM_PATCH, call, 2)
        val props = method.parameterRegisterNumber(1)
        method.addInstructions(
            call,
            """
                move-object/from16 v$holder, v$props
                iget-object v$holder, v$holder, $surface
                move/from16 v$count, v$lines
                invoke-static { v$holder, v$count }, $CAPTION_MAX_LINES
                move-result v$count
                move/from16 v$lines, v$count
            """,
        )
    }
}

/**
 * Finds the cut. The helper is the static (layout builder, header, text, "more", line count, flag)
 * call [EXPANDING_TEXT_VIEW]'s onMeasure makes, on 456's 386214574 LX/01Gc->A00. A dozen places call
 * it, so the patch hooks the one call that builds a feed caption: the single static method calling
 * it that takes a Context and the caption's props and returns a SpannableStringBuilder, LX/02pL->A03
 * there. The props have one field of the surface enum, the one whose constants include
 * [SURFACE_NAMES]. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findCaptionTrim(): CaptionTrim {
    val view = classDefByOrNull(EXPANDING_TEXT_VIEW) ?: refuseTrim("$EXPANDING_TEXT_VIEW isn't in the app")
    val measure = view.methods.singleOrNull { it.name == "onMeasure" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I") }
        ?: refuseTrim("$EXPANDING_TEXT_VIEW has no onMeasure")
    val helpers = measure.trimCode().mapNotNull { it.trimCall() }.filter { it.isTrimHelper() }.distinctBy { it.toString() }
    val helper = helpers.singleOrNull()
        ?: refuseTrim("expected onMeasure to call one trim helper, found ${helpers.size}")
    val helperParameters = helper.parameterTypes.map(CharSequence::toString)

    val builders = classesCalling(helper.definingClass, helper.name, helperParameters, helper.returnType).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == SPANNABLE &&
                method.parameterTypes.size >= 2 && method.parameterTypes[0].toString() == CONTEXT &&
                method.trimCode().any { it.trimCall()?.toString() == helper.toString() }
        }
    }
    val builder = builders.singleOrNull()
        ?: refuseTrim("expected one caption builder calling $helper, found ${builders.size}")
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.trimCode()
    val calls = code.indices.filter { code[it].trimCall()?.toString() == helper.toString() }
    val call = calls.singleOrNull() ?: refuseTrim("$where calls the trim helper ${calls.size} times, not once")
    val range = code[call] as? RegisterRangeInstruction
        ?: refuseTrim("$where doesn't hand the trim helper its arguments as a range")
    if (call in builder.jumpTargets()) refuseTrim("$where jumps straight to the trim call, past where the hook would go")
    val lines = range.startRegister + helperParameters.indexOf("I")
    if (lines > 255) refuseTrim("$where keeps the line count in v$lines, past what the hook can write back")

    val props = builder.parameterTypes[1].toString()
    val surfaces = (classDefByOrNull(props) ?: refuseTrim("$where takes props $props the app doesn't have"))
        .fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && isSurfaceEnum(it.type) }
    val surface = surfaces.singleOrNull()
        ?: refuseTrim("expected $props to hold one surface, found ${surfaces.size}")
    builder.requireParameterIntact(TRIM_PATCH, 1, listOf(call))
    val field = "${surface.definingClass}->${surface.name}:${surface.type}"
    return CaptionTrim(builder.definingClass, builder.name, builder.parameterTypes.map(CharSequence::toString),
        builder.returnType, call, lines, field)
}

/** Whether [type] is an enum whose static initializer loads every one of [SURFACE_NAMES]. */
private fun BytecodePatchContext.isSurfaceEnum(type: String): Boolean {
    val enum = classDefByOrNull(type)?.takeIf { it.superclass == ENUM } ?: return false
    val initializer = enum.methods.singleOrNull { it.name == "<clinit>" } ?: return false
    val code = initializer.trimCode()
    val names = code.indices.mapNotNullTo(HashSet()) { stringLoadedAt(code, it) }
    return names.containsAll(SURFACE_NAMES)
}

/** The static (object, CharSequence x3, int, boolean) CharSequence helper Instagram cuts captions with. */
private fun MethodReference.isTrimHelper(): Boolean {
    val parameters = parameterTypes.map(CharSequence::toString)
    return returnType == CHAR_SEQUENCE && parameters.size == 6 && parameters[0].startsWith("L") &&
        parameters.subList(1, 4).all { it == CHAR_SEQUENCE } && parameters[4] == "I" && parameters[5] == "Z"
}

private fun Instruction.trimCall(): MethodReference? =
    if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) (this as ReferenceInstruction).reference as? MethodReference
    else null

private fun Method.trimCode(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun refuseTrim(detail: String): Nothing = throw PatchException("$TRIM_PATCH: $detail")
