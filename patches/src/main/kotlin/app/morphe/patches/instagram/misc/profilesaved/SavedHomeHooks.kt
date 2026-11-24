/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.profilesaved

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
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
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val SAVED_PATCH = "Saved on your profile"

private const val SAVED_HOME = "$EXTENSION_PACKAGE/misc/SavedHome;"

/** Whether the Saved link's screen was started by HushGram's bookmark. */
internal const val FROM_BOOKMARK = "$SAVED_HOME->fromBookmark(Landroid/app/Activity;)I"

/** Picks the menu's Saved screen over the link's grid. */
internal const val PICK_SCREEN = "$SAVED_HOME->pick(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/** Whether the + at the top of Saved is left off. */
internal const val HIDE_NEW_COLLECTION = "$SAVED_HOME->hideNewCollection()I"

/** Whether a Saved screen with nothing under it shows its back arrow anyway: the bookmark's does. */
internal const val SHOW_BACK = "$SAVED_HOME->showBack(Ljava/lang/Object;)Z"

/** Takes the Reels tab out of the tabbed Saved screen's list of its tabs (#810). */
internal const val SAVED_TABS = "$SAVED_HOME->tabs(Ljava/util/List;)V"

/** Whether the Saved bookmark at the top of the Search tab is left off (#810). */
internal const val HIDE_EXPLORE_SAVED = "$SAVED_HOME->hideExploreSaved()I"

/** Names of some of Saved's tabs, which the tabbed screen's list of its tabs loads together. */
internal val SAVED_TAB_NAMES = listOf("ALL", "REELS", "COLLECTIONS")

/** The Search tab's top bar. Its layout names it, so it keeps its name. */
internal const val EXPLORE_ACTION_BAR = "Lcom/instagram/discovery/actionbar/ExploreActionBar;"

/**
 * The stub the Search tab's top bar turns into its Saved bookmark (`explore_action_bar_secondary_button_stub`),
 * the same id in both 457 builds (0x7f0b1680 in 456).
 */
internal const val EXPLORE_SAVED_STUB = 0x7f0b166a

/** The tab the newer Saved screen opens on. Only that screen and the menu's maker of it load the key. */
internal const val INITIAL_TAB = "SaveFragment.ARGUMENT_SAVED_HOME_INITIAL_TAB"

/**
 * The screen Instagram's own Saved link opens. The manifest names it, so it keeps its name within a
 * version, but 457 moved it into `com.instagram.save.urlhandlers.saved` (`com.instagram.urlhandlers.saved`
 * through 456.1). The extension starts it by name and knows both.
 */
internal const val SAVED_LINK_SCREEN = "Lcom/instagram/save/urlhandlers/saved/SavedUrlHandlerActivity;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"

private fun refuse(why: String): Nothing = throw PatchException("$SAVED_PATCH: $why")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)
private fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.isStaticCall() = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE
private fun String.words() = if (this == "J" || this == "D") 2 else 1

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.name == method.name && it.parameters() == method.parameters() }

/**
 * The two Saved screens and what makes them. [menuMaker] is the static `(UserSession, tab)Fragment`
 * the profile menu's Saved calls: it makes the [tabbed] screen, or, for an account Instagram hasn't
 * given that screen, the [grid] through [gridMaker], the same call the Saved link's screen makes.
 */
internal class SavedScreens(
    val menuMaker: String,
    val tabbed: String,
    val gridMaker: String,
    val grid: String,
)

/**
 * Where the Saved link's screen has made its grid: [insertAt] is right after the grid is in [result].
 * [self] and [session] hold the screen and its session, and [scratch] is free there.
 */
internal class LinkScreen(
    val method: MutableMethod,
    val menuMaker: String,
    val insertAt: Int,
    val self: Int,
    val session: Int,
    val result: Int,
    val scratch: Int,
)

/**
 * The + on one Saved screen's top bar: the instructions from [from] through [to] make and add it,
 * [scratch] is free at both ends, and [label] is the last resource id loaded on the way.
 */
internal class NewCollectionButton(
    val screen: String,
    val method: MutableMethod,
    val from: Int,
    val to: Int,
    val scratch: Int,
    val label: Int?,
)

