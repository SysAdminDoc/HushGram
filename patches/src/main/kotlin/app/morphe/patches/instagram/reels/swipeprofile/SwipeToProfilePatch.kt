/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.swipeprofile

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Swipe left to open the profile"
internal const val SWIPE_TO_PROFILE = "$EXTENSION_PACKAGE/reels/SwipeToProfile;"
internal const val SWIPE_ALLOWED_HOOK = "$SWIPE_TO_PROFILE->allow(I)Z"
internal const val TAB_CHANGING_HOOK = "$SWIPE_TO_PROFILE->tabChanging(Ljava/lang/Object;)V"
internal const val TAB_CHANGED_HOOK = "$SWIPE_TO_PROFILE->tabChanged(Ljava/lang/Object;)V"
internal const val CHILD_SCROLLS_HOOK = "$SWIPE_TO_PROFILE->childScrolls(Landroid/view/View;IIIII)Z"
internal const val PAGER_INPUT_HOOK = "$SWIPE_TO_PROFILE->pagerInput(Ljava/lang/Object;I)V"
internal const val TOUCHED_HOOK = "$SWIPE_TO_PROFILE->touched(Landroid/view/View;Landroid/view/MotionEvent;)V"
internal const val RIGHT_PANEL_HOOK = "$SWIPE_TO_PROFILE->leaveOutRightPanel(ILjava/lang/Object;)Z"
internal const val CLOSES_PROFILE_HOOK = "$SWIPE_TO_PROFILE->closesProfile(Landroid/app/Activity;Landroid/view/MotionEvent;)Z"
internal const val PROFILE_RESUMED_HOOK = "$SWIPE_TO_PROFILE->profileResumed(Ljava/lang/Object;)V"
internal const val PROFILE_PAUSED_HOOK = "$SWIPE_TO_PROFILE->profilePaused(Ljava/lang/Object;)V"
internal const val SWIPE_TARGETS = "$EXTENSION_PACKAGE/reels/SwipeToProfileTargets;"

/** The marker, less its release, of the Reels viewer's check of whether a swipe may go from a reel to its side panel. */
internal const val SWIPE_ALLOWED = "ClipsViewerSwipeNavigationDelegate_isSwipeFromReelsToActionAllowed"

/** The view that slides a tab's side panels in under a sideways swipe. Instagram keeps its name. */
internal const val SWIPE_CONTAINER = "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"

/** What the container's panel switch throws when it's asked to turn the middle panel off. */
internal const val CENTER_PANEL = "No support for disabling center panel"

/** The container's setter of whether its walk passes over a pager whose input is off. Instagram keeps its name. */
internal const val PAGER_CHECK_SETTER = "setShouldCheckViewPagerInputEnabledForScroll"

/** The profile screen, which opens in a screen of its own once the panel has slid in. Instagram keeps its name. */
internal const val PROFILE_FRAGMENT = "Lcom/instagram/profile/fragment/UserDetailFragment;"

/** The pager the main tabs and the Reels and Friends tabs slide in. androidx keeps its name. */
internal const val VIEW_PAGER = "Landroidx/viewpager2/widget/ViewPager2;"
private const val SET_USER_INPUT = "setUserInputEnabled"

private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val VIEW = "Landroid/view/View;"
private const val ANY = "Ljava/lang/Object;"
private val WALK = listOf("Landroid/view/View;", "I", "I", "I", "Z", "Z")
private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/**
 * Lets a left swipe on a reel open its creator's profile, as older Instagram builds did. The profile
 * loads into the container's right panel, which the swipe setup leaves out on accounts with the new
 * tab bar, so the setup's answer goes through the extension (#283). The container's walk of the
 * views under the finger gave the swipe to a pager (#245), so that walk, the call setting the tabs'
 * pager input and each touch event the container sees go through the extension too. Once the panel
 * has slid in, Instagram opens the profile in a screen of its own with no swipe back, so the
 * profile's resume and pause and every screen's touch dispatch go through the extension as well,
 * which lets a right swipe on that profile go back to the reel. In the default selection with its
 * switch off: it changes what a swipe on a reel does, so it's the user's pick.
 */
