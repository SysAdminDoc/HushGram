/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Accent color"

private const val CONTEXT = "Landroid/content/Context;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val CANVAS = "Landroid/graphics/Canvas;"

/** Hands the extension an image view whose tint may be Instagram's text color, once the view has laid it on (#187). */
internal const val TEXT_ICONS = "$ACCENT_THEME->textIcons(Landroid/widget/ImageView;)V"

/** The extension's answer to a color an icon takes from the theme's text and icon color (#187). */
internal const val TEXT_ICON = "$ACCENT_THEME->textIcon(${CONTEXT}I)I"

/** The extension's answer to the color of the line under a tab strip's selected tab (#187). */
internal const val TAB_INDICATOR = "$ACCENT_THEME->tabIndicator(Landroid/view/View;I)I"

/** The top bar Instagram's newer screens build, which makes its buttons' badged icons. It keeps its name. */
internal const val IGDS_ACTION_BAR = "Lcom/instagram/igds/components/actionbar/IgdsActionBar;"

/** Material's tab strip, the follow lists' Followers and Following among its uses. It keeps its name. */
internal const val TAB_LAYOUT = "Lcom/google/android/material/tabs/TabLayout;"

/**
 * The one method of [TAB_ICON_VIEW] laying the tint it builds from its normal and active colors on
 * itself (`A01` on 386314286): an instance ()V calling ImageView.setImageTintList. The colors come from
 * the view's layout or from code, and every way to its tint ends there, a new image included. Many of
 * these views are given Instagram's text and icon color, the comment composer's buttons among them, so
 * the icon style never reached them (#187). Answers null with a warning when there isn't one. Refuses
 * when the extension lacks [TEXT_ICONS] or `this` is written over before a return.
 */
internal fun BytecodePatchContext.findColorFilterTint(): MutableMethod? {
    val setters = classDefByOrNull(TAB_ICON_VIEW)?.methods?.filter { it.laysItsTint() }.orEmpty()
    val setter = setters.singleOrNull() ?: run {
        patchLog.warning("$PATCH: ${setters.size} methods of $TAB_ICON_VIEW lay its tint, not one, so its icons keep Instagram's text color")
        return null
    }
    requireHook(TEXT_ICONS)
    val returns = setter.code().returnsVoid()
    if (returns.isEmpty()) refuse("${setter.where()} never returns")
    setter.requireThisIntact(PATCH, returns)
    return mutableClassDefBy(TAB_ICON_VIEW).methods.single { it.name == setter.name && it.laysItsTint() }
}

private fun Method.laysItsTint(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.isEmpty() && returnType == "V" &&
        code().any { it.called()?.let { called -> called.name == "setImageTintList" && called.definingClass == "Landroid/widget/ImageView;" } == true }

/**
 * Hands the view to the extension before each return of [findColorFilterTint]'s method, at the
 * return's own label so a branch to it passes through too, which answers the theme's icon color for a
 * tint in its text color with Icons in the accent color on (#187).
 */
internal fun BytecodePatchContext.tintColorFilterIcons() {
    val method = findColorFilterTint() ?: return
    for (at in method.code().returnsVoid().reversed()) {
        method.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p0 }, $TEXT_ICONS")
    }
}

/** Where a badged icon takes its color: right after the read at [at] in [method], into [color], with the Context in [context]. */
internal class BadgedIconColor(val method: MutableMethod, val at: Int, val context: Int, val color: Int)

/**
 * The drawable Instagram's top bars draw a button's icon in with room for its badge (`LX/00c8` on
 * 386314286), the one class extending Drawable that [IGDS_ACTION_BAR] makes. Its (Context, int)
 * constructor loads the icon by its resource and tints it with Context.getColor of the theme's text and
 * icon color, which the profile's top bar, the feed's, Explore's and others build theirs with: the bell,
 * the Threads icon, the menu and the back arrow on a profile (#187). Finds that constructor's one
 * getColor(int) with its answer taken right after. Answers null with a warning when there's no such
 * class, constructor or read, a register past v15 holds the Context or the color, or something jumps
 * to right after the read, which would skip the hook. Refuses when the extension lacks [TEXT_ICON].
 */
internal fun BytecodePatchContext.findBadgedIconColor(): BadgedIconColor? {
    fun skip(why: String): BadgedIconColor? {
        patchLog.warning("$PATCH: $why, so the icons on a profile's top bar keep Instagram's text color")
        return null
    }
    val bar = classDefByOrNull(IGDS_ACTION_BAR) ?: return skip("no $IGDS_ACTION_BAR")
    val made = bar.methods.flatMap { it.code() }.filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
        .filter { classDefByOrNull(it)?.superclass == DRAWABLE }
    val type = made.singleOrNull() ?: return skip("$IGDS_ACTION_BAR makes ${made.size} drawables of its own, not one")
    val constructor = classDefByOrNull(type)?.methods?.singleOrNull { it.isIconConstructor() }
        ?: return skip("$type has no (Context, int) constructor")
    val code = constructor.code()
    val reads = code.indices.filter { at ->
        code[at].called()?.let { it.definingClass == CONTEXT && it.name == "getColor" } == true && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }
    val read = reads.singleOrNull() ?: return skip("${constructor.where()} reads ${reads.size} colors, not one")
    val context = (code[read] as FiveRegisterInstruction).registerC
    val color = (code[read + 1] as OneRegisterInstruction).registerA
    if (context > 15 || color > 15 || context == color) return skip("${constructor.where()} keeps its Context or color past v15")
    if (read + 2 in constructor.jumpTargets()) return skip("${constructor.where()} jumps to right after its color's read")
    requireHook(TEXT_ICON)
    return BadgedIconColor(mutableClassDefBy(type).methods.single { it.isIconConstructor() }, read + 1, context, color)
}