/**
 * Where the tabbed screen's top bar sets its back arrow: [call] at [at] hands the bar [flag], yes when
 * something sits under the screen. [self] holds the screen.
 */
internal class BackArrow(
    val screen: String,
    val method: MutableMethod,
    val at: Int,
    val call: Instruction,
    val flag: Int,
    val self: Int,
)

/** The tabbed Saved screen's method making the list of its tabs, and each [returns] handing it back (#810). */
internal class SavedTabs(val screen: String, val method: MutableMethod, val returns: List<Int>)

/**
 * Where the Search tab's top bar makes its Saved bookmark (#810): [at] reads the field holding the
 * bookmark into [scratch], the bar makes and keeps one when that's null, and goes on at [skip] either
 * way. [scratch] is free from [skip] on.
 */
internal class ExploreSaved(val method: MutableMethod, val at: Int, val skip: Int, val scratch: Int)

/** What [hookSavedHome] changed. */
internal class SavedHomeHooks(
    val opensOnAll: Boolean,
    val newCollectionScreens: Int,
    val backArrow: Boolean,
    val reelsTab: Boolean,
    val exploreSaved: Boolean,
)

/**
 * Finds every change first, and each one that can't be read leaves Instagram's code as it is, with a
 * warning in the patch log. The Search tab's Saved doesn't need the Saved screens, so it goes in
 * without them.
 */
internal fun BytecodePatchContext.hookSavedHome(): SavedHomeHooks {
    val explore = try {
        findExploreSaved()
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. The Search tab keeps its Saved.")
        null
    }
    val screens = try {
        findSavedScreens()
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. The bookmark opens the collections grid, the + stays, and Saved keeps its Reels tab.")
        explore?.let(::hookExploreSaved)
        return SavedHomeHooks(false, 0, false, false, explore != null)
    }
    val tabs = try {
        findSavedTabs(screens)
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. Saved keeps its Reels tab.")
        null
    }
    val link = try {
        findLinkScreen(screens)
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. The bookmark opens the collections grid.")
        null
    }
    val buttons = findNewCollectionButtons(screens)
    // The arrow only matters on the screen the link's hook hands the bookmark.
    val back = link?.let {
        try {
            findBackArrow(screens.tabbed)
        } catch (refusal: PatchException) {
            patchLog.warning("${refusal.message}. The bookmark's Saved has no back arrow, and Back still closes it.")
            null
        }
    }
    link?.let(::hookLinkScreen)
    buttons.forEach(::hookNewCollectionButton)
    // Last, and found again by its instruction: the + may have gone in ahead of it in the same method.
    back?.let(::hookBackArrow)
    tabs?.let(::hookSavedTabs)
    explore?.let(::hookExploreSaved)
    return SavedHomeHooks(link != null, buttons.size, back != null, tabs != null, explore != null)
}

/**
 * The tabbed screen's list of its tabs: its one instance `()List` or `()ArrayList` loading every name
 * in [SAVED_TAB_NAMES]. Each of its returns gets the hook, so none may sit in a try block, where the
 * hook's call would reach a handler.
 */
internal fun BytecodePatchContext.findSavedTabs(screens: SavedScreens): SavedTabs {
    val owner = classDefByOrNull(screens.tabbed) ?: refuse("this build has no ${screens.tabbed}")
    val lists = owner.methods.filter { method ->
        !method.isStatic() && method.parameters().isEmpty() && method.implementation != null &&
            (method.returnType == ARRAY_LIST || method.returnType == LIST) && SAVED_TAB_NAMES.all { loadsString(method, it) }
    }
    val method = lists.singleOrNull() ?: refuse("expected one list of tabs in ${screens.tabbed}, found ${lists.size}")
    val where = "${screens.tabbed}->${method.name}"
    val code = method.code()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuse("$where never hands its tabs back")
    val (address, _) = addresses(code)
    val tried = method.implementation!!.tryBlocks.any { block ->
        returns.any { address[it] >= block.startCodeAddress && address[it] < block.startCodeAddress + block.codeUnitCount }
    }
    if (tried) refuse("$where hands its tabs back inside a try block")
    return SavedTabs(screens.tabbed, mutable(method), returns)
}