@Suppress("unused")
val swipeToProfilePatch = bytecodePatch(
    name = "Swipe left to open the profile",
    description = "Makes a left swipe on a reel open the profile of the person who posted it, like Instagram used " +
        "to. Tap Friends at the top to reach your friends' reels. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("swipeToProfile")
        val gate = findSwipeGate()
        val panel = findProfilePanel()
        val setUp = findPanelSetUp(panel)
        val pagers = findPagerHandling(panel.touch)
        val close = findProfileClose()
        hookSwipeGate(gate)
        hookPagerHandling(panel.touch, pagers)
        hookProfilePanel(panel)
        hookPanelSetUp(setUp)
        hookProfileClose(close)
        enableStatus("swipeToProfile")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** Where the check reads its last gate: its class, name, the move-result of the gate's answer and the register it fills. */
internal class SwipeGate(
    val type: String,
    val name: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * Finds the gate that decides, for an ordinary reel, whether a swipe may go on to the side panel.
 * The Reels viewer's check is the one instance method marked [SWIPE_ALLOWED], taking nothing and
 * answering a boolean. Its last gate asks one call taking just the user session and answering a
 * boolean, then branches on the answer with an if-nez to the "allowed" return. The check has to hold
 * exactly one such call, its move-result has to come straight after it and the branch straight after
 * that, on the same register, with nothing jumping between them.
 */
internal fun BytecodePatchContext.findSwipeGate(): SwipeGate {
    val holders = typesMarked(SWIPE_ALLOWED)
    val checks = holders.flatMap { type ->
        classDefBy(type).methods.filter { SWIPE_ALLOWED in it.markers() }
    }
    val check = checks.singleOrNull() ?: refuse("expected one check marked $SWIPE_ALLOWED, found ${checks.size}")
    val where = "${check.definingClass}->${check.name}"
    if (AccessFlags.STATIC.isSet(check.accessFlags) || check.returnType != "Z" || check.parameterTypes.isNotEmpty()) {
        refuse("$where isn't an instance method without parameters answering a boolean")
    }
    val code = check.implementation!!.instructions.toList()
    val asks = code.indices.filter { code[it].isSessionGate() }
    val ask = asks.singleOrNull() ?: refuse("expected $where to make one call taking only the user session and answering a boolean, found ${asks.size}")
    val result = code.getOrNull(ask + 1) as? OneRegisterInstruction
    val branch = code.getOrNull(ask + 2) as? OneRegisterInstruction
    if (result == null || branch == null || result.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_NEZ ||
        branch.registerA != result.registerA
    ) {
        refuse("$where doesn't branch on the answer of that call right after it at instruction $ask")
    }
    if (result.registerA > 255) refuse("$where keeps the answer past v255, out of reach of a move-result")
    if (ask + 2 in check.jumpTargets()) refuse("something in $where jumps between the gate's answer and the branch on it")
    return SwipeGate(check.definingClass, check.name, ask + 1, result.registerA)
}

/** Whether this is an invoke-virtual of a method taking only the user session and answering a boolean. */
private fun Instruction.isSessionGate(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf(SESSION)
}

/**
 * Hands the gate's answer to the extension right after it is read, and branches on what comes back.
 * The extension answers true while the switch is on, which has Instagram take the profile panel for
 * an ordinary reel, and Instagram's own answer otherwise. The call takes the register as a range
 * because it can sit above v15.
 */
internal fun BytecodePatchContext.hookSwipeGate(gate: SwipeGate) {
    val method: MutableMethod = mutableClassDefBy(gate.type).methods.single {
        it.name == gate.name && SWIPE_ALLOWED in it.markers()
    }
    val register = "v${gate.register}"
    method.addInstructions(
        gate.moveResult + 1,
        """
            invoke-static/range { $register .. $register }, $SWIPE_ALLOWED_HOOK
            move-result $register
        """,
    )
}

/**
 * The Reels tab's profile panel and what reaches it. On 452: the container's onInterceptTouchEvent
 * hands the panel switch (A05(ZF)V) the answer of a panel gate (07xk.A08), which turns the left
 * panel, the camera, on or off. The profile loads into the right panel, which sits in the
 * container's [panel] field (A0E, a 00VQ) that [setter] (A02) fills from its fifth parameter, and
 * which keeps its on flag and its position. The tab host's tab-change method (00M2.A0O) is the
 * gate's one other caller; the host answers its tab with [currentTab] (A09), compares it with
 * [reelsTab] (08kM.A08), and reaches the container through its [controller] field (A03, a 004N) and
 * that one's [container] field (A0E).
 */
internal class ProfilePanel(
    val touch: Method,
    val toggle: MethodReference,
    val panel: FieldReference,
    val panelOn: FieldReference,
    val position: FieldReference,
    val setter: MethodReference,
    val tabChange: Method,
    val currentTab: MethodReference,
    val reelsTab: FieldReference,
    val controller: FieldReference,
    val container: FieldReference,
)

/**
 * Finds the Reels tab's profile panel. Instagram keeps it on for the Reels tab only on accounts
 * without the new tab bar, and only when its server says so. Everything here is checked against
 * something else that reads it, and has to be public, since the extension's stubs read it.
 *
 * - The panel switch is the container's one (boolean, float) method saying [CENTER_PANEL].
 * - The gate is the one call in the container's onInterceptTouchEvent taking only the session and
 *   answering a boolean whose move-result the panel switch takes, straight after, as its boolean.
 * - A panel's on flag is the boolean field the touch check reads last before the gate.
 * - The panel setter is the container's one static method taking its holder, the container, three
 *   panels (left, middle, right) and two doubles. The panel is the container field it fills from the
 *   right one; the switch reads the same field. The panel keeps its position in its one float
 *   field, which the switch reads too.
 * - The tab-change method is the one other method calling the gate. It is an instance method
 *   answering nothing whose first two parameters are a tab. Before the gate it asks the host's tab
 *   (a call of the host taking nothing and answering a tab) and loads the tab it's compared with,
 *   which the touch check loads as well. After the gate it fetches the container with a static call
 *   taking the controller, which the host keeps in one field and which keeps the container in one.
 *   The controller is the panel setter's first parameter.
 */
internal fun BytecodePatchContext.findProfilePanel(): ProfilePanel {
    val container = classDefByOrNull(SWIPE_CONTAINER) ?: refuse("this Instagram build has no $SWIPE_CONTAINER")
    requirePublic(container)

    val toggles = container.methods.filter {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "F") && it.loads(CENTER_PANEL)
    }
    val toggleMethod = toggles.singleOrNull()
        ?: refuse("expected one (boolean, float) method of $SWIPE_CONTAINER saying \"$CENTER_PANEL\", found ${toggles.size}")
    requirePublicInstance(toggleMethod)
    val toggle = ImmutableMethodReference(toggleMethod.definingClass, toggleMethod.name, toggleMethod.parameterTypes, "V")

    val touch = container.methods.singleOrNull {
        it.name == "onInterceptTouchEvent" && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) && it.implementation != null
    } ?: refuse("$SWIPE_CONTAINER has no onInterceptTouchEvent")
    val code = touch.implementation!!.instructions.toList()
    val gates = code.indices.filter { at ->
        val result = code.getOrNull(at + 1) as? OneRegisterInstruction
        val handed = code.getOrNull(at + 2)
        code[at].isSessionGate() && result?.opcode == Opcode.MOVE_RESULT && handed?.opcode == Opcode.INVOKE_VIRTUAL &&
            handed.called()?.matches(toggle) == true && (handed as FiveRegisterInstruction).registerD == result.registerA
    }
    val gate = gates.singleOrNull()
        ?: refuse("expected $SWIPE_CONTAINER's onInterceptTouchEvent to hand the panel switch one session gate's answer, found ${gates.size}")
    val gateCall = code[gate].called()!!

    val onRead = (0 until gate).lastOrNull { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: refuse("$SWIPE_CONTAINER's onInterceptTouchEvent reads no panel's on flag before its gate")
    val panelOn = code[onRead].field()!!
    val panelType = panelOn.definingClass
    val setters = container.methods.filter { method ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && parameters.size == 7 &&
            parameters[1] == SWIPE_CONTAINER && parameters.subList(2, 5).all { it == panelType } && parameters.subList(5, 7) == listOf("D", "D")
    }
    val setterMethod = setters.singleOrNull()
        ?: refuse("expected one static method of $SWIPE_CONTAINER taking its holder, the container, three panels and two doubles, found ${setters.size}")
    // The holder, the container and the three panels take a register each and the doubles two, so the right panel is the fifth of the last nine.
    val right = setterMethod.implementation!!.registerCount - 9 + 4
    val rights = setterMethod.implementation!!.instructions.filter {
        it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == right &&
            it.field()?.let { field -> field.definingClass == SWIPE_CONTAINER && field.type == panelType } == true
    }.mapNotNull { it.field() }
    val panel = rights.singleOrNull()
        ?: refuse("expected the panel setter ${setterMethod.name} to keep its right panel in one field of the container, found ${rights.size}")
    val setter = ImmutableMethodReference(setterMethod.definingClass, setterMethod.name, setterMethod.parameterTypes, "V")
    val toggleCode = toggleMethod.implementation!!.instructions.toList()
    if (toggleCode.none { it.opcode == Opcode.IGET_OBJECT && it.field()?.matches(panel) == true }) {
        refuse("the panel switch ${toggleMethod.name} doesn't read the right panel $panel")
    }
    val panelClass = classDefByOrNull(panelType) ?: refuse("the panel type $panelType isn't in the app")
    val positions = panelClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "F" }
    val positionField = positions.singleOrNull()
        ?: refuse("expected the panel ${panelClass.type} to keep its position in one float field, found ${positions.size}")
    val position = ImmutableFieldReference(panelClass.type, positionField.name, "F")
    if (toggleCode.none { it.opcode == Opcode.IGET && it.field()?.matches(position) == true }) {
        refuse("the panel switch ${toggleMethod.name} doesn't read the panel's position $position")
    }
    requirePublic(panel)
    requirePublic(panelOn)
    requirePublic(position)

    val callers = classesCalling(gateCall.definingClass, gateCall.name).flatMap { holder ->
        holder.methods.filter { method -> method.implementation?.instructions?.any { it.called()?.matches(gateCall) == true } == true }
    }.filterNot { it.definingClass == SWIPE_CONTAINER && it.name == touch.name }
    val tabChange = callers.singleOrNull() ?: refuse("expected the panel gate $gateCall to have one caller besides the touch check, found ${callers.size}")
    val host = "${tabChange.definingClass}->${tabChange.name}"
    val parameters = tabChange.parameterTypes.map(CharSequence::toString)
    if (tabChange.returnType != "V" || parameters.size < 2 || parameters[0] != parameters[1] || !parameters[0].startsWith("L")) {
        refuse("$host, the gate's other caller, doesn't take two tabs first and answer nothing")
    }
    requirePublicInstance(tabChange)
    val tab = parameters[0]
    val body = tabChange.implementation!!.instructions.toList()
    val ask = body.indexOfFirst { it.called()?.matches(gateCall) == true }
    val currentTab = (0 until ask).reversed().firstNotNullOfOrNull { at ->
        body[at].takeIf { it.opcode == Opcode.INVOKE_VIRTUAL }?.called()
            ?.takeIf { it.definingClass == tabChange.definingClass && it.parameterTypes.isEmpty() && it.returnType == tab }
    } ?: refuse("$host doesn't ask the host's tab before the gate")
    val reelsTab = (0 until ask).reversed().firstNotNullOfOrNull { at ->
        body[at].takeIf { it.opcode == Opcode.SGET_OBJECT }?.field()?.takeIf { it.definingClass == tab && it.type == tab }
    } ?: refuse("$host loads no tab to compare with before the gate")
    if (code.none { it.opcode == Opcode.SGET_OBJECT && it.field()?.matches(reelsTab) == true }) {
        refuse("$SWIPE_CONTAINER's touch check doesn't compare with the tab $reelsTab that $host does")
    }
    val fetch = (ask + 1 until body.size).firstNotNullOfOrNull { at ->
        body[at].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.called()
            ?.takeIf { it.returnType == SWIPE_CONTAINER && it.parameterTypes.size == 1 }
    } ?: refuse("$host doesn't fetch the container after the gate")
    val controllerType = fetch.parameterTypes.single().toString()
    if (setter.parameterTypes.first().toString() != controllerType) {
        refuse("the panel setter ${setter.name} takes a ${setter.parameterTypes.first()}, not the container's holder $controllerType")
    }
    val controllers = instanceFields(classDefBy(tabChange.definingClass)).filter { it.type == controllerType }
    val controller = controllers.singleOrNull()
        ?: refuse("expected ${tabChange.definingClass} to keep its $controllerType in one field, found ${controllers.size}")
    val controllerClass = classDefByOrNull(controllerType) ?: refuse("the container's holder $controllerType isn't in the app")
    val containers = instanceFields(controllerClass).filter { it.type == SWIPE_CONTAINER }
    val containerField = containers.singleOrNull()
        ?: refuse("expected $controllerType to keep the container in one field, found ${containers.size}")
    val controllerRef = ImmutableFieldReference(controller.definingClass, controller.name, controller.type)
    val containerRef = ImmutableFieldReference(containerField.definingClass, containerField.name, containerField.type)
    requirePublic(controllerRef)
    requirePublic(containerRef)
    requirePublic(reelsTab)
    requirePublicMethod(currentTab)

    val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) refuse("$host never returns")
    if (0 in tabChange.jumpTargets()) refuse("something in $host jumps to its first instruction, where the hook goes")
    tabChange.requireThisIntact(PATCH, returns)

    return ProfilePanel(
        touch, toggle, panel, panelOn, position, setter, tabChange, currentTab, reelsTab, controllerRef, containerRef,
    )
}

