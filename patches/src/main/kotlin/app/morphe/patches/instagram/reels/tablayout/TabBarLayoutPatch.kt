/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tablayout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Tab bar layout"
internal const val TAB_BAR_LAYOUT = "$EXTENSION_PACKAGE/reels/TabBarLayout;"
internal const val NEW_TAB_BAR = "$TAB_BAR_LAYOUT->newTabBar(I)Z"
internal const val QUICK_SEARCH_PAGES = "$TAB_BAR_LAYOUT->quickSearchPages(I)I"

private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val CONFIGS = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
private const val VIEW_PAGER = "Landroidx/viewpager2/widget/ViewPager2;"
private const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"
private const val LIST = "Ljava/util/List;"

/**
 * The server config Instagram 453 asks whether an account gets the newer tab bar, the one with
 * Messages on it and a sideways swipe between every tab. Only two methods load it: the gate the
 * patch hooks, which reads it as a boolean, and one that reads a string from it. 452 had it as
 * 0x2081080a00102a78. The 453 retarget first took 0x208102ea00060a8d, a pending DM inbox gate of
 * the same shape that the tab host never asks, so the choice did nothing on 453.
 */
internal const val NEW_TAB_BAR_FLAG = 0x208107fa00102a5cL

/**
 * Picks the tab bar, newer or classic, instead of leaving it to Instagram (#356). Included in the
 * default selection with the choice on Instagram's, so the bar stays the one Instagram picks until
 * the user picks another.
 *
 * Instagram 453 asks one static method, `(UserSession)Z`, whether the account gets the newer bar.
 * It returns a stored override's answer when there is one, and otherwise the [NEW_TAB_BAR_FLAG]
 * config or a second gate. The tab host and about 120 other places ask it, so a server change can
 * move an account from one bar to the other between two launches. Each of the method's returns
 * hands its answer to [NEW_TAB_BAR] first, so every caller gets the bar the user picked.
 *
 * The classic bar also gets back the long press on Search that opens search (#383), which 453
 * only finishes on the swipe bar. See [hookQuickSearchWait].
 */