/**
 * The Search tab's top bar makes its Saved bookmark in the one method loading [EXPLORE_SAVED_STUB]:
 * it reads the field holding the bookmark, and when that's null finds the stub, inflates it and
 * keeps it in the field, then goes on where it goes when the field isn't null. The hook skips that
 * block, so the field stays null, and every other read of it has to check for null first.
 */
internal fun BytecodePatchContext.findExploreSaved(): ExploreSaved {
    val bar = classDefByOrNull(EXPLORE_ACTION_BAR) ?: refuse("this build has no $EXPLORE_ACTION_BAR")
    val makers = bar.methods.filter { method -> method.code().any { it.loads(EXPLORE_SAVED_STUB) } }
    val method = makers.singleOrNull() ?: refuse("expected one method of $EXPLORE_ACTION_BAR loading the Saved stub, found ${makers.size}")
    val where = "$EXPLORE_ACTION_BAR->${method.name}"
    val code = method.code()
    val loads = code.indices.filter { code[it].loads(EXPLORE_SAVED_STUB) }
    val id = loads.singleOrNull() ?: refuse("$where loads the Saved stub ${loads.size} times")
    val check = code.getOrNull(id - 1)
    val read = code.getOrNull(id - 2)
    if (check?.opcode != Opcode.IF_NEZ || read?.opcode != Opcode.IGET_OBJECT) refuse("$where doesn't check for its Saved bookmark before making it")
    val field = (read as ReferenceInstruction).reference as FieldReference
    val scratch = (read as TwoRegisterInstruction).registerA
    if (field.definingClass != EXPLORE_ACTION_BAR || (check as OneRegisterInstruction).registerA != scratch) {
        refuse("$where checks something other than its own field before making its Saved bookmark")
    }
    val (address, index) = addresses(code)
    val skip = index[address[id - 1] + (check as OffsetInstruction).codeOffset] ?: refuse("$where branches somewhere it can't read")
    if (skip <= id) refuse("$where branches back from its Saved bookmark's check")
    if ((id until skip).none { code[it].opcode == Opcode.IPUT_OBJECT && code[it].field() == field.text() }) {
        refuse("$where doesn't keep the Saved bookmark it makes")
    }
    val at = id - 2
    if (at in method.jumpTargets()) refuse("$where jumps to its Saved bookmark's check")
    if (scratch > 15) refuse("$where checks its Saved bookmark past v15")
    if (readers(code, skip - 1, scratch).isNotEmpty()) refuse("$where reads v$scratch after its Saved bookmark")
    val unchecked = bar.methods.filter { other ->
        val body = other.code()
        val same = other.name == method.name && other.parameters() == method.parameters()
        body.indices.any { spot ->
            body[spot].opcode == Opcode.IGET_OBJECT && body[spot].field() == field.text() && !(same && spot == id - 2) &&
                !body.checksForNull(spot)
        }
    }
    if (unchecked.isNotEmpty()) refuse("$EXPLORE_ACTION_BAR reads its Saved bookmark without checking for null in ${unchecked.map { it.name }}")
    return ExploreSaved(mutable(method), at, skip, scratch)
}

/** Whether the instruction after [at], a field read, tests the register it read for null. */
private fun List<Instruction>.checksForNull(at: Int): Boolean {
    val read = (this[at] as TwoRegisterInstruction).registerA
    val test = getOrNull(at + 1) ?: return false
    return (test.opcode == Opcode.IF_EQZ || test.opcode == Opcode.IF_NEZ) && (test as OneRegisterInstruction).registerA == read
}

private fun Instruction.loads(literal: Int) = (this as? NarrowLiteralInstruction)?.narrowLiteral == literal
private fun Instruction.field() = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.text()
private fun FieldReference.text() = "$definingClass->$name:$type"

/** Hands each list of tabs to [SAVED_TABS] on its way out, on the return's own label. */
internal fun hookSavedTabs(found: SavedTabs) {
    val method = found.method
    found.returns.sortedDescending().forEach { at ->
        val list = (method.getInstruction(at) as OneRegisterInstruction).registerA
        val call = if (list > 15) "invoke-static/range { v$list .. v$list }, $SAVED_TABS" else "invoke-static { v$list }, $SAVED_TABS"
        method.addInstructionsAtControlFlowLabel(at, call)
    }
}