/**
 * Calls [TAB_CHANGING_HOOK] first thing in the tab-change method, to turn off what HushGram turned
 * on before Instagram picks the next tab's panels, and [TAB_CHANGED_HOOK] at each of its returns, to
 * turn the panel on when the tab is Reels. A return can be a jump target, so each is replaced by the
 * call, which keeps the return's label, and a new return follows it. Then writes the stubs the
 * extension reaches Instagram's pieces through. The touch check's camera gate stays Instagram's.
 */
internal fun BytecodePatchContext.hookProfilePanel(found: ProfilePanel) {
    val change = mutable(found.tabChange)
    val returns = change.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    for (at in returns.reversed()) {
        change.replaceInstruction(at, "invoke-static/range { p0 .. p0 }, $TAB_CHANGED_HOOK")
        change.addInstruction(at + 1, "return-void")
    }
    change.addInstructions(0, "invoke-static/range { p0 .. p0 }, $TAB_CHANGING_HOOK")

    val host = found.tabChange.definingClass
    stub("controller", listOf(ANY), ANY, "check-cast p0, $host\niget-object p0, p0, ${found.controller}\nreturn-object p0")
    stub("container", listOf(ANY), ANY, "check-cast p0, ${found.controller.type}\niget-object p0, p0, ${found.container}\nreturn-object p0")
    stub("tab", listOf(ANY), ANY, "check-cast p0, $host\ninvoke-virtual { p0 }, ${found.currentTab}\nmove-result-object p0\nreturn-object p0")
    stub("reelsTab", emptyList(), ANY, "sget-object v0, ${found.reelsTab}\nreturn-object v0")
    stub("panel", listOf(ANY), ANY, "check-cast p0, $SWIPE_CONTAINER\niget-object p0, p0, ${found.panel}\nreturn-object p0")
    stub("panelOn", listOf(ANY), "Z", "check-cast p0, ${found.panelOn.definingClass}\niget-boolean p0, p0, ${found.panelOn}\nreturn p0")
    stub(
        "turn", listOf(ANY, ANY, "Z"), "V",
        """
            check-cast p0, $SWIPE_CONTAINER
            check-cast p1, ${found.position.definingClass}
            iget p1, p1, ${found.position}
            invoke-virtual { p0, p2, p1 }, ${found.toggle}
            return-void
        """,
    )
}

