/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Clean up Reels"

/** The tab strip at the top of Reels, Material's, whose name Instagram's build keeps. */
internal const val TAB_LAYOUT = "Lcom/google/android/material/tabs/TabLayout;"

/**
 * Instagram 454's drawable/empty_social_facepile (0x7f0806a8 on 453), the icon the Friends tab starts with
 * before its faces load, the same in all ten 454 builds (aapt2).
 */
internal const val FRIENDS_TAB_ICON = 0x7f0806a7

internal const val FRIENDS_TAB_HOOK = "$EXTENSION_PACKAGE/reels/ReelsTopBar;->friendsTab(Landroid/view/View;)V"

/** The views a tab's view can be, by its class's first Android ancestor. */
private val VIEW_ANCESTORS = setOf(
    "Landroid/view/View;", "Landroid/view/ViewGroup;", "Landroid/widget/LinearLayout;",
    "Landroid/widget/FrameLayout;", "Landroid/widget/RelativeLayout;",
)

/**
 * Where Instagram builds the Friends tab (#300): in [method], the tab in [tab] gets its facepile
 * icon, and at [at] its view is read into [view]. The hook goes in right after that read.
 */
internal class FriendsTabSite(val method: Method, val at: Int, val tab: Int, val view: Int)

/**
 * Finds the one place the Friends tab is built, failing before anything changes when it isn't there
 * exactly once. Instagram builds the Reels and Friends tabs in a method handed the [TAB_LAYOUT]
 * first, and only the Friends tab gets [FRIENDS_TAB_ICON]: a `const` of it, the tab's own int method
 * called with it straight after, and then the tab's view read off the tab, a field whose class is
 * a view. Later in the method a [TAB_LAYOUT] method taking just the tab adds it to the strip.
 * Another method that only swaps the icon of the tab already added (it looks the tab up by its
 * place) adds nothing and doesn't count.
 */
internal fun BytecodePatchContext.findFriendsTab(): FriendsTabSite {
    val known = sitesIn(classesLoading(FRIENDS_TAB_ICON.toLong()), { it == FRIENDS_TAB_ICON })
    // An APK whose resource table numbers the facepile differently (#533 failed with none, on a
    // file that may not be one of the builds checked here) still builds the tab the same way, so
    // the tab strip's own callers are read for that shape with any icon of the app's. The Reels
    // tab gets its icon the same way in the same method, so only the tab then handed on with the
    // signed-in account, where its friends' faces load, counts.
    val sites = known.ifEmpty { sitesIn(classesCallingInto(TAB_LAYOUT), { it ushr 24 == APP_PACKAGE }, ::loadsFaces) }
    return sites.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one place building the Friends tab with its facepile icon and adding it to the tab strip, found " +
            if (sites.isEmpty()) "none" else sites.joinToString { "${it.method.definingClass}->${it.method.name}" },
    )
}

/** The app's resource package, the top byte of every id its own resource table declares. */
private const val APP_PACKAGE = 0x7f

private const val FACES_SESSION = "Lcom/instagram/common/session/UserSession;"

private fun BytecodePatchContext.sitesIn(
    classes: List<ClassDef>,
    icon: (Int) -> Boolean,
    keep: (List<Instruction>, FriendsTabSite) -> Boolean = { _, _ -> true },
): List<FriendsTabSite> = classes.flatMap { classDef ->
    classDef.methods.filter { it.parameterTypes.firstOrNull()?.toString() == TAB_LAYOUT }
        .mapNotNull { method -> friendsTabIn(method, icon, keep) }
}

private fun BytecodePatchContext.friendsTabIn(
    method: Method,
    icon: (Int) -> Boolean,
    keep: (List<Instruction>, FriendsTabSite) -> Boolean,
): FriendsTabSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val found = (0 until code.size - 2).mapNotNull { at -> siteAt(method, code, at, icon, keep) }
    return found.singleOrNull()
}

/**
 * Whether the tab at [site] is handed, after its view is read, to a static method taking it and the
 * signed-in account, the way the Friends tab is to load its faces on every 454 build.
 */
