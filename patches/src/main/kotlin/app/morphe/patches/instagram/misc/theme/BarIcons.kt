/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.profilesaved.EXPLORE_ACTION_BAR
import app.morphe.patches.instagram.misc.profilesaved.EXPLORE_SAVED_STUB
import app.morphe.patches.instagram.reels.tab.TOASTING_BADGE
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Accent color"

private const val FILTER = "Landroid/graphics/ColorFilter;"
private const val IMAGE = "Landroid/widget/ImageView;"
private const val COLOR_LIST = "Landroid/content/res/ColorStateList;"
private const val STRING = "Ljava/lang/String;"
private const val PORTER_DUFF_FILTER = "Landroid/graphics/PorterDuffColorFilter;"
private const val SRC_ATOP = "Landroid/graphics/PorterDuff\$Mode;->SRC_ATOP:Landroid/graphics/PorterDuff\$Mode;"

/** The extension's answer to the color a top bar's icon or a profile tab is given (#187). */
internal const val BAR_ICONS = "$ACCENT_THEME->barIcons(I)I"

/** The extension's stand-in for the top bar's maker of the filter it lays on its buttons (#187). */
internal const val BAR_ICON_FILTER = "$ACCENT_THEME->barIconFilter(I)$FILTER"

/** The extension's stand-in for the top bar's ImageView.setColorFilter(int) on its back arrow (#187). */
internal const val SET_BAR_ICON_COLOR = "$ACCENT_THEME->setBarIconColor(${IMAGE}I)V"

/** Hands the extension the Search tab's Saved bookmark as its top bar makes it (#187). */
internal const val SEARCH_SAVED = "$ACCENT_THEME->searchSaved($IMAGE)V"

/**
 * What only the class behind every screen's top bar throws, the one class holding its back arrow,
 * title and buttons (`LX/01pF` on 386314341). Instagram keeps the message as it is through its builds.
 */
internal const val ACTION_BAR_MESSAGE = "Custom action-bar views require action Done-to-checkmark Wave 2"

/** A profile's Posts, Reels, Reposts and Tagged tab. Its layout names it, so it keeps its name. */
internal const val PROFILE_TAB = "Lcom/instagram/profile/intf/tabs/ProfileIconTabView;"

/** The top bar's calls that color its icons: [filters] make a button's color filter, [tints] tint its back arrow. */
internal class ActionBarIcons(val filters: List<Pair<MutableMethod, Int>>, val tints: List<Pair<MutableMethod, Int>>)

/**
 * Finds [ActionBarIcons] in the one class loading [ACTION_BAR_MESSAGE]: each call to a static
 * `(int)ColorFilter` that makes a PorterDuffColorFilter in SRC_ATOP mode, the one the bar lays on its
 * back arrow and buttons with Instagram's text and icon color, and each ImageView.setColorFilter(int).
 * Answers null with a warning when there isn't one such class or it makes no icon's color, so the top
 * bars keep Instagram's colors. Refuses when the extension lacks a hook.
 */
internal fun BytecodePatchContext.findActionBarIcons(): ActionBarIcons? {
    fun skip(why: String): ActionBarIcons? {
        patchLog.warning("$PATCH: $why, so the icons on a screen's top bar keep Instagram's colors")
        return null
    }
    val bars = classesLoadingString(ACTION_BAR_MESSAGE).distinctBy { it.type }
    val bar = bars.singleOrNull() ?: return skip("${bars.size} classes load the top bar's message, not one")
    val makers = HashMap<String, Boolean>()
    fun makesFilter(called: MethodReference): Boolean = makers.getOrPut("${called.definingClass}->${called.name}") {
        classDefByOrNull(called.definingClass)?.methods?.singleOrNull {
            it.name == called.name && it.sameShape(called) && AccessFlags.STATIC.isSet(it.accessFlags)
        }?.makesSrcAtopFilter() == true
    }
    val filters = mutableListOf<Pair<String, Int>>()
    val tints = mutableListOf<Pair<String, Int>>()
    for (method in bar.methods) {
        val code = method.code()
        for (at in code.indices) {
            val called = code[at].called() ?: continue
            when {
                code[at].opcode in STATIC_CALLS && called.returnType == FILTER && called.parameterTypes.map(Any::toString) == listOf("I") &&
                    makesFilter(called) -> filters += method.key() to at
                code[at].opcode in VIRTUAL_CALLS && called.definingClass == IMAGE && called.name == "setColorFilter" &&
                    called.parameterTypes.map(Any::toString) == listOf("I") -> tints += method.key() to at
            }
        }
    }
    if (filters.isEmpty() && tints.isEmpty()) return skip("${bar.type} makes no icon's color")
    for (hook in listOf(BAR_ICON_FILTER, SET_BAR_ICON_COLOR)) requireHook(hook)
    val owner = mutableClassDefBy(bar.type)
    val methods = owner.methods.associateBy { it.key() }
    return ActionBarIcons(filters.map { methods.getValue(it.first) to it.second }, tints.map { methods.getValue(it.first) to it.second })
}