/**
 * Where the tab host builds the container's panels. On 452: the host's setUpSwipeNavigationController
 * (00M2.A0I) asks whether the account has the new tab bar (07xk.A05) into [register] at [answer],
 * flips it and, when it's yes, skips building the right panel unless another check says otherwise.
 * The right panel's view is the holder's [rightView] (004N.A0A), read from [holder], and may be
 * missing. The panels go to the panel setter at [setterCall].
 */
internal class PanelSetUp(
    val method: Method,
    val answer: Int,
    val register: Int,
    val holder: Int,
    val rightView: FieldReference,
    val setterCall: Int,
)

/**
 * Finds where the tab host builds the panels: the one method of the host calling the panel setter,
 * once. Before that call it asks a static call taking only the session and answering a boolean, and
 * straight after the move-result flips it (xor 1) and branches with an if-nez on it. The branch lands
 * on a read of a view field of the container's holder, checked for null right after, and the first
 * panel built after that is built on that view. Exactly one ask may look like that. The answer and
 * the holder have to sit in v0 to v15, the hook's call takes both; nothing may jump onto the flip or
 * onto the instruction after the setter call, where the hooks go, and this has to be intact there.
 */
internal fun BytecodePatchContext.findPanelSetUp(found: ProfilePanel): PanelSetUp {
    val host = classDefBy(found.tabChange.definingClass)
    val setUps = host.methods.filter { method -> method.implementation?.instructions?.any { it.called()?.matches(found.setter) == true } == true }
    val setUp = setUps.singleOrNull() ?: refuse("expected one method of ${host.type} to hand the container its panels, found ${setUps.size}")
    val where = "${setUp.definingClass}->${setUp.name}"
    val code = setUp.implementation!!.instructions.toList()
    val calls = code.indices.filter { code[it].called()?.matches(found.setter) == true }
    val setterCall = calls.singleOrNull() ?: refuse("expected $where to hand the container its panels once, found ${calls.size}")
    val holderType = found.controller.type
    val panelType = found.panelOn.definingClass
    val starts = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }

    val asks = (0 until setterCall).mapNotNull { at ->
        val call = code[at].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.called() ?: return@mapNotNull null
        if (call.returnType != "Z" || call.parameterTypes.map(CharSequence::toString) != listOf(SESSION)) return@mapNotNull null
        val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction ?: return@mapNotNull null
        val answer = result.registerA
        val flip = code.getOrNull(at + 2)
        if (flip?.opcode != Opcode.XOR_INT_LIT8 || (flip as TwoRegisterInstruction).registerA != answer || flip.registerB != answer ||
            (flip as NarrowLiteralInstruction).narrowLiteral != 1
        ) {
            return@mapNotNull null
        }
        val branch = code.getOrNull(at + 3)?.takeIf { it.opcode == Opcode.IF_NEZ && (it as OneRegisterInstruction).registerA == answer }
            ?: return@mapNotNull null
        val target = starts.indexOf(starts[at + 3] + (branch as OffsetInstruction).codeOffset).takeIf { it in code.indices } ?: return@mapNotNull null
        val read = code[target].takeIf { it.opcode == Opcode.IGET_OBJECT } ?: return@mapNotNull null
        val view = read.field()?.takeIf { it.definingClass == holderType && it.type == VIEW } ?: return@mapNotNull null
        val viewRegister = (read as TwoRegisterInstruction).registerA
        if (code.getOrNull(target + 1)?.let { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == viewRegister } != true) {
            return@mapNotNull null
        }
        val built = (target + 2 until setterCall).firstOrNull { index ->
            code[index].opcode == Opcode.INVOKE_DIRECT && code[index].called()?.let {
                it.definingClass == panelType && it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(VIEW, "F")
            } == true
        } ?: return@mapNotNull null
        if ((code[built] as FiveRegisterInstruction).registerD != viewRegister) return@mapNotNull null
        if ((target + 2 until built).any { code[it].writes(viewRegister) }) return@mapNotNull null
        PanelSetUp(setUp, at + 1, answer, read.registerB, view, setterCall)
    }
    val ask = asks.singleOrNull() ?: refuse("expected $where to leave out the right panel on one session gate's answer, found ${asks.size}")
    if (ask.register > 15 || ask.holder > 15) refuse("$where keeps the gate's answer or the holder past v15, out of reach of the hook's call")
    val targets = setUp.jumpTargets()
    if (ask.answer + 1 in targets) refuse("something in $where jumps onto the flip of the gate's answer")
    if (setterCall + 1 in targets) refuse("something in $where jumps onto the instruction after the panel setter")
    setUp.requireThisIntact(PATCH, listOf(setterCall + 1))
    requirePublic(ask.rightView)
    return ask
}

