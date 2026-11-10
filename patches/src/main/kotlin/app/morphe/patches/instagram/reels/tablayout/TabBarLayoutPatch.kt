/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tablayout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
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
internal const val RELOAD_EMPTY_TAB = "$TAB_BAR_LAYOUT->reloadEmptyTab()Z"

/** What Instagram's tab wrapper (IgTabHostFragment) traces while it builds its tab's page. */
internal const val TAB_LOAD_MARK = "IgTabHostFragment.loadFragment"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"

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
 * the same shape that the tab host never asks, so the choice did nothing on 453. 454 moved it from
 * 453's 0x208107fa00102a5c, read by the same gate (00PP.A04 on 386014758), which now asks for the
 * config through Bbu instead of BbW.
 */
internal const val NEW_TAB_BAR_FLAG = 0x208107cd00102a43L

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
 *
 * A picked bar also gets its tabs back after Android ends the app in the background (#535). See
 * [hookEmptyTabReload].
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
        hookEmptyTabReload()
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

/**
 * The parts of Instagram's tab wrapper the reload needs: its [loader], which marks the wrapper
 * loaded by putting [loaded] in [state] and then builds the page when [page] finds none, the
 * opcode the loader asks [page] with, and [attached], what the wrapper puts in [state] once its
 * page attaches.
 */
internal class TabLoader(
    val loader: Method,
    val state: FieldReference,
    val loaded: FieldReference,
    val attached: FieldReference,
    val page: MethodReference,
    val pageCall: Opcode,
)

/**
 * Builds a tab's page again when the tab comes on screen marked as loaded with no page in it, and
 * [RELOAD_EMPTY_TAB] says to. On 454 each tab sits in a wrapper (IgTabHostFragment, 03ja on
 * 386014758) whose onCreate builds the page only for a wrapper not loaded yet, and whose onResume
 * only for one held back until then. It saves its loading state with the app, and its page in its
 * own fragments. With the classic bar picked on an account Instagram gives the swipe bar, Android
 * ending the app in the background brings Home and Messages back marked attached with no page,
 * and the tab stays blank. First thing in onResume, a wrapper marked loaded or attached whose page
 * finder finds nothing asks the extension and, on yes, runs its own loader, which marks it loaded
 * and builds the page.
 */
internal fun BytecodePatchContext.hookEmptyTabReload() {
    val tab = findTabLoader()
    val owner = tab.loader.definingClass
    val resume = mutableClassDefBy(owner).methods.filter {
        it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" && it.implementation != null
    }
    if (resume.size != 1) refuse("expected one onResume()V on the tab wrapper $owner, found ${resume.size}")
    val method = resume.single()
    // Two locals, and the wrapper itself in a register the four-bit forms below can name.
    if (method.localRegisterCount() < 2 || method.implementation!!.registerCount > 16) {
        refuse("$owner->onResume has ${method.implementation!!.registerCount} registers, the reload needs two locals below v16")
    }
    val call = if (AccessFlags.PRIVATE.isSet(tab.loader.accessFlags)) "invoke-direct" else "invoke-virtual"
    val pageCall = if (tab.pageCall == Opcode.INVOKE_DIRECT) "invoke-direct" else "invoke-virtual"
    method.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${tab.state}
            sget-object v1, ${tab.loaded}
            if-eq v0, v1, :marked
            sget-object v1, ${tab.attached}
            if-ne v0, v1, :resume
            :marked
            $pageCall { p0 }, ${tab.page}
            move-result-object v0
            if-nez v0, :resume
            invoke-static { }, $RELOAD_EMPTY_TAB
            move-result v0
            if-eqz v0, :resume
            $call { p0 }, $owner->${tab.loader.name}()V
        """,
        ExternalLabel("resume", method.getInstruction(0)),
    )
}

/**
 * The one instance `()V` method loading [TAB_LOAD_MARK]: the tab wrapper's loader. Its first
 * write of a static value of a field's own type into the wrapper is the loaded state, and the
 * first `()Fragment` of the wrapper it asks after that is the page finder. The state the
 * wrapper's onAttachFragment writes into the same field is the attached one. Fails before
 * anything changes when there isn't exactly one loader or any part is missing.
 */
internal fun BytecodePatchContext.findTabLoader(): TabLoader {
    val wrappers = classesLoadingString(TAB_LOAD_MARK)
    val loaders = wrappers.flatMap { it.methods }.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == "V" && loadsString(it, TAB_LOAD_MARK)
    }
    if (loaders.size != 1) refuse("expected one ()V method loading \"$TAB_LOAD_MARK\", found ${loaders.size}")
    val loader = loaders.single()
    val code = loader.implementation!!.instructions.toList()
    val mark = code.stateWrite(loader.definingClass) { true }
        ?: refuse("${loader.definingClass}->${loader.name} marks no loading state")
    val state = code[mark + 1].field()!!
    val loaded = code[mark].field()!!
    val ask = code.drop(mark + 2).firstOrNull { instruction ->
        val called = instruction.called()
        (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_DIRECT) && called != null &&
            called.definingClass == loader.definingClass && called.parameterTypes.isEmpty() && called.returnType == FRAGMENT
    } ?: refuse("${loader.definingClass}->${loader.name} asks no page finder after marking itself loaded")
    val attach = wrappers.single { it.type == loader.definingClass }.methods.singleOrNull {
        it.name == "onAttachFragment" && it.parameterTypes.map { type -> type.toString() } == listOf(FRAGMENT) && it.returnType == "V"
    }?.implementation?.instructions?.toList().orEmpty()
    val attachedAt = attach.stateWrite(loader.definingClass) { it.toString() == state.toString() }
        ?: refuse("${loader.definingClass}->onAttachFragment marks no attached state")
    return TabLoader(loader, state, loaded, attach[attachedAt].field()!!, ask.called()!!, ask.opcode)
}

/**
 * The index of the first static value of a field's own type that's put straight into a field of
 * [owner] holding that type and passing [into], or null.
 */
private fun List<Instruction>.stateWrite(owner: String, into: (FieldReference) -> Boolean): Int? = indices.firstOrNull { at ->
    val value = this[at].field()
    val target = getOrNull(at + 1)?.field()
    this[at].opcode == Opcode.SGET_OBJECT && this[at + 1].opcode == Opcode.IPUT_OBJECT && value != null && target != null &&
        value.definingClass == value.type && target.definingClass == owner && target.type == value.type && into(target) &&
        (this[at] as OneRegisterInstruction).registerA == (this[at + 1] as TwoRegisterInstruction).registerA
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