private fun Method.isIconConstructor(): Boolean = name == "<init>" && parameterTypes.map(Any::toString) == listOf(CONTEXT, "I")

/**
 * Hands the color [findBadgedIconColor]'s constructor tints its icon with to the extension right after
 * reading it, with the Context it was read from, and takes the answer instead (#187).
 */
internal fun BytecodePatchContext.tintBadgedIcons() {
    val found = findBadgedIconColor() ?: return
    found.method.addInstructions(
        found.at + 1,
        """
            invoke-static { v${found.context}, v${found.color} }, $TEXT_ICON
            move-result v${found.color}
        """,
    )
}

/** Where a tab strip reads the color of the line under its selected tab: the iget at [at] in [method], into [color], from the TabLayout in [tabs]. */
internal class TabIndicatorRead(val method: MutableMethod, val at: Int, val tabs: Int, val color: Int)

/**
 * The read of the color [TAB_LAYOUT]'s setSelectedTabIndicatorColor keeps (its one int field it
 * stores) in a draw(Canvas) that tints a drawable with it within the next few instructions: the strip
 * drawing the line under the selected tab (`LX/0Ed0;->draw` on 386314286). Layouts and fragments give
 * that line Instagram's text and icon color, so it stayed white or black under the follow lists' tabs
 * (#187). Answers null with a warning when there isn't one such read, a register past v15 holds the
 * strip or the color, or something jumps to right after the read. Refuses when the extension lacks
 * [TAB_INDICATOR].
 */
internal fun BytecodePatchContext.findTabIndicatorRead(): TabIndicatorRead? {
    fun skip(why: String): TabIndicatorRead? {
        patchLog.warning("$PATCH: $why, so the line under a selected tab keeps Instagram's text color")
        return null
    }
    val layout = classDefByOrNull(TAB_LAYOUT) ?: return skip("no $TAB_LAYOUT")
    val setter = layout.methods.singleOrNull {
        it.name == "setSelectedTabIndicatorColor" && it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == "V"
    } ?: return skip("$TAB_LAYOUT has no setSelectedTabIndicatorColor(int)")
    val stores = setter.code().filter { it.opcode == Opcode.IPUT }.mapNotNull { it.field() }
        .filter { it.definingClass == TAB_LAYOUT && it.type == "I" }.map { it.name }.distinct()
    val field = stores.singleOrNull() ?: return skip("$TAB_LAYOUT's setSelectedTabIndicatorColor keeps ${stores.size} ints, not one")
    val reads = classesAccessing(TAB_LAYOUT, field, Opcode.IGET).distinctBy { it.type }.flatMap { classDef ->
        classDef.methods.filter { it.name == "draw" && it.parameterTypes.map(Any::toString) == listOf(CANVAS) && it.returnType == "V" }
            .flatMap { method ->
                val code = method.code()
                code.indices.filter { at -> code[at].readsIndicator(field) && code.tints(at) }.map { method to it }
            }
    }
    val (method, at) = reads.singleOrNull() ?: return skip("${reads.size} draws tint with $TAB_LAYOUT's indicator color, not one")
    val read = method.code()[at] as TwoRegisterInstruction
    if (read.registerA > 15 || read.registerB > 15 || read.registerA == read.registerB) {
        return skip("${method.where()} keeps its strip or color past v15")
    }
    if (at + 1 in method.jumpTargets()) return skip("${method.where()} jumps to right after the indicator color's read")
    requireHook(TAB_INDICATOR)
    val mutable = mutableClassDefBy(method.definingClass).methods.single { it.name == method.name && it.parameterTypes.map(Any::toString) == listOf(CANVAS) }
    return TabIndicatorRead(mutable, at, read.registerB, read.registerA)
}

private fun Instruction.readsIndicator(field: String): Boolean =
    opcode == Opcode.IGET && field()?.let { it.definingClass == TAB_LAYOUT && it.name == field && it.type == "I" } == true

/** Whether the int read at [at] goes to a Drawable.setTint(int) within the next three instructions. */
private fun List<Instruction>.tints(at: Int): Boolean {
    val color = (this[at] as TwoRegisterInstruction).registerA
    return (at + 1..minOf(at + 3, lastIndex)).any { next ->
        val call = this[next]
        call.called()?.let { it.definingClass == DRAWABLE && it.name == "setTint" } == true && (call as FiveRegisterInstruction).registerD == color
    }
}

/**
 * Hands the color [findTabIndicatorRead]'s draw reads for the line under the selected tab to the
 * extension right after the read, with the strip's TabLayout, and takes the answer instead (#187).
 */
internal fun BytecodePatchContext.tintTabIndicator() {
    val found = findTabIndicatorRead() ?: return
    found.method.addInstructions(
        found.at + 1,
        """
            invoke-static { v${found.tabs}, v${found.color} }, $TAB_INDICATOR
            move-result v${found.color}
        """,
    )
}

/** The hooks for icons in Instagram's text color, each finding its own place (#187). */
internal fun BytecodePatchContext.tintTextIcons() {
    tintColorFilterIcons()
    tintBadgedIcons()
    tintTabIndicator()
}

private fun BytecodePatchContext.requireHook(hook: String) {
    val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!present) refuse("the extension has no public static $hook")
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

private fun List<Instruction>.returnsVoid(): List<Int> = indices.filter { this[it].opcode == Opcode.RETURN_VOID }

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"