/**
 * Hands the setup's new-tab-bar answer and the holder to [RIGHT_PANEL_HOOK] right after the answer is
 * read, so the right panel is built while the switch is on, and calls [TAB_CHANGED_HOOK] on this
 * right after the panels go to the setter, which turns the right panel off away from Reels. A panel
 * starts on. Then writes the stub reading the right panel's view.
 */
internal fun BytecodePatchContext.hookPanelSetUp(found: PanelSetUp) {
    val method = mutable(found.method)
    method.addInstruction(found.setterCall + 1, "invoke-static/range { p0 .. p0 }, $TAB_CHANGED_HOOK")
    val register = "v${found.register}"
    method.addInstructions(
        found.answer + 1,
        """
            invoke-static { $register, v${found.holder} }, $RIGHT_PANEL_HOOK
            move-result $register
        """,
    )
    stub("rightView", listOf(ANY), ANY, "check-cast p0, ${found.rightView.definingClass}\niget-object p0, p0, ${found.rightView}\nreturn-object p0")
}

/** Whether this writes [register], or the pair starting below it when it's a wide write. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val target = (this as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (opcode.setsWideRegister() && target + 1 == register)
}

/**
 * How the container's touch check deals with pagers. On 452: at [walkCall] it walks the views under
 * the finger with [walk] (09bV.A00), handing it the container's flag of whether to pass over a pager
 * whose input is off (A0R) last, and yields the swipe to any that can scroll sideways. At [inputCall]
 * it turns the tabs' pager input on or off (setUserInputEnabled) as a touch starts. A pager keeps
 * whether it takes swipes in [pagerInput] (ViewPager2.A09).
 */
