/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The extension's hook told which raw animation the Keyframes loader is about to load, on which Context (#724). */
internal const val REPOST_LOAD = "$ACCENT_THEME->repostAnimation(Landroid/content/Context;I)V"

/** The extension's hook handed the drawable the Keyframes loader made, or null. */
internal const val REPOST_LOADED = "$ACCENT_THEME->repostAnimation(Landroid/graphics/drawable/Drawable;)V"

/** The extension's hook called as a Keyframes drawable starts to draw. */
internal const val REPOST_DRAW = "$ACCENT_THEME->repostDraw(Landroid/graphics/drawable/Drawable;Landroid/graphics/Canvas;)V"

/** The extension's hook called as a Keyframes drawable is done drawing. */
internal const val REPOST_DRAWN = "$ACCENT_THEME->repostDrawn(Landroid/graphics/drawable/Drawable;Landroid/graphics/Canvas;)V"

/** What the Keyframes drawable's constructor reads to name a capability a file asks for and it lacks. */
internal const val KEYFRAMES_CAPABILITY = "capability "

private const val REPOST_PATCH = "Accent color"
private const val KEYFRAMES_DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val KEYFRAMES_CANVAS = "Landroid/graphics/Canvas;"
private const val KEYFRAMES_CONTEXT = "Landroid/content/Context;"

/**
 * Instagram's Keyframes animations: [drawable] is the drawable that plays one (`LX/04s0` on
 * 386214574), [loader] the static (Context, raw id) method that reads the file and makes it
 * (`LX/04q1;->A02`), and [draw] its draw(Canvas).
 */
internal class KeyframesSite(val drawable: String, val loader: Method, val draw: Method)

/**
 * The Keyframes drawable is the one Drawable whose code loads "capability ", naming a feature a file
 * asks for that it lacks, and the loader the one static method taking a Context and an int that makes
 * it. Null, with a warning, when there isn't exactly one of either.
 */
internal fun BytecodePatchContext.findKeyframes(): KeyframesSite? {
    val drawables = classesLoadingString(KEYFRAMES_CAPABILITY).filter { classDef ->
        classDef.superclass == KEYFRAMES_DRAWABLE && classDef.methods.any { it.isDraw() }
    }
    if (drawables.size != 1) {
        patchLog.warning("$REPOST_PATCH: ${drawables.size} Keyframes drawables, not one, so the repost button keeps Instagram's color")
        return null
    }
    val drawable = drawables.single()
    val loaders = methodsCalling(drawable.type, "<init>").filter { it.isLoaderOf(drawable.type) }
    if (loaders.size != 1) {
        patchLog.warning("$REPOST_PATCH: ${loaders.size} Keyframes loaders take a Context and a raw id, not one, so the repost button keeps Instagram's color")
        return null
    }
    return KeyframesSite(drawable.type, loaders.single(), drawable.methods.single { it.isDraw() })
}

private fun Method.isDraw(): Boolean =
    name == "draw" && returnType == "V" && implementation != null &&
        parameterTypes.map(CharSequence::toString) == listOf(KEYFRAMES_CANVAS)

private fun Method.isLoaderOf(drawable: String): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == drawable && implementation != null &&
        parameterTypes.map(CharSequence::toString) == listOf(KEYFRAMES_CONTEXT, "I")

/**
 * Lets the repost button under a post take the accent icon color (#724). Its animations,
 * raw/repost_light and the rest, keep Instagram's black or white in the file, and the Keyframes
 * drawable playing them ignores color filters, so neither the icon style nor a tint reached them.
 * The loader tells the extension which raw it loads and hands it the drawable it made, and the
 * drawable's draw starts and ends with a call to the extension, which draws a repost animation in a
 * layer of the icon color. Leaves Keyframes as it is when [findKeyframes] finds nothing.
 */
internal fun BytecodePatchContext.tintRepostAnimation() {
    val site = findKeyframes() ?: return
    for (hook in listOf(REPOST_LOAD, REPOST_LOADED, REPOST_DRAW, REPOST_DRAWN)) {
        val owner = classDefByOrNull(ACCENT_THEME)?.methods?.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        }
        if (owner == null) throw PatchException("$REPOST_PATCH: the extension has no public static $hook")
    }

    val draw = mutableClassDefBy(site.drawable).methods.single { it.isDraw() }
    val drawCode = draw.keyframesCode()
    val drawn = drawCode.indices.filter { drawCode[it].opcode == Opcode.RETURN_VOID }
    if (drawn.isEmpty()) throw PatchException("$REPOST_PATCH: ${site.drawable}->draw never returns")
    draw.requireThisIntact(REPOST_PATCH, drawn)
    draw.requireParameterIntact(REPOST_PATCH, 0, drawn)
    val self = draw.parameterRegisterNumber(0) - 1
    val canvas = draw.parameterRegisterNumber(0)

    // The loader's new drawable is handed over right after its constructor, the one place it's made.
    // A load that fails returns null with nothing handed over, and the next load starts afresh.
    val loader = mutableClassDefBy(site.loader.definingClass).methods.single { it.name == site.loader.name && it.isLoaderOf(site.drawable) }
    val where = "${loader.definingClass}->${loader.name}"
    val context = loader.parameterRegisterNumber(0)
    val raw = loader.parameterRegisterNumber(1)
    if (raw != context + 1) throw PatchException("$REPOST_PATCH: $where keeps its raw id apart from its Context")
    val code = loader.keyframesCode()
    val made = code.indices.filter { code[it].makes(site.drawable) }
    if (made.size != 1) throw PatchException("$REPOST_PATCH: $where makes its drawable ${made.size} times, not once")
    val after = made.single() + 1
    if (after >= code.size || after in loader.jumpTargets()) throw PatchException("$REPOST_PATCH: $where jumps to the drawable it just made")
    val drawable = code[made.single()].firstRegister()

    for (at in drawn.asReversed()) {
        draw.addInstructionsAtControlFlowLabel(at, "invoke-static/range { v$self .. v$canvas }, $REPOST_DRAWN")
    }
    draw.addInstructions(0, "invoke-static/range { v$self .. v$canvas }, $REPOST_DRAW")
    loader.addInstructions(after, "invoke-static/range { v$drawable .. v$drawable }, $REPOST_LOADED")
    loader.addInstructions(0, "invoke-static/range { v$context .. v$raw }, $REPOST_LOAD")
}

private fun Method.keyframesCode(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/** Whether this calls [drawable]'s constructor. */
private fun Instruction.makes(drawable: String): Boolean {
    if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) return false
    val target = (this as ReferenceInstruction).reference as MethodReference
    return target.definingClass == drawable && target.name == "<init>"
}

/** The first register a call passes, the object a constructor call builds. */
private fun Instruction.firstRegister(): Int =
    (this as? FiveRegisterInstruction)?.registerC ?: (this as RegisterRangeInstruction).startRegister