/**
 * Hands the color each of a screen's top bar's buttons and its back arrow is given to the extension,
 * which answers the picked color with Icons in the accent color on (#187). The bar reads Instagram's
 * text and icon color for them, which the title shares, so the icon style the theme gets never reached
 * them and Saved's back arrow, search and + stayed white. Each call to the bar's filter maker becomes a
 * call to the extension's, which makes the same filter from its answer, and each
 * ImageView.setColorFilter(int) a call to the extension's with the view and the color, which lays its
 * answer on that view. The registers stay as they were.
 */
internal fun BytecodePatchContext.tintActionBarIcons() {
    val found = findActionBarIcons() ?: return
    for ((method, at) in found.filters) {
        val call = method.getInstruction<Instruction>(at)
        val color = if (call is RegisterRangeInstruction) call.startRegister else (call as FiveRegisterInstruction).registerC
        val range = call is RegisterRangeInstruction
        method.replaceInstruction(at, if (range) "invoke-static/range { v$color .. v$color }, $BAR_ICON_FILTER" else "invoke-static { v$color }, $BAR_ICON_FILTER")
    }
    for ((method, at) in found.tints) {
        val call = method.getInstruction<Instruction>(at)
        val registers = when (call) {
            is RegisterRangeInstruction -> "v${call.startRegister} .. v${call.startRegister + 1}"
            else -> (call as FiveRegisterInstruction).let { "v${it.registerC}, v${it.registerD}" }
        }
        method.replaceInstruction(at, if (call is RegisterRangeInstruction) "invoke-static/range { $registers }, $SET_BAR_ICON_COLOR" else "invoke-static { $registers }, $SET_BAR_ICON_COLOR")
    }
}

/** Where a profile tab is told its colors: [method], and the color [reads] it takes from the theme for while it isn't selected. */
internal class ProfileTabColors(val method: MutableMethod, val reads: List<Int>)

/**
 * Finds [PROFILE_TAB]'s setActiveColor(int) and each Context.getColor(int) in it with its answer
 * taken right after, the gray of a tab you aren't on. Answers null with a warning when there's no such
 * class or method, so the profile's tabs keep Instagram's colors. Refuses when the extension lacks
 * [BAR_ICONS].
 */
internal fun BytecodePatchContext.findProfileTabColors(): ProfileTabColors? {
    fun skip(why: String): ProfileTabColors? {
        patchLog.warning("$PATCH: $why, so a profile's tabs keep Instagram's colors")
        return null
    }
    val tab = classDefByOrNull(PROFILE_TAB) ?: return skip("no $PROFILE_TAB")
    val setter = tab.methods.singleOrNull {
        it.name == "setActiveColor" && it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == "V" && it.implementation != null
    } ?: return skip("$PROFILE_TAB has no setActiveColor(int)")
    val code = setter.code()
    val reads = code.indices.filter { at ->
        val called = code[at].called()
        called != null && called.definingClass == "Landroid/content/Context;" && called.name == "getColor" &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 1 }
    requireHook(BAR_ICONS)
    return ProfileTabColors(mutableClassDefBy(PROFILE_TAB).methods.single { it.name == setter.name && it.sameShape(setter) }, reads)
}

/**
 * Hands a profile tab's color to the extension as setActiveColor(int) starts, and the gray it reads
 * for while it isn't selected right after reading it, so with Icons in the accent color on Posts,
 * Reels, Reposts and Tagged take the picked color whether you're on them or not (#187). The range form
 * takes any register.
 */
