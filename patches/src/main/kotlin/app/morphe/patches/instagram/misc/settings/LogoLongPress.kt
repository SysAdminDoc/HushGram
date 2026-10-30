/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** Home's header, the bar at the top of your feed. A class Instagram's layouts name, so every build keeps it. */
internal const val LOGO_BAR = "Linstagram/features/feed/mainfeed/actionbar/MainFeedActionBar;"

/**
 * The header's title view, the middle of the bar. It holds the Instagram logo (`title_logo`) and
 * the feed's name, and takes the taps and long presses on them. Also a class the layouts name.
 */
internal const val LOGO_TITLE = "Lcom/instagram/actionbar/ActionBarTitleViewSwitcher;"

private const val LOGO_LONG_LISTENER = "Landroid/view/View\$OnLongClickListener;"

/**
 * SettingsEntry's stand-in for Instagram's call that gives the title view its long press: the
 * title view, then Instagram's listener. It makes the call itself, with a listener that asks
 * Instagram's first and opens the HushGram screen when Instagram's doesn't take the press.
 */
internal const val LOGO_LONG_PRESS_STAND_IN = "$ENTRY->setLogoLongClickListener(Landroid/view/View;$LOGO_LONG_LISTENER)V"

/**
 * Where a logo hook goes: the instruction at [index] in [method]. For the older header, the call
 * giving the title view its long press; for the newer one, the store keeping its logo view.
 */
internal class LogoSite(val method: Method, val index: Int)

/** Whether this is a setOnLongClickListener call, the framework's or the title view's, taking one listener. */
private fun Instruction.setsLongPress(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.name == "setOnLongClickListener" && call.returnType == "V" &&
        call.parameterTypes.map { it.toString() } == listOf(LOGO_LONG_LISTENER) &&
        (call.definingClass.startsWith("Landroid/") || call.definingClass == LOGO_TITLE)
}

/** The register a call is made on: its first. */
private fun Instruction.receiver(): Int = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> -1
}

/** Whether this instruction writes [register], on its own or as the high half of a wide value. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/** Whether this instruction puts the title view in its register: a read of the bar's field holding it, or a cast to it. */
private fun Instruction.holdsTitle(): Boolean = when (opcode) {
    Opcode.IGET_OBJECT -> ((this as ReferenceInstruction).reference as? FieldReference)
        ?.let { it.definingClass == LOGO_BAR && it.type == LOGO_TITLE } == true
    Opcode.CHECK_CAST -> ((this as ReferenceInstruction).reference as? TypeReference)?.type == LOGO_TITLE
    else -> false
}

/**
 * Every place [method] gives the title view a long press: a setOnLongClickListener call on a
 * register whose last write before it, in the method's order, read the bar's title view or cast
 * to it. The bar gives long presses to other views too (its buttons, and the feed's name on the
 * floating header), and those are left out.
 */
internal fun logoLongPressSitesIn(method: Method): List<LogoSite> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        val call = code[at]
        if (!call.setsLongPress()) return@filter false
        val receiver = call.receiver()
        code.subList(0, at).lastOrNull { it.writes(receiver) }?.holdsTitle() == true
    }.map { LogoSite(method, it) }
}

/**
 * Every place [LOGO_BAR] gives its title view, and so the Instagram logo in it, a long press. In
 * 450 that's one call, in the method that draws the header from a state (MainFeedActionBar.A08),
 * on the field holding the title view, in each of the seven builds.
 */
internal fun BytecodePatchContext.logoLongPressSites(): List<LogoSite> =
    classDefByOrNull(LOGO_BAR)?.methods?.flatMap(::logoLongPressSitesIn).orEmpty()

/**
 * Sends Instagram's call that gives Home's title view its long press to [LOGO_LONG_PRESS_STAND_IN],
 * on the same registers, so a long press on the Instagram logo at the top of the feed opens the
 * HushGram screen. Instagram's tap on the logo isn't touched, and its own long
 * press there (the app icon sheet on Instagram Plus accounts) runs only when the screen can't open. Answers false and changes nothing when this build doesn't give the
 * title view a long press in exactly one place: the logo then stays as it was, and the icon
 * shortcut and the row in Settings and activity still open the screen.
 */