@Suppress("unused")
val tabBarLayoutPatch = bytecodePatch(
    name = "Tab bar layout",
    description = "Lets you keep the newer tab bar, where you swipe between every tab, or the classic one, " +
        "instead of the one Instagram picks for your account. Starts on Instagram's choice. Pick one in " +
        "HushGram settings > Tab bar.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("tabBarLayout")
        hookTabBarGate()
        hookQuickSearchWait()
        enableStatus("tabBarLayout")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Puts [NEW_TAB_BAR] before every return of the gate [findTabBarGate] finds. */
internal fun BytecodePatchContext.hookTabBarGate() {
    val gate = findTabBarGate()
    val method = mutableClassDefBy(gate.definingClass).methods.single {
        it.name == gate.name && it.parameterTypes.map(CharSequence::toString) == listOf(SESSION) && it.returnType == "Z"
    }
    // At each return's own label, so a branch straight to a return passes through the call too.
    // The hook takes the answer as an int (a boolean parameter fails verification on a register
    // ART types as int), and its boolean answer goes back into the register the method returns.
    method.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
        .asReversed()
        .forEach { (index, register) ->
            method.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $NEW_TAB_BAR
                    move-result v$register
                """,
            )
        }
}

/**
 * The one static `(UserSession)Z` method that loads [NEW_TAB_BAR_FLAG] and reads it as a boolean
 * through [CONFIGS]. Fails before anything changes when there isn't exactly one, or it doesn't
 * return a register, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findTabBarGate(): Method {
    val flag = NEW_TAB_BAR_FLAG.toString(16)
    val gates = classesLoading(NEW_TAB_BAR_FLAG).flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(SESSION) &&
            method.loads(NEW_TAB_BAR_FLAG) && method.readsBoolean()
    }
    if (gates.size != 1) {
        refuse("expected one static (UserSession)Z method reading the tab bar flag $flag as a boolean, found ${gates.size}")
    }
    val gate = gates.single()
    if (gate.implementation!!.instructions.none { it.opcode == Opcode.RETURN }) {
        refuse("${gate.definingClass}->${gate.name} reads $flag but returns no register")
    }
    return gate
}

/** Where the long press on Search reads how many swipe pages to wait for: the size call's move-result and its register. */
internal class QuickSearchWait(val method: Method, val index: Int, val register: Int)

/**
 * Hands the classic bar's page count in the long press on Search to [QUICK_SEARCH_PAGES]. On 453
 * the press switches to Search, then waits until the swipe pager's pages have built their views
 * before it opens search with the keyboard up. It counts the pages off the pager's adapter, and
 * with no pager off the tab host's tab list, which no page ever reaches on the classic bar, so the
 * press stops at Explore. The extension answers 0 there, and the wait ends at once.
 */
internal fun BytecodePatchContext.hookQuickSearchWait() {
    val site = findQuickSearchWait()
    val parameters = site.method.parameterTypes.map(CharSequence::toString)
    val method = mutableClassDefBy(site.method.definingClass).methods.single {
        it.name == site.method.name && it.parameterTypes.map(CharSequence::toString) == parameters && it.returnType == site.method.returnType
    }
    // After the move-result, so only the tab list's count goes through the call. A branch to the
    // next instruction skipped the size read and stays as it was.
    method.addInstructions(
        site.index + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $QUICK_SEARCH_PAGES
            move-result v${site.register}
        """,
    )
}

/**
 * The one method outside a tab host that reads its [VIEW_PAGER], counts the pager's adapter
 * (getItemCount), and reads the size of the same host's tab list into a register. Fails before
 * anything changes when there isn't exactly one such read.
 */
internal fun BytecodePatchContext.findQuickSearchWait(): QuickSearchWait {
    val readers = Fingerprint(
        filters = listOf(fieldAccess(definingClass = VIEW_PAGER, type = RECYCLER_VIEW, opcode = Opcode.IGET_OBJECT)),
    ).matchAllOrNull().orEmpty().map { it.originalMethod }.filterNot { it.definingClass.startsWith(EXTENSION_ROOT) }
    val sites = readers.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }.flatMap { it.tabCountReads() }
    if (sites.size != 1) {
        refuse("expected one read of a tab host's tab list size next to its $VIEW_PAGER's page count, found ${sites.size}")
    }
    return sites.single()
}

private fun Method.tabCountReads(): List<QuickSearchWait> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    val hosts = code.mapNotNull { it.field() }.filter { it.type == VIEW_PAGER && it.definingClass != definingClass }
        .map { it.definingClass }.toSet()
    val countsPages = code.any { (it.called()?.name == "getItemCount") && it.called()?.returnType == "I" }
    if (hosts.isEmpty() || !countsPages) return emptyList()
    return code.indices.filter { index ->
        val field = code[index].field()
        val size = code.getOrNull(index + 1)?.called()
        code[index].opcode == Opcode.IGET_OBJECT && field != null && field.type == LIST && field.definingClass in hosts &&
            code[index + 1].opcode == Opcode.INVOKE_INTERFACE && size?.definingClass == LIST && size.name == "size" &&
            (code[index + 1] as FiveRegisterInstruction).registerC == (code[index] as TwoRegisterInstruction).registerA &&
            code.getOrNull(index + 2)?.opcode == Opcode.MOVE_RESULT
    }.map { QuickSearchWait(this, it + 2, (code[it + 2] as OneRegisterInstruction).registerA) }
}

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.loads(literal: Long): Boolean = implementation?.instructions?.any {
    it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == literal
} == true

private fun Method.readsBoolean(): Boolean = implementation?.instructions?.any {
    val called = (it as? ReferenceInstruction)?.reference as? MethodReference
    it.opcode == Opcode.INVOKE_INTERFACE && called != null && called.definingClass == CONFIGS &&
        called.parameterTypes.map(CharSequence::toString) == listOf("J") && called.returnType == "Z"
} == true