internal fun BytecodePatchContext.tintProfileTabs() {
    val found = findProfileTabColors() ?: return
    val method = found.method
    for (at in found.reads.sortedDescending()) {
        val register = method.getInstruction<OneRegisterInstruction>(at).registerA
        method.addInstructions(at + 1, "invoke-static/range { v$register .. v$register }, $BAR_ICONS\nmove-result v$register")
    }
    val color = method.parameterRegisterNumber(0)
    method.addInstructions(0, "invoke-static/range { v$color .. v$color }, $BAR_ICONS\nmove-result v$color")
}

/** Where the Search tab's top bar keeps the Saved bookmark it made: the store at [at] of the view in [view]. */
internal class SearchSavedStore(val method: MutableMethod, val at: Int, val view: Int)

/**
 * Finds the one store of an image view into a field of [EXPLORE_ACTION_BAR] after the one method
 * loading [EXPLORE_SAVED_STUB] loads it: the bookmark it just inflated. Answers null with a warning
 * when there isn't one such method or store, so the bookmark keeps Instagram's color. Refuses when the
 * extension lacks [SEARCH_SAVED].
 */
internal fun BytecodePatchContext.findSearchSavedStore(): SearchSavedStore? {
    fun skip(why: String): SearchSavedStore? {
        patchLog.warning("$PATCH: $why, so the Search tab's Saved bookmark keeps Instagram's color")
        return null
    }
    val bar = classDefByOrNull(EXPLORE_ACTION_BAR) ?: return skip("no $EXPLORE_ACTION_BAR")
    val makers = bar.methods.filter { method -> method.code().any { it.loads(EXPLORE_SAVED_STUB) } }
    val maker = makers.singleOrNull() ?: return skip("${makers.size} methods of $EXPLORE_ACTION_BAR load the Saved stub, not one")
    val code = maker.code()
    val load = code.indexOfFirst { it.loads(EXPLORE_SAVED_STUB) }
    val stores = (load until code.size).filter { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field?.definingClass == EXPLORE_ACTION_BAR && isImageView(field.type)
    }
    val store = stores.firstOrNull() ?: return skip("$EXPLORE_ACTION_BAR->${maker.name} keeps no image view after loading the Saved stub")
    requireHook(SEARCH_SAVED)
    return SearchSavedStore(
        mutableClassDefBy(EXPLORE_ACTION_BAR).methods.single { it.name == maker.name && it.sameShape(maker) },
        store,
        (code[store] as TwoRegisterInstruction).registerA,
    )
}

/**
 * Hands the Search tab's Saved bookmark to the extension right after its top bar keeps it, which lays
 * the picked icon color over it with Icons in the accent color on (#187). The range form takes any
 * register.
 */
internal fun BytecodePatchContext.tintSearchSaved() {
    val found = findSearchSavedStore() ?: return
    found.method.addInstructions(found.at + 1, "invoke-static/range { v${found.view} .. v${found.view} }, $SEARCH_SAVED")
}

/**
 * What a profile's tab maker and the Home top bar throw when the badge they inflate isn't a
 * ToastingBadge, the view Tagged's tab is (`LX/0Bux;->Apx` on 386314286). The message keeps its words
 * through the builds, and only those classes and a pool of shared strings hold it.
 */
internal const val TOASTING_BADGE_CAST = "null cannot be cast to non-null type com.instagram.notifications.badging.ui.component.ToastingBadge"

/** Where a profile's Tagged tab is tinted: in [method], the call at [at] making its color list from the gray in [first] and the color in the register after it. */
internal class TaggedTabColors(val method: MutableMethod, val at: Int, val first: Int)

/**
 * Finds where a profile's Tagged tab makes its tint: among the methods loading [TOASTING_BADGE_CAST]
 * and casting to [TOASTING_BADGE], the one call of a static (int, int, int, int, int, int)
 * ColorStateList, in its range form, whose answer goes straight to ImageView.setImageTintList. Its
 * first int is the gray the theme holds for a tab you aren't on and the second the color of the one
 * you are. Tagged is made there rather than as a [PROFILE_TAB], so [tintProfileTabs] never reached it
 * (#187). Answers null with a warning when there isn't one such call. Refuses when the extension lacks
 * [BAR_ICONS].
 */