/** Asks the extension before the Search tab's top bar looks for its Saved bookmark, and skips making it when it's left off. */
internal fun hookExploreSaved(found: ExploreSaved) {
    val method = found.method
    method.addInstructionsWithLabels(
        found.at,
        """
            invoke-static { }, $HIDE_EXPLORE_SAVED
            move-result v${found.scratch}
            if-nez v${found.scratch}, :instagram_no_saved
        """,
        ExternalLabel("instagram_no_saved", method.getInstruction(found.skip)),
    )
}

/**
 * The menu's maker is the one static `(UserSession, X)Fragment` loading [INITIAL_TAB]. The screen it
 * makes with new-instance, or through its own class's one-parameter static factory (457's armeabi-v7a
 * moves the construction into 0L1R.A00), is the tabbed one. The static `(X, String, Z, Z, Z)` it calls
 * otherwise makes the grid.
 */
internal fun BytecodePatchContext.findSavedScreens(): SavedScreens {
    val makers = classesLoadingString(INITIAL_TAB).flatMap { owner ->
        owner.methods.filter { method ->
            method.isStatic() && method.returnType == FRAGMENT && method.parameters().size == 2 &&
                method.parameters()[0] == USER_SESSION && loadsString(method, INITIAL_TAB)
        }
    }
    val maker = makers.singleOrNull() ?: refuse("expected one maker of the tabbed Saved screen, found ${makers.size}")
    val code = maker.code()
    val built = code.filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }
    val factories = code.filter { it.isStaticCall() }.mapNotNull { it.called() }
        .filter { it.parameterTypes.size == 1 && it.returnType == it.definingClass }.map { it.returnType }
    val made = (built + factories).distinct()
    val tabbed = made.singleOrNull() ?: refuse("expected ${maker.descriptor()} to make one screen, found ${made.size}")
    val grids = code.filter { it.isStaticCall() }.mapNotNull { it.called() }.filter { call ->
        val parameters = call.parameterTypes.map(CharSequence::toString)
        parameters.size == 5 && parameters[1] == STRING && parameters.drop(2).all { it == "Z" } &&
            call.returnType.startsWith("L") && call.returnType != tabbed
    }.distinctBy { it.descriptor() }
    val gridMaker = grids.singleOrNull() ?: refuse("expected ${maker.descriptor()} to make one other Saved screen, found ${grids.size}")
    return SavedScreens(maker.descriptor(), tabbed, gridMaker.descriptor(), gridMaker.returnType)
}

/**
 * The Saved link's screen makes the grid in one instance method, for the session it's handed. The
 * hook goes right after the grid is in its register, and every later read of that register has to
 * take any Fragment, since it holds the tabbed screen from there when the bookmark started it.
 */