internal class PagerHandling(
    val walkCall: Int,
    val walk: MethodReference,
    val inputCall: Int,
    val pagerInput: FieldReference,
)

/**
 * Finds how the touch check deals with pagers. Everything here is checked against something else,
 * and the walk and the pager's input flag have to be public, since the extension's stubs reach them.
 *
 * - The flag is the one boolean field of the container its setter [PAGER_CHECK_SETTER] writes, read
 *   once in the touch check.
 * - The walk is the touch check's one static call taking a view, three ints and two booleans and
 *   answering a boolean. It comes after the flag's read, in straight code, and is handed the flag
 *   last, as read or through moves. It calls itself and asks whether a view is a [VIEW_PAGER].
 * - The input call is the touch check's one call of the pager's setUserInputEnabled.
 * - The pager's input flag is the one boolean field of the pager setUserInputEnabled writes, and the
 *   walk reads it.
 */
internal fun BytecodePatchContext.findPagerHandling(touch: Method): PagerHandling {
    val container = classDefBy(SWIPE_CONTAINER)
    val setter = container.methods.singleOrNull {
        it.name == PAGER_CHECK_SETTER && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
    } ?: refuse("$SWIPE_CONTAINER has no $PAGER_CHECK_SETTER(Z)V")
    val flags = setter.implementation!!.instructions.filter {
        it.opcode == Opcode.IPUT_BOOLEAN && it.field()?.definingClass == SWIPE_CONTAINER
    }.mapNotNull { it.field() }
    val flag = flags.singleOrNull()
        ?: refuse("expected $PAGER_CHECK_SETTER to write one boolean field of the container, found ${flags.size}")

    val where = "$SWIPE_CONTAINER's onInterceptTouchEvent"
    val code = touch.implementation!!.instructions.toList()
    val reads = code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].field()?.matches(flag) == true }
    val read = reads.singleOrNull() ?: refuse("expected $where to read $flag once, found ${reads.size}")
    val walks = code.indices.filter { at ->
        code[at].opcode.let { it == Opcode.INVOKE_STATIC || it == Opcode.INVOKE_STATIC_RANGE } &&
            code[at].called()?.let { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == WALK } == true
    }
    val walkCall = walks.singleOrNull() ?: refuse("expected $where to walk the views under the finger once, found ${walks.size}")
    val call = code[walkCall] as? RegisterRangeInstruction ?: refuse("$where doesn't hand the walk a register range")
    if (walkCall < read) refuse("$where walks the views before it reads $flag")
    if (touch.jumpTargets().any { it in read + 1..walkCall }) refuse("something in $where jumps between the read of $flag and the walk")
    if (!holds(code, read, walkCall, call.startRegister + call.registerCount - 1)) refuse("$where doesn't hand the walk $flag last")

    val walk = code[walkCall].called()!!
    val walkClass = classDefByOrNull(walk.definingClass) ?: refuse("the walk's class ${walk.definingClass} isn't in the app")
    val walkMethod = walkClass.methods.singleOrNull { it.matches(walk) } ?: refuse("${walk.definingClass} doesn't declare $walk")
    requirePublic(walkClass)
    if (!AccessFlags.PUBLIC.isSet(walkMethod.accessFlags) || !AccessFlags.STATIC.isSet(walkMethod.accessFlags)) {
        refuse("the walk $walk isn't a public static method")
    }
    val body = walkMethod.implementation!!.instructions.toList()
    if (body.none { it.called()?.matches(walk) == true }) refuse("the walk $walk never calls itself for the children")
    if (body.none { it.opcode == Opcode.INSTANCE_OF && (it as ReferenceInstruction).reference.toString() == VIEW_PAGER }) {
        refuse("the walk $walk never asks whether a view is a $VIEW_PAGER")
    }

    val inputs = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL && code[at].called()?.let {
            it.definingClass == VIEW_PAGER && it.name == SET_USER_INPUT && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Z")
        } == true
    }
    val inputCall = inputs.singleOrNull() ?: refuse("expected $where to call $VIEW_PAGER->$SET_USER_INPUT once, found ${inputs.size}")
    val pagerClass = classDefByOrNull(VIEW_PAGER) ?: refuse("this Instagram build has no $VIEW_PAGER")
    val setInput = pagerClass.methods.singleOrNull {
        it.name == SET_USER_INPUT && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
    } ?: refuse("$VIEW_PAGER has no $SET_USER_INPUT(Z)V")
    requirePublicInstance(setInput)
    val inputFlags = setInput.implementation!!.instructions.filter {
        it.opcode == Opcode.IPUT_BOOLEAN && it.field()?.definingClass == VIEW_PAGER
    }.mapNotNull { it.field() }
    val pagerInput = inputFlags.singleOrNull()
        ?: refuse("expected $VIEW_PAGER->$SET_USER_INPUT to write one boolean field of the pager, found ${inputFlags.size}")
    if (body.none { it.opcode == Opcode.IGET_BOOLEAN && it.field()?.matches(pagerInput) == true }) {
        refuse("the walk $walk doesn't read the pager's input flag $pagerInput")
    }
    requirePublic(pagerInput)

    return PagerHandling(walkCall, walk, inputCall, pagerInput)
}