internal fun BytecodePatchContext.findTaggedTabColors(): TaggedTabColors? {
    fun skip(why: String): TaggedTabColors? {
        patchLog.warning("$PATCH: $why, so a profile's Tagged tab keeps Instagram's gray")
        return null
    }
    val makers = classesLoadingString(TOASTING_BADGE_CAST).distinctBy { it.type }.flatMap { it.methods }.filter { method ->
        method.code().any { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == TOASTING_BADGE } &&
            loadsString(method, TOASTING_BADGE_CAST)
    }
    val sites = makers.flatMap { method -> method.code().let { code -> code.indices.filter { code.makesTabTint(it) }.map { method to it } } }
    val (maker, at) = sites.singleOrNull() ?: return skip("${sites.size} methods casting the badge make a tab's tint, not one")
    requireHook(BAR_ICONS)
    val mutable = mutableClassDefBy(maker.definingClass).methods.single { it.name == maker.name && it.sameShape(maker) }
    return TaggedTabColors(mutable, at, (maker.code()[at] as RegisterRangeInstruction).startRegister)
}

/** Whether [at] makes a color list from six ints with the range form and lays it straight on an image view as its tint. */
private fun List<Instruction>.makesTabTint(at: Int): Boolean {
    val called = this[at].called() ?: return false
    if (this[at].opcode != Opcode.INVOKE_STATIC_RANGE || called.returnType != COLOR_LIST ||
        called.parameterTypes.map(Any::toString) != List(6) { "I" }
    ) return false
    val taken = getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction ?: return false
    val tint = getOrNull(at + 2) as? FiveRegisterInstruction ?: return false
    val set = tint.called() ?: return false
    return set.definingClass == IMAGE && set.name == "setImageTintList" && tint.registerCount == 2 && tint.registerD == taken.registerA
}

/**
 * Hands the two colors a profile's Tagged tab makes its tint from to the extension right before the
 * call making it, at that call's own label, the gray of a tab you aren't on and the color of the one
 * you are, so with Icons in the accent color on Tagged takes the picked color like the other tabs (#187).
 */
internal fun BytecodePatchContext.tintTaggedTab() {
    val found = findTaggedTabColors() ?: return
    val (gray, color) = found.first to found.first + 1
    found.method.addInstructionsAtControlFlowLabel(
        found.at,
        """
            invoke-static/range { v$gray .. v$gray }, $BAR_ICONS
            move-result v$gray
            invoke-static/range { v$color .. v$color }, $BAR_ICONS
            move-result v$color
        """,
    )
}

/** Tells the extension the next icon Compose's icon wrapper draws in the default color is a search bar's search icon (#187). */
internal const val SEARCH_ICON_NEXT = "$ACCENT_THEME->searchIconNext()V"

/** Hands the extension the color Compose's icon wrapper reads for an icon given none of its own (#187). */
internal const val DEFAULT_ICON = "$ACCENT_THEME->defaultIcon(J)J"

/** Hands the extension the color a Compose search bar draws the search icon its theme names in (#187). */
internal const val SEARCH_ICON = "$ACCENT_THEME->searchIcon(J)J"

/** Instagram 457's attr/inlineSearchBarSearchIconResource, the search icon a search bar's theme names, in all four 457 builds (aapt2). */
internal const val INLINE_SEARCH_ICON = 0x7f04091a

/**
 * Instagram 457's search outline icon, a search bar's own, in all four 457 builds: SearchEditText and
 * SearchController load it too, and it sits between instagram_search_filled_24 and
 * instagram_search_gen_ai_outline_24, though aapt2 names neither it nor the ids beside it.
 */
internal const val SEARCH_OUTLINE_ICON = 0x7f0812f2

/**
 * Where Compose search bars draw their search icon, your messages' among them (`LX/0O2s;->A07` and its
 * twin `LX/0BUR;->A07` on 386314286): [named], each call of the icon wrapper that draws the bar's own
 * icon in the default color, [given], each call drawing the theme's icon in the color in the two
 * registers before the call's last, and in [wrapper], the icon wrapper (`LX/0833;->A1O`), the read of
 * that default color at [read].
 */
internal class SearchBarIcons(
    val named: List<Pair<MutableMethod, Int>>,
    val given: List<Pair<MutableMethod, Int>>,
    val wrapper: MutableMethod,
    val read: Int,
)