internal fun BytecodePatchContext.hookLogoLongPress(): Boolean {
    val sites = logoLongPressSites()
    val site = sites.singleOrNull() ?: run {
        patchLog.warning(
            "HushGram settings: ${sites.size} places give the Instagram logo at the top of Home its long press, " +
                "not one, so the logo doesn't open the settings",
        )
        return false
    }
    val method = mutableClassDefBy(site.method.definingClass).methods.single { it.sameShapeAs(site.method) }
    // Looked for again in the method as it stands now, since another patch may have written into it.
    val index = logoLongPressSitesIn(method).singleOrNull()?.index ?: run {
        patchLog.warning(
            "HushGram settings: ${site.method.definingClass}->${site.method.name} changed before the logo's long press " +
                "could be found again, so the logo doesn't open the settings",
        )
        return false
    }
    // The stand-in takes the call's place, so a branch landing on the call lands on it.
    method.sendToStandIn(index, LOGO_LONG_PRESS_STAND_IN)
    return true
}

/**
 * Home's newer header, on accounts Instagram moved to its merged feeds. A class Instagram's layouts
 * name, so every build keeps it. It shows the Instagram logo (`wordmark_logo`) as Home opens, and
 * on some accounts keeps it there, but gives the logo no long press or tap of its own.
 */
internal const val MERGED_LOGO_BAR = "Lcom/instagram/homecoming/feeds/mergedfeed/actionbar/MergedFeedsActionBar;"

/** The view the newer header holds its logo in. The header keeps one field of this type, the logo's. */
internal const val MERGED_LOGO_VIEW = "Lcom/instagram/common/ui/base/IgSimpleImageView;"

/** SettingsEntry's call that gives a logo Instagram left without a long press the one opening the HushGram screen. */
internal const val MERGED_LOGO_STAND_IN = "$ENTRY->watchLogo(Landroid/view/View;)V"

/** Every place [method] stores a view in a field of [MERGED_LOGO_BAR] holding the logo's type. */
internal fun mergedLogoStoresIn(method: Method): List<LogoSite> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        code[at].opcode == Opcode.IPUT_OBJECT &&
            ((code[at] as ReferenceInstruction).reference as? FieldReference)
                ?.let { it.definingClass == MERGED_LOGO_BAR && it.type == MERGED_LOGO_VIEW } == true
    }.map { LogoSite(method, it) }
}

/**
 * Every place [MERGED_LOGO_BAR] keeps its logo view. In 450 that's one store, in the constructor,
 * right after it finds the view in its layout, in each of the seven builds (the field's name and
 * the finding helper differ between them).
 */
internal fun BytecodePatchContext.mergedLogoStores(): List<LogoSite> =
    classDefByOrNull(MERGED_LOGO_BAR)?.methods?.flatMap(::mergedLogoStoresIn).orEmpty()

/**
 * Hands the newer header's logo view to [MERGED_LOGO_STAND_IN] right after the header keeps it, so a
 * long press on the logo opens the HushGram screen there too. Instagram's own code isn't changed.
 * Answers false and changes nothing when this build doesn't keep the view in exactly one place,
 * or has no such header: the older header's logo, the icon shortcut and the row in Settings and
 * activity still open the screen.
 */
internal fun BytecodePatchContext.hookMergedLogoLongPress(): Boolean {
    val stores = mergedLogoStores()
    val store = stores.singleOrNull() ?: run {
        patchLog.warning(
            "HushGram settings: ${stores.size} places keep the Instagram logo in Home's newer header, " +
                "not one, so that logo doesn't open the settings",
        )
        return false
    }
    val method = mutableClassDefBy(store.method.definingClass).methods.single { it.sameShapeAs(store.method) }
    // Looked for again in the method as it stands now, since another patch may have written into it.
    val index = mergedLogoStoresIn(method).singleOrNull()?.index ?: run {
        patchLog.warning(
            "HushGram settings: ${store.method.definingClass}->${store.method.name} changed before the newer header's " +
                "logo could be found again, so that logo doesn't open the settings",
        )
        return false
    }
    val logo = (method.implementation!!.instructions[index] as OneRegisterInstruction).registerA
    method.addInstruction(index + 1, "invoke-static/range { v$logo .. v$logo }, $MERGED_LOGO_STAND_IN")
    return true
}

private fun Method.sameShapeAs(other: Method) =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)