/**
 * Whether [register] holds, at [call], what the read at [read] filled: the read's own register, or
 * one a move copied it to, with nothing in between writing over it. A wide write covers two
 * registers. The code between is straight.
 */
private fun holds(code: List<Instruction>, read: Int, call: Int, register: Int): Boolean {
    val holders = mutableSetOf((code[read] as OneRegisterInstruction).registerA)
    for (at in read + 1 until call) {
        val instruction = code[at]
        if (!instruction.opcode.setsRegister()) continue
        val target = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        val from = (instruction as? TwoRegisterInstruction)?.registerB
        if (instruction.opcode in MOVES && from in holders) holders += target else holders -= target
        if (instruction.opcode.setsWideRegister()) holders -= target + 1
    }
    return register in holders
}

/**
 * Puts [CHILD_SCROLLS_HOOK] in the walk's place and [PAGER_INPUT_HOOK] in the input call's, each on
 * the registers the call it replaces read, so a jump to either still lands on the hook, then
 * [TOUCHED_HOOK] on this and the event first, so the extension sees each move before the pager does.
 * Then writes the stubs: Instagram's own walk, whether a view is a pager, whether it takes swipes,
 * and the call turning that on or off. The walk takes six registers, always a range; the input call
 * takes two, never above v15. The first hook goes in last, as it moves every index after it.
 */
internal fun BytecodePatchContext.hookPagerHandling(touch: Method, found: PagerHandling) {
    val method = mutable(touch)
    val code = method.implementation!!.instructions
    val walk = code[found.walkCall] as RegisterRangeInstruction
    method.replaceInstruction(
        found.walkCall,
        "invoke-static/range { v${walk.startRegister} .. v${walk.startRegister + walk.registerCount - 1} }, $CHILD_SCROLLS_HOOK",
    )
    val input = code[found.inputCall] as FiveRegisterInstruction
    method.replaceInstruction(found.inputCall, "invoke-static { v${input.registerC}, v${input.registerD} }, $PAGER_INPUT_HOOK")
    method.addInstruction(0, "invoke-static/range { p0 .. p1 }, $TOUCHED_HOOK")

    stub("childScrolls", WALK, "Z", "invoke-static/range { p0 .. p5 }, ${found.walk}\nmove-result p0\nreturn p0")
    stub("pager", listOf(ANY), "Z", "instance-of p0, p0, $VIEW_PAGER\nreturn p0")
    stub("inputOn", listOf(ANY), "Z", "check-cast p0, $VIEW_PAGER\niget-boolean p0, p0, ${found.pagerInput}\nreturn p0")
    stub("input", listOf(ANY, "Z"), "V", "check-cast p0, $VIEW_PAGER\ninvoke-virtual { p0, p1 }, $VIEW_PAGER->$SET_USER_INPUT(Z)V\nreturn-void")
}

/** Every Instagram screen's touch dispatch, and the profile screen's resume and pause. */
internal class ProfileClose(val touch: Method, val resume: Method, val pause: Method)