/**
 * Finds [SearchBarIcons]: the static void methods loading both [INLINE_SEARCH_ICON] and
 * [SEARCH_OUTLINE_ICON], in each the first call after the outline's load of a static
 * (composer, modifier, painter, String) void icon wrapper, and the first call after the theme icon's
 * load of a static (composer, modifier, painter, String, long) void with the color taken right before
 * it. The bars read their gray from Compose's own palette, which no theme reaches, so with Icons in the
 * accent color on your messages' search icon stayed gray (#187). The wrapper is the same for every bar
 * and reads the default color once, a long field of the palette it's handed. Answers null with a
 * warning when a bar draws either icon another way or the wrapper reads its color another way. Refuses
 * when the extension lacks a hook.
 */
internal fun BytecodePatchContext.findSearchBarIcons(): SearchBarIcons? {
    fun skip(why: String): SearchBarIcons? {
        patchLog.warning("$PATCH: $why, so a search bar's search icon keeps Instagram's gray")
        return null
    }
    val bars = classesLoading(INLINE_SEARCH_ICON.toLong()).distinctBy { it.type }.flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.code().let { code -> code.any { it.loads(INLINE_SEARCH_ICON) } && code.any { it.loads(SEARCH_OUTLINE_ICON) } }
    }
    if (bars.isEmpty()) return skip("no search bar loads both search icons")
    val sites = bars.map { bar ->
        val code = bar.code()
        val named = code.firstCallAfter(code.indexOfFirst { it.loads(SEARCH_OUTLINE_ICON) }) { at -> code.drawsDefaultIcon(at) }
        val given = code.firstCallAfter(code.indexOfFirst { it.loads(INLINE_SEARCH_ICON) }) { at -> code.drawsGivenIcon(at) }
        if (named < 0 || given < 0) return skip("${bar.definingClass}->${bar.name} draws a search icon another way")
        Triple(bar, named, given)
    }
    val wrappers = sites.map { (bar, named, _) -> bar.code()[named].called()!! }.distinctBy { "${it.definingClass}->${it.name}" }
    val wrapperCall = wrappers.singleOrNull() ?: return skip("the bars draw their own icon with ${wrappers.size} wrappers, not one")
    val wrapper = classDefByOrNull(wrapperCall.definingClass)?.methods?.singleOrNull { it.name == wrapperCall.name && it.sameShape(wrapperCall) }
        ?: return skip("no ${wrapperCall.definingClass}->${wrapperCall.name}")
    val read = wrapper.code().defaultColorRead() ?: return skip("${wrapper.definingClass}->${wrapper.name} reads its color another way")
    if (read + 1 in wrapper.jumpTargets()) return skip("${wrapper.definingClass}->${wrapper.name} jumps to right after reading its color")
    requireHook(SEARCH_ICON_NEXT)
    requireHook(DEFAULT_ICON)
    requireHook(SEARCH_ICON)
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).methods.single { it.name == method.name && it.sameShape(method) }
    return SearchBarIcons(
        sites.map { (bar, named, _) -> mutable(bar) to named },
        sites.map { (bar, _, given) -> mutable(bar) to given },
        mutable(wrapper),
        read,
    )
}

/** The first index after [from] that [wanted] takes among the static calls, or -1. */
private fun List<Instruction>.firstCallAfter(from: Int, wanted: (Int) -> Boolean): Int {
    if (from < 0) return -1
    return (from + 1 until size).firstOrNull { this[it].opcode in STATIC_CALLS && wanted(it) } ?: -1
}

/** Whether [at] calls a static (object, object, object, String) void, an icon drawn in the default color. */
private fun List<Instruction>.drawsDefaultIcon(at: Int): Boolean {
    val called = this[at].called() ?: return false
    val parameters = called.parameterTypes.map(Any::toString)
    return called.returnType == "V" && parameters.size == 4 && parameters.last() == STRING &&
        parameters.dropLast(1).all { it.startsWith("L") && it != STRING }
}

/**
 * Whether [at] calls a static (object, object, object, String, long) void with the range form, its
 * long taken by the move-result-wide right before it, an icon drawn in a color handed over.
 */