private fun loadsFaces(code: List<Instruction>, site: FriendsTabSite): Boolean {
    val tabType = ((code[site.at - 1] as ReferenceInstruction).reference as MethodReference).definingClass
    return (site.at + 1 until code.size).any { later ->
        val call = code[later]
        val called = ((call as? ReferenceInstruction)?.reference as? MethodReference) ?: return@any false
        call.opcode == Opcode.INVOKE_STATIC && called.returnType == "V" &&
            called.parameterTypes.map(Any::toString) == listOf(tabType, FACES_SESSION) &&
            (call as FiveRegisterInstruction).registerC == site.tab
    }
}

private fun BytecodePatchContext.siteAt(
    method: Method,
    code: List<Instruction>,
    at: Int,
    isIcon: (Int) -> Boolean,
    keep: (List<Instruction>, FriendsTabSite) -> Boolean,
): FriendsTabSite? {
    val icon = code[at]
    if (icon.opcode != Opcode.CONST || !isIcon((icon as NarrowLiteralInstruction).narrowLiteral)) return null
    val literal = (icon as OneRegisterInstruction).registerA

    val set = code[at + 1]
    if (set.opcode != Opcode.INVOKE_VIRTUAL) return null
    val setter = (set as ReferenceInstruction).reference as MethodReference
    set as FiveRegisterInstruction
    if (set.registerCount != 2 || set.registerD != literal) return null
    if (setter.parameterTypes.map(Any::toString) != listOf("I") || setter.returnType != "V") return null
    val tabType = setter.definingClass
    val tab = set.registerC

    val read = code[at + 2]
    if (read.opcode != Opcode.IGET_OBJECT) return null
    val field = (read as ReferenceInstruction).reference as FieldReference
    read as TwoRegisterInstruction
    if (read.registerB != tab || field.definingClass != tabType || !isView(field.type)) return null

    val adds = (at + 3 until code.size).any { later ->
        val add = code[later]
        val called = ((add as? ReferenceInstruction)?.reference as? MethodReference) ?: return@any false
        add.opcode == Opcode.INVOKE_VIRTUAL && called.definingClass == TAB_LAYOUT &&
            called.parameterTypes.map(Any::toString).let { it == listOf(tabType) || it == listOf(tabType, "Z") } &&
            called.returnType == "V" && (add as FiveRegisterInstruction).registerD == tab
    }
    if (!adds) return null
    val site = FriendsTabSite(method, at + 2, tab, read.registerA)
    if (!keep(code, site)) return null
    // A jump to the setter or the read could arrive holding something else in those registers.
    val jumps = method.jumpTargets()
    // 453 jumps to the read itself when a condition leaves out the icon, which still reads the
    // same tab's view, so only a jump past the setter would land between the icon and its view.
    if (at + 1 in jumps) {
        throw PatchException("$PATCH: something jumps into the Friends tab's icon and view in ${method.definingClass}->${method.name}")
    }
    return site
}

/** Whether [type] is a view, by its superclasses up to the first Android one. */
private fun BytecodePatchContext.isView(type: String): Boolean {
    var at: String? = type
    var steps = 0
    while (at != null && steps++ < 10) {
        if (at.startsWith("Landroid/")) return at in VIEW_ANCESTORS
        at = classDefByOrNull(at)?.superclass
    }
    return false
}

/**
 * Hands the Friends tab's view to the extension right after Instagram reads it. Only code that came
 * through the read gets there: a jump to the instruction after it keeps its label there and skips
 * the call.
 */
internal fun BytecodePatchContext.markFriendsTab(site: FriendsTabSite) {
    val found = site.method
    val method = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString) &&
            it.returnType == found.returnType
    }
    val read = method.implementation!!.instructions.elementAtOrNull(site.at)
    if (read?.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerA != site.view) {
        throw PatchException("$PATCH: ${found.definingClass}->${found.name} changed before the Friends tab hook went in")
    }
    method.addInstruction(site.at + 1, "invoke-static/range { v${site.view} .. v${site.view} }, $FRIENDS_TAB_HOOK")
}