internal fun BytecodePatchContext.findLinkScreen(screens: SavedScreens): LinkScreen {
    val screen = classDefByOrNull(SAVED_LINK_SCREEN) ?: refuse("this build has no $SAVED_LINK_SCREEN")
    val makers = screen.methods.filter { method -> method.code().any { it.called()?.descriptor() == screens.gridMaker } }
    val method = makers.singleOrNull() ?: refuse("expected one method of $SAVED_LINK_SCREEN making the grid, found ${makers.size}")
    val where = "$SAVED_LINK_SCREEN->${method.name}"
    if (method.isStatic()) refuse("$where is static")
    val code = method.code()
    val call = code.indexOfFirst { it.called()?.descriptor() == screens.gridMaker }
    val result = (code.getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA
        ?: refuse("$where drops the grid it makes")
    val insertAt = call + 2
    if (insertAt >= code.size || insertAt in method.jumpTargets()) refuse("$where jumps to right after it makes the grid")

    val registers = method.implementation!!.registerCount
    val self = registers - method.parameters().sumOf { it.words() } - 1
    var next = self + 1
    var session = -1
    method.parameters().forEach { type ->
        if (type == USER_SESSION && session < 0) session = next
        next += type.words()
    }
    if (session < 0) refuse("$where isn't handed a session")
    if (arguments(code[call]).firstOrNull() != session) refuse("$where makes the grid for another session than its own")
    if ((0 until call).any { code[it].writes(session) || code[it].writes(self) }) refuse("$where writes over its session or itself")

    val takers = readers(code, insertAt - 1, result).map { at ->
        val reader = code[at]
        val target = reader.called() ?: refuse("$where reads the grid outside a call")
        val slots = (if (reader.isStaticCall()) emptyList() else listOf(target.definingClass)) +
            target.parameterTypes.map(CharSequence::toString)
        slots.getOrNull(arguments(reader).indexOf(result)) ?: refuse("$where hands the grid on in a way it can't read")
    }
    if (takers.any { it != FRAGMENT && it != OBJECT }) refuse("$where uses the grid as more than a Fragment: ${takers.distinct()}")
    if (self > 15 || session > 15 || result > 15) refuse("$where keeps itself, its session or the grid past v15")

    val scratch = (0..minOf(15, registers - 1)).firstOrNull { candidate ->
        candidate != self && candidate != session && candidate != result && readers(code, insertAt - 1, candidate).isEmpty()
    } ?: refuse("$where has no free register at v15 or below")
    return LinkScreen(mutable(method), screens.menuMaker, insertAt, self, session, result, scratch)
}

/**
 * Each Saved screen sets up its top bar in one instance method taking the bar. Its first static call
 * of `(OnClickListener, X, bar)V` adds the +, with a configuration a static `()X` makes just before.
 * A screen whose bar can't be read keeps its +, with a warning. When both screens are read, their
 * first buttons have to load the same label, or neither is changed.
 */
internal fun BytecodePatchContext.findNewCollectionButtons(screens: SavedScreens): List<NewCollectionButton> {
    val found = listOf(screens.tabbed, screens.grid).distinct().mapNotNull { screen ->
        try {
            findNewCollectionButton(screen)
        } catch (refusal: PatchException) {
            patchLog.warning("${refusal.message}. The + stays on that Saved screen.")
            null
        }
    }
    val labels = found.map { it.label }.distinct()
    if (found.size > 1 && (labels.size != 1 || labels.single() == null)) {
        patchLog.warning("$SAVED_PATCH: the Saved screens' first top bar buttons load different labels ($labels), so the + stays")
        return emptyList()
    }
    return found
}

/** The one instance `(bar)V` of [screen] that adds a button to the bar it's handed. */
private fun BytecodePatchContext.topBar(screen: String): Method {
    val owner = classDefByOrNull(screen) ?: refuse("this build has no $screen")
    val bars = owner.methods.filter { method ->
        !method.isStatic() && method.returnType == "V" && method.parameters().size == 1 && method.implementation != null &&
            method.code().any { it.addsButtonTo(method.parameters()[0]) }
    }
    return bars.singleOrNull() ?: refuse("expected one top bar setup in $screen, found ${bars.size}")
}

internal fun BytecodePatchContext.findNewCollectionButton(screen: String): NewCollectionButton {
    val bar = topBar(screen)
    val where = "$screen->${bar.name}"
    val code = bar.code()
    val add = code.indexOfFirst { it.addsButtonTo(bar.parameters()[0]) }
    val config = arguments(code[add]).getOrNull(1) ?: refuse("$where adds its first button without a configuration")
    val configType = code[add].called()!!.parameterTypes[1].toString()
    val made = (add - 1 downTo 0).firstOrNull { code[it].writes(config) } ?: refuse("$where never makes its first button's configuration")
    if (code[made].opcode != Opcode.MOVE_RESULT_OBJECT || made == 0) refuse("$where gets its first button's configuration from somewhere else")
    val maker = code[made - 1].called()
    if (!code[made - 1].isStaticCall() || maker == null || maker.parameterTypes.isNotEmpty() || maker.returnType != configType) {
        refuse("$where doesn't make its first button's configuration with a static call")
    }
    val from = made - 1
    val to = add
    if (to + 1 >= code.size) refuse("$where ends at its first button")
    val targets = bar.jumpTargets()
    if ((from..to).any { it in targets }) refuse("$where jumps into its first button")
    if ((from..to).any { code[it] is OffsetInstruction || code[it].opcode in ENDS }) refuse("$where branches while it adds its first button")
    val registers = bar.implementation!!.registerCount
    val written = (0 until registers).filter { register -> (from..to).any { code[it].writes(register) } }
    if (written.any { readers(code, to, it).isNotEmpty() }) refuse("$where reads what its first button leaves behind")
    val scratch = (0..minOf(15, registers - 1)).firstOrNull { candidate ->
        readers(code, from - 1, candidate).isEmpty() && readers(code, to, candidate).isEmpty()
    } ?: refuse("$where has no free register at v15 or below")
    val label = (from..to).mapNotNull { at ->
        (code[at] as? NarrowLiteralInstruction)?.narrowLiteral?.takeIf { it ushr 24 == 0x7f }
    }.lastOrNull()
    return NewCollectionButton(screen, mutable(bar), from, to, scratch, label)
}

/**
 * The tabbed screen's top bar shows its back arrow when its fragment stack holds anything: it reads
 * the parent fragment manager's count, turns that into a yes or no with a static `(I)Z`, and hands
 * the answer to the bar's `(Z)V`. The hook goes right in front of that call. 456.1's armeabi-v7a
 * build reads the count in a static `(Fragment)I` of its own, which counts as the read.
 */
internal fun BytecodePatchContext.findBackArrow(screen: String): BackArrow {
    val bar = topBar(screen)
    val where = "$screen->${bar.name}"
    val code = bar.code()
    fun readsParentStack(call: MethodReference) = classDefByOrNull(call.definingClass)?.methods?.singleOrNull {
        it.name == call.name && it.parameters() == listOf(FRAGMENT) && it.returnType == "I"
    }?.code()?.any { it.called()?.let { inner -> inner.definingClass == FRAGMENT && inner.name == "getParentFragmentManager" } == true } == true
    val stack = code.indexOfFirst { instruction ->
        val call = instruction.called() ?: return@indexOfFirst false
        call.definingClass == FRAGMENT && call.name == "getParentFragmentManager" ||
            instruction.isStaticCall() && call.returnType == "I" && call.parameterTypes.map(CharSequence::toString) == listOf(FRAGMENT) &&
            readsParentStack(call)
    }
    if (stack < 0) refuse("$where never reads its fragment stack")
    val sets = (stack + 1..minOf(code.size - 1, stack + STACK_TO_ARROW)).filter { code.setsArrowAt(it, bar.parameters()[0]) }
    val at = sets.firstOrNull() ?: refuse("$where doesn't set its back arrow from its fragment stack")
    val flag = (code[at - 1] as OneRegisterInstruction).registerA
    // An instance method handed one object: the screen sits right below the bar.
    val self = bar.implementation!!.registerCount - 2
    if (at in bar.jumpTargets()) refuse("$where jumps to its back arrow")
    if ((0 until at).any { code[it].writes(self) }) refuse("$where writes over itself")
    if (self > 15 || flag > 15) refuse("$where keeps itself or the arrow's answer past v15")
    val method = mutable(bar)
    return BackArrow(screen, method, at, method.getInstruction(at), flag, self)
}

/** How far past the stack read the bar's arrow is set, at most. */
private const val STACK_TO_ARROW = 8

/** Whether [at] hands [bar]'s `(Z)V` an answer a static `(I)Z` has just made. */
private fun List<Instruction>.setsArrowAt(at: Int, bar: String): Boolean {
    if (at < 2) return false
    val set = this[at]
    val call = set.called() ?: return false
    if (set.opcode != Opcode.INVOKE_VIRTUAL || call.definingClass != bar || call.returnType != "V" ||
        call.parameterTypes.map(CharSequence::toString) != listOf("Z")
    ) return false
    val answer = this[at - 1]
    if (answer.opcode != Opcode.MOVE_RESULT || arguments(set).getOrNull(1) != (answer as OneRegisterInstruction).registerA) return false
    val test = this[at - 2].called() ?: return false
    return this[at - 2].isStaticCall() && test.returnType == "Z" && test.parameterTypes.map(CharSequence::toString) == listOf("I")
}

/** A static `(OnClickListener, X, bar)V` call adding a button to the [bar] it's handed. */
private fun Instruction.addsButtonTo(bar: String): Boolean {
    if (!isStaticCall()) return false
    val call = called() ?: return false
    val parameters = call.parameterTypes.map(CharSequence::toString)
    return call.returnType == "V" && parameters.size == 3 && parameters[0] == CLICK_LISTENER && parameters[2] == bar
}

/**
 * When the bookmark started the link's screen, it also makes the menu's screen and keeps that one
 * unless it's another grid. Otherwise nothing changes.
 */
internal fun hookLinkScreen(found: LinkScreen) {
    val method = found.method
    method.addInstructionsWithLabels(
        found.insertAt,
        """
            invoke-static { v${found.self} }, $FROM_BOOKMARK
            move-result v${found.scratch}
            if-eqz v${found.scratch}, :instagram_saved
            const/4 v${found.scratch}, 0x0
            invoke-static { v${found.session}, v${found.scratch} }, ${found.menuMaker}
            move-result-object v${found.scratch}
            invoke-static { v${found.scratch}, v${found.result} }, $PICK_SCREEN
            move-result-object v${found.result}
            check-cast v${found.result}, $FRAGMENT
        """,
        ExternalLabel("instagram_saved", method.getInstruction(found.insertAt)),
    )
}

/** Asks the extension before the + is made, and skips making and adding it when it's left off. */
internal fun hookNewCollectionButton(found: NewCollectionButton) {
    val method = found.method
    method.addInstructionsWithLabels(
        found.from,
        """
            invoke-static { }, $HIDE_NEW_COLLECTION
            move-result v${found.scratch}
            if-nez v${found.scratch}, :instagram_no_button
        """,
        ExternalLabel("instagram_no_button", method.getInstruction(found.to + 1)),
    )
}

/**
 * When Instagram leaves the arrow off, asks the extension and hands the bar its answer instead. An
 * arrow Instagram shows is kept without asking.
 */
internal fun hookBackArrow(found: BackArrow) {
    val method = found.method
    val at = method.implementation!!.instructions.indexOfFirst { it === found.call }
    if (at < 0) throw PatchException("$SAVED_PATCH: lost the back arrow's call in ${found.screen}")
    method.addInstructionsWithLabels(
        at,
        """
            if-nez v${found.flag}, :instagram_back
            invoke-static { v${found.self} }, $SHOW_BACK
            move-result v${found.flag}
        """,
        ExternalLabel("instagram_back", found.call),
    )
}

private val ENDS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.THROW)
private val JUMPS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