private fun List<Instruction>.drawsGivenIcon(at: Int): Boolean {
    val call = this[at] as? RegisterRangeInstruction ?: return false
    val called = this[at].called() ?: return false
    val parameters = called.parameterTypes.map(Any::toString)
    if (called.returnType != "V" || parameters.size != 5 || parameters[3] != STRING || parameters.last() != "J" ||
        parameters.take(3).any { !it.startsWith("L") || it == STRING }
    ) return false
    val taken = getOrNull(at - 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_WIDE } as? OneRegisterInstruction ?: return false
    return taken.registerA == call.startRegister + call.registerCount - 2
}

/**
 * In Compose's icon wrapper, the one iget-wide of a long field, with no branch before it, whose pair
 * the range call after it hands on last: the default icon color read from the palette. With no branch
 * before it, every call of the wrapper reads it, so the extension's flag never outlives its icon.
 */
private fun List<Instruction>.defaultColorRead(): Int? {
    val reads = indices.filter { this[it].opcode == Opcode.IGET_WIDE && ((this[it] as ReferenceInstruction).reference as FieldReference).type == "J" }
    val read = reads.singleOrNull() ?: return null
    if (subList(0, read).any { it is OffsetInstruction }) return null
    val color = (this[read] as TwoRegisterInstruction).registerA
    val handed = (read + 1 until size).any { at ->
        val call = this[at] as? RegisterRangeInstruction ?: return@any false
        this[at].called()?.parameterTypes?.lastOrNull()?.toString() == "J" && color == call.startRegister + call.registerCount - 2
    }
    return read.takeIf { handed }
}

/**
 * Names each Compose search bar's own search icon to the extension right before the bar draws it, at
 * the call's label, hands the color a bar draws its theme's icon in to the extension right before that
 * call, and hands the default color the icon wrapper reads to the extension right after the read, so
 * with Icons in the accent color on the search icon atop your messages takes the picked color (#187).
 * The wrapper's hook answers every other icon's color as it came.
 */
internal fun BytecodePatchContext.tintSearchBarIcons() {
    val found = findSearchBarIcons() ?: return
    val sites = found.named.map { (method, at) -> Triple(method, at, "invoke-static {}, $SEARCH_ICON_NEXT") } +
        found.given.map { (method, at) ->
            val color = (method.getInstruction(at - 1) as OneRegisterInstruction).registerA
            Triple(method, at, "invoke-static/range { v$color .. v${color + 1} }, $SEARCH_ICON\nmove-result-wide v$color")
        }
    // Later sites first, so each index still names its call.
    for ((method, at, code) in sites.sortedByDescending { it.second }) method.addInstructionsAtControlFlowLabel(at, code)
    val color = (found.wrapper.getInstruction(found.read) as TwoRegisterInstruction).registerA
    found.wrapper.addInstructions(found.read + 1, "invoke-static/range { v$color .. v${color + 1} }, $DEFAULT_ICON\nmove-result-wide v$color")
}

/** [tintActionBarIcons], [tintProfileTabs], [tintTaggedTab], [tintSearchSaved] and [tintSearchBarIcons]. */
internal fun BytecodePatchContext.tintBarIcons() {
    tintActionBarIcons()
    tintProfileTabs()
    tintTaggedTab()
    tintSearchSaved()
    tintSearchBarIcons()
}

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/** Whether [type] is ImageView or a class of the app extending it, however far down. */
private fun BytecodePatchContext.isImageView(type: String): Boolean {
    var current: String? = type
    repeat(8) {
        if (current == IMAGE) return true
        current = current?.let { classDefByOrNull(it)?.superclass } ?: return false
    }
    return false
}

/** Whether this method's body makes a PorterDuffColorFilter in SRC_ATOP mode. */
private fun Method.makesSrcAtopFilter(): Boolean {
    val code = code()
    return code.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == PORTER_DUFF_FILTER } &&
        code.any { it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).text() == SRC_ATOP }
}

private fun BytecodePatchContext.requireHook(hook: String) {
    val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!present) throw PatchException("$PATCH: the extension has no public static $hook")
}

private fun Instruction.loads(literal: Int) = (this as? NarrowLiteralInstruction)?.narrowLiteral == literal

private fun FieldReference.text() = "$definingClass->$name:$type"

private fun Method.sameShape(other: MethodReference): Boolean =
    returnType == other.returnType && parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

private fun Method.key(): String = "$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