/**
 * Finds where a right swipe on the open profile is caught. [FRAGMENT_ACTIVITY], which every
 * Instagram screen extends, has to have one dispatchTouchEvent of its own, which sees each touch
 * before any view does, with a local for the hook's answer and nothing jumping back to where the
 * hook goes, its start or right after Tap to play's call. [PROFILE_FRAGMENT] has to have an onResume
 * and an onPause of its own, instance methods taking nothing.
 */
internal fun BytecodePatchContext.findProfileClose(): ProfileClose {
    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuse("this build has no $FRAGMENT_ACTIVITY")
    val touches = activity.methods.filter {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) && it.implementation != null
    }
    val touch = touches.singleOrNull() ?: refuse("expected $FRAGMENT_ACTIVITY to have one dispatchTouchEvent of its own, found ${touches.size}")
    if (touch.localRegisterCount() < 1) refuse("$FRAGMENT_ACTIVITY->dispatchTouchEvent has no local register for the hook's answer")
    if (touch.closeHookAt() in touch.jumpTargets()) refuse("something in $FRAGMENT_ACTIVITY->dispatchTouchEvent jumps back to its start")
    val profile = classDefByOrNull(PROFILE_FRAGMENT) ?: refuse("this build has no $PROFILE_FRAGMENT")
    fun lifecycle(name: String) = profile.methods.singleOrNull {
        it.name == name && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$PROFILE_FRAGMENT has no $name() of its own")
    return ProfileClose(touch, lifecycle("onResume"), lifecycle("onPause"))
}

/**
 * First thing in the touch dispatch, hands the screen and the event to [CLOSES_PROFILE_HOOK]. When
 * it answers true the event was a right swipe closing the profile and is answered as handled.
 * Otherwise the code after it runs as it was. Tap to play reads every touch here too, so when its
 * call is already first, this one goes right after it; when Tap to play comes later, it goes in
 * front. First thing in the profile's resume and pause, hands the profile to [PROFILE_RESUMED_HOOK]
 * and [PROFILE_PAUSED_HOOK].
 */
internal fun BytecodePatchContext.hookProfileClose(found: ProfileClose) {
    val touch = mutable(found.touch)
    val at = touch.closeHookAt()
    touch.addInstructionsWithLabels(
        at,
        """
            invoke-static/range { p0 .. p1 }, $CLOSES_PROFILE_HOOK
            move-result v0
            if-eqz v0, :instagram
            return v0
        """,
        ExternalLabel("instagram", touch.getInstruction(at)),
    )
    mutable(found.resume).addInstruction(0, "invoke-static/range { p0 .. p0 }, $PROFILE_RESUMED_HOOK")
    mutable(found.pause).addInstruction(0, "invoke-static/range { p0 .. p0 }, $PROFILE_PAUSED_HOOK")
}

/** Where the close hook goes in the touch dispatch: right after Tap to play's call when that's first, else first. */
private fun Method.closeHookAt(): Int {
    val first = implementation?.instructions?.firstOrNull()?.called() ?: return 0
    return if ("${first.definingClass}->${first.name}(${first.parameterTypes.joinToString("")})${first.returnType}" == TAP_CLOCK) 1 else 0
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/** Writes [smali] first in the static stub [name] of [SWIPE_TARGETS], which takes [parameters] and answers [returns]. */
private fun BytecodePatchContext.stub(name: String, parameters: List<String>, returns: String, smali: String) {
    val stub = mutableClassDefBy(SWIPE_TARGETS).methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: refuse("$SWIPE_TARGETS has no static $name(${parameters.joinToString("")})$returns")
    stub.addInstructions(0, smali)
}

private fun instanceFields(classDef: ClassDef) = classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.matches(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun FieldReference.matches(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun Method.loads(string: String) = implementation?.instructions?.any {
    (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((it as ReferenceInstruction).reference as StringReference).string == string
} == true

private fun requirePublic(classDef: ClassDef) {
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("${classDef.type} isn't public, so the extension can't reach it")
}

private fun requirePublicInstance(method: Method) {
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) {
        refuse("${method.definingClass}->${method.name} isn't a public instance method")
    }
}

/** Throws unless [field]'s class and the field itself are public, so a stub in the extension's package can read it. */
private fun BytecodePatchContext.requirePublic(field: FieldReference) {
    val owner = classDefByOrNull(field.definingClass) ?: refuse("${field.definingClass} isn't in the app")
    val declared = owner.fields.singleOrNull { it.name == field.name && it.type == field.type }
        ?: refuse("${field.definingClass} doesn't declare $field")
    requirePublic(owner)
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("$field isn't public, so the extension can't read it")
}

private fun BytecodePatchContext.requirePublicMethod(method: MethodReference) {
    val owner = classDefByOrNull(method.definingClass) ?: refuse("${method.definingClass} isn't in the app")
    val declared = owner.methods.singleOrNull { it.matches(method) } ?: refuse("${method.definingClass} doesn't declare $method")
    requirePublic(owner)
    requirePublicInstance(declared)
}