private fun arguments(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> emptyList()
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

private fun Instruction.reads(register: Int): Boolean {
    if (this is FiveRegisterInstruction || this is RegisterRangeInstruction) return register in arguments(this)
    return (!opcode.setsRegister() && this is OneRegisterInstruction && registerA == register) ||
        (this is TwoRegisterInstruction && registerB == register) ||
        (this is ThreeRegisterInstruction && registerC == register)
}

private fun addresses(code: List<Instruction>): Pair<IntArray, Map<Int, Int>> {
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    return address to (0 until code.size).associateBy { address[it] }
}

/** Every instruction reachable from [at] that reads [register] before anything writes it again, in order. */
private fun readers(code: List<Instruction>, at: Int, register: Int): List<Int> {
    val (address, index) = addresses(code)
    val found = sortedSetOf<Int>()
    val seen = HashSet<Int>()
    val work = ArrayDeque(listOf(at + 1))
    while (work.isNotEmpty() && seen.size < 600) {
        val next = work.removeFirst()
        if (next !in code.indices || !seen.add(next)) continue
        val instruction = code[next]
        if (instruction.reads(register)) found += next
        if (instruction.writes(register) || instruction.opcode in ENDS) continue
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = index[address[next] + (instruction as OffsetInstruction).codeOffset]?.let { code[it] } as? SwitchPayload
            payload?.switchElements?.forEach { case -> index[address[next] + case.offset]?.let(work::addLast) }
        } else if (instruction is OffsetInstruction) {
            index[address[next] + instruction.codeOffset]?.let(work::addLast)
            if (instruction.opcode in JUMPS) continue
        }
        work.addLast(next + 1)
    }
    return found.toList()
}
