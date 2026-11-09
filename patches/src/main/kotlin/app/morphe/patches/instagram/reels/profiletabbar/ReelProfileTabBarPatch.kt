/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.profiletabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
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

private const val PATCH = "Keep the tab bar on a reel's profile"
internal const val REEL_PROFILE_STATUS = "reelProfileTabBar"
private const val ANY = "Ljava/lang/Object;"
internal const val REEL_PROFILE_HOOK = "$EXTENSION_PACKAGE/reels/ReelProfileTabBar;->open($ANY$ANY$ANY$ANY)Z"
internal const val REEL_PROFILE_TARGETS = "$EXTENSION_PACKAGE/reels/ReelProfileTabBarTargets;"
internal const val TAB_ROOT_HOOK = "$EXTENSION_PACKAGE/reels/ReelProfileTabBar;->keepTabRoot(I$ANY)I"

/** The markers of the two methods that open a reel author's profile in its own screen. */
internal const val AUTHOR_PROFILE = "android_purge_26_q3_AuthorInfoNavigator_navigateToNormalProfile"
internal const val VIEWER_PROFILE = "android_purge_26_q3_ClipsViewerUtils_launchProfile"

/** The Reels viewer's follow prompt, which puts a profile on the current tab, loads this. */
internal const val FOLLOW_PROMPT = "clips_follow_prompt"

/** The check for a tab sitting at its root loads this, the tag of the Home screen. */
internal const val FEED_TAG = "fragment_feed"

internal const val MODAL_ACTIVITY = "Lcom/instagram/modal/ModalActivity;"
internal const val SESSION = "Lcom/instagram/common/session/UserSession;"
internal const val LAUNCH_CONFIG = "Lcom/instagram/profile/intf/UserDetailLaunchConfig;"
internal const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
internal const val ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val CONTEXT = "Landroid/content/Context;"
private const val LIST = "Ljava/util/List;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * A method opening the profile's own screen: [hookAt] is the launcher's new-instance, whose register
 * [answer] holds nothing yet. [resumeAt] is the instruction after the launch, and the other four are
 * the registers holding what the profile is built from at [hookAt].
 */
internal class ProfileRoute(
    val method: Method,
    val hookAt: Int,
    val resumeAt: Int,
    val answer: Int,
    val activity: Int,
    val factory: Int,
    val session: Int,
    val config: Int,
    val factoryType: String,
)

/** How Instagram puts a profile on the current tab: [build] makes the screen, the [steps] of a [navigator] show it. */
internal class ProfilePush(val build: MethodReference, val navigator: MethodReference, val steps: List<MethodReference>)

/**
 * Instagram's check for a tab sitting at its root, and the call in it listing a fragment manager's
 * screens. Some builds inline the manager's own call, and read its [store] to ask that instead.
 */
internal class TabRootCheck(val method: Method, val store: FieldReference?, val fragments: MethodReference)

/**
 * A tab host's method deciding on the tab bar. The hook goes in at [hookAt], just past the read of
 * the root check into [answer], with the host itself in [host]. [manager] gets the host's fragment manager.
 */
internal class TabHost(val method: Method, val hookAt: Int, val answer: Int, val host: Int, val manager: MethodReference)

internal fun BytecodePatchContext.findProfileRoute(marker: String): ProfileRoute {
    val marked = classesLoadingString(marker).flatMap { owner -> owner.methods.filter { loadsString(it, marker) } }
    val method = marked.singleOrNull() ?: refuse("expected one method marked $marker, found ${marked.size}")
    val code = method.code()
    val modal = code.indices.singleOrNull { code[it].opcode == Opcode.CONST_CLASS && code[it].type() == MODAL_ACTIVITY }
        ?: refuse("$marker doesn't name $MODAL_ACTIVITY once")
    val bundle = (modal until code.size).firstOrNull { code[it].called()?.takes(BUNDLE, SESSION, LAUNCH_CONFIG) == true }
        ?: refuse("$marker builds no profile arguments after $MODAL_ACTIVITY")
    val hookAt = (bundle until code.size).firstOrNull { code[it].opcode == Opcode.NEW_INSTANCE }
        ?: refuse("$marker makes no launcher")
    val launcher = code[hookAt].type()
    val launch = (hookAt until code.size).firstOrNull {
        val call = code[it].called()
        code[it].opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == launcher && call.takes("V", CONTEXT)
    } ?: refuse("$marker never launches $launcher")
    if (launch + 1 >= code.size) refuse("nothing follows the launch in $marker")
    if (hookAt in method.jumpTargets()) refuse("something in $marker jumps to the launcher")

    val arguments = code[bundle].registers()
    if (arguments.size != 3) refuse("the profile arguments in $marker take ${arguments.size} registers")
    val (factory, session, config) = arguments
    val activity = code[launch].registers()[1]
    val answer = (code[hookAt] as OneRegisterInstruction).registerA
    for (register in arguments) {
        if ((bundle + 1 until hookAt).any { code[it].writes(register) }) refuse("$marker overwrites v$register before the launcher")
    }
    if ((hookAt until launch).any { code[it].writes(activity) }) refuse("$marker overwrites the activity after the launcher")
    if (listOf(activity, factory, session, config).any { it > 15 }) refuse("a register in $marker is out of the hook's reach")
    return ProfileRoute(method, hookAt, launch + 1, answer, activity, factory, session, config, code[bundle].called()!!.definingClass)
}

/**
 * Reads the push the follow prompt does: the factory's call building the Fragment, then a new
 * navigator from the activity and the session, and the calls that set it going.
 */
internal fun BytecodePatchContext.findProfilePush(factory: String): ProfilePush {
    val marked = classesLoadingString(FOLLOW_PROMPT).flatMap { owner -> owner.methods.filter { loadsString(it, FOLLOW_PROMPT) } }
    val method = marked.singleOrNull { candidate ->
        candidate.code().any { it.called()?.let { call -> call.definingClass == factory && call.takes(FRAGMENT, SESSION, LAUNCH_CONFIG) } == true }
    } ?: refuse("no one method loading $FOLLOW_PROMPT builds a profile with $factory")
    val code = method.code()
    val builds = code.indexOfFirst { it.called()?.takes(FRAGMENT, SESSION, LAUNCH_CONFIG) == true && it.called()!!.definingClass == factory }
    val created = (builds until code.size).firstOrNull { code[it].opcode == Opcode.NEW_INSTANCE } ?: refuse("the push makes no navigator")
    val navigator = code[created].type()
    val made = code[created + 1].called()?.takeIf { it.name == "<init>" && it.definingClass == navigator && it.parameterTypes.size == 2 && it.parameterTypes[0].toString() == ACTIVITY }
        ?: refuse("$navigator isn't built from an activity and a session")
    val steps = (created + 2 until code.size).mapNotNull { at ->
        code[at].called()?.takeIf { code[at].opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == navigator }
    }
    val shown = steps.count { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(FRAGMENT) }
    if (shown != 1 || steps.any { it.parameterTypes.isNotEmpty() && it.parameterTypes.map(CharSequence::toString) != listOf(FRAGMENT) }) {
        refuse("$navigator's steps in the follow prompt aren't one Fragment and calls with no arguments: $steps")
    }
    return ProfilePush(code[builds].called()!!, made, steps)
}

internal fun BytecodePatchContext.findTabRootCheck(): TabRootCheck {
    val marked = classesLoadingString(FEED_TAG).flatMap { owner ->
        owner.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.size == 1 && loadsString(it, FEED_TAG) }
    }
    val method = marked.singleOrNull() ?: refuse("expected one root check loading $FEED_TAG, found ${marked.size}")
    val manager = method.parameterTypes.single().toString()
    val code = method.code()
    val listing = code.indices.singleOrNull { code[it].called()?.takes(LIST) == true } ?: refuse("the root check doesn't list the screens of $manager")
    val fragments = code[listing].called()!!
    if (fragments.definingClass == manager) return TabRootCheck(method, null, fragments)
    val read = code.getOrNull(listing - 1)?.takeIf { it.opcode == Opcode.IGET_OBJECT }
    val store = ((read as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { it.definingClass == manager && it.type == fragments.definingClass }
        ?: refuse("the root check lists screens from ${fragments.definingClass}, which $manager doesn't hold")
    return TabRootCheck(method, store, fragments)
}

/**
 * The two tab hosts' methods asking the root check: static, void and taking only the host. The
 * check's other callers want the real answer, for swipes and the back button, so it stays as it is.
 */
internal fun BytecodePatchContext.findTabHosts(check: Method): List<TabHost> {
    val manager = check.parameterTypes.single().toString()
    val asking = classesLoadingString(FEED_TAG).flatMap { owner ->
        owner.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf(owner.type) &&
                method.code().any { it.asks(check) }
        }
    }
    if (asking.size != 2) refuse("expected two tab hosts asking the root check, found ${asking.size}")
    return asking.map { tabHost(it, check, manager) }
}

private fun tabHost(method: Method, check: Method, manager: String): TabHost {
    val owner = method.definingClass
    val code = method.code()
    val call = code.indices.singleOrNull { code[it].asks(check) } ?: refuse("$owner doesn't ask the root check once")
    val read = code.getOrNull(call + 1)
    if (read?.opcode != Opcode.MOVE_RESULT) refuse("$owner doesn't read the root check")
    val hookAt = call + 2
    if (hookAt in method.jumpTargets()) refuse("something in $owner jumps past the root check")
    val answer = (read as OneRegisterInstruction).registerA
    val host = method.implementation!!.registerCount - 1
    if ((0 until hookAt).any { code[it].writes(host) }) refuse("$owner overwrites the host before the root check")
    if (answer > 15 || host > 15) refuse("a register in $owner is out of the hook's reach")
    val getters = code.mapNotNull { it.called() }.filter { it.definingClass == FRAGMENT && it.takes(manager) }.distinctBy { it.toString() }
    val getter = getters.singleOrNull() ?: refuse("$owner gets its fragment manager ${getters.size} ways")
    return TabHost(method, hookAt, answer, host, getter)
}

/** Lets the extension answer the root check, in the host's own decision on the tab bar only. */
internal fun BytecodePatchContext.keepProfileAsTabRoot(host: TabHost) {
    val parameters = host.method.parameterTypes.map(CharSequence::toString)
    val method = mutableClassDefBy(host.method.definingClass).methods.first {
        it.name == host.method.name && it.returnType == host.method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    }
    method.addInstructions(
        host.hookAt,
        """
            invoke-static { v${host.answer}, v${host.host} }, $TAB_ROOT_HOOK
            move-result v${host.answer}
        """.trimIndent(),
    )
}

/** Writes the stub the extension lists a host's screens through. */
internal fun BytecodePatchContext.writeFragmentList(manager: MethodReference, check: TabRootCheck) {
    val stub = mutableClassDefBy(REEL_PROFILE_TARGETS).methods.single { it.name == "fragments" }
    stub.addInstructions(
        0,
        """
            check-cast p0, $FRAGMENT
            invoke-virtual { p0 }, $manager
            move-result-object p0
            ${check.store?.let { "iget-object p0, p0, $it" } ?: ""}
            invoke-virtual { p0 }, ${check.fragments}
            move-result-object p0
            return-object p0
        """.trimIndent(),
    )
}

/** Where the launcher would be made, asks the extension first and skips the launch when the profile went on the tab. */
internal fun BytecodePatchContext.keepProfileOnTab(route: ProfileRoute) {
    val parameters = route.method.parameterTypes.map(CharSequence::toString)
    val method = mutableClassDefBy(route.method.definingClass).methods.first {
        it.name == route.method.name && it.returnType == route.method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    }
    method.addInstructionsWithLabels(
        route.hookAt,
        """
            invoke-static { v${route.activity}, v${route.factory}, v${route.session}, v${route.config} }, $REEL_PROFILE_HOOK
            move-result v${route.answer}
            if-nez v${route.answer}, :opened
        """.trimIndent(),
        ExternalLabel("opened", method.getInstruction(route.resumeAt)),
    )
}

/** Writes the stub the extension pushes the profile through. Its four parameters are the only registers it has. */
internal fun BytecodePatchContext.writeProfilePush(push: ProfilePush) {
    val stub = mutableClassDefBy(REEL_PROFILE_TARGETS).methods.single { it.name == "push" }
    val replay = push.steps.joinToString("\n") { step ->
        if (step.parameterTypes.isEmpty()) "invoke-virtual { p3 }, $step" else "invoke-virtual { p3, p1 }, $step"
    }
    stub.addInstructions(
        0,
        """
            check-cast p0, $ACTIVITY
            check-cast p1, ${push.build.definingClass}
            check-cast p2, $SESSION
            check-cast p3, $LAUNCH_CONFIG
            invoke-virtual { p1, p2, p3 }, ${push.build}
            move-result-object p1
            new-instance p3, ${push.navigator.definingClass}
            invoke-direct { p3, p0, p2 }, ${push.navigator}
        """.trimIndent() + "\n" + replay + "\nreturn-void",
    )
}

/** Instagram's classes the extension's stub reaches have to be public, or the call dies on the phone. */
internal fun BytecodePatchContext.requirePushIsPublic(push: ProfilePush) = requirePublic(listOf(push.build, push.navigator) + push.steps)

internal fun BytecodePatchContext.requirePublic(field: FieldReference) {
    val owner = classDefByOrNull(field.definingClass) ?: refuse("${field.definingClass} isn't in the app")
    val read = owner.fields.singleOrNull { it.name == field.name && it.type == field.type } ?: refuse("$field isn't in ${field.definingClass}")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(read.accessFlags)) refuse("$field isn't public")
}

internal fun BytecodePatchContext.requirePublic(calls: List<MethodReference>) {
    for (call in calls) {
        val owner = classDefByOrNull(call.definingClass) ?: refuse("${call.definingClass} isn't in the app")
        val method = owner.methods.singleOrNull {
            it.name == call.name && it.returnType == call.returnType &&
                it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
        } ?: refuse("$call isn't in ${call.definingClass}")
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) refuse("$call isn't public")
    }
}

private fun MethodReference.takes(returns: String, vararg parameters: String) =
    returnType == returns && parameterTypes.map(CharSequence::toString) == parameters.toList()

private fun Instruction.asks(check: Method) = called()?.let { call ->
    call.definingClass == check.definingClass && call.name == check.name && call.returnType == check.returnType &&
        call.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
} == true

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.type() = ((this as ReferenceInstruction).reference as TypeReference).type

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as OneRegisterInstruction).registerA
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/**
 * Keeps the tab bar on a profile opened from a reel (#385). Instagram opens it in a screen of its
 * own with no tab bar, so the extension gets to put it on the current tab first. On accounts where
 * the tab hosts hide the bar for anything pushed on a tab, a profile on top also counts as the
 * tab's root. See ReelProfileTabBar in the extension. In the default selection with its switch off.
 */
@Suppress("unused")
val reelProfileTabBarPatch = bytecodePatch(
    name = "Keep the tab bar on a reel's profile",
    description = "A profile you open from a reel stays on the tab you're on, with the tab bar under it. Back goes " +
        "back to the reel. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(REEL_PROFILE_STATUS)
        val routes = listOf(findProfileRoute(AUTHOR_PROFILE), findProfileRoute(VIEWER_PROFILE))
        val factory = routes.map { it.factoryType }.distinct().singleOrNull() ?: refuse("the two routes build the profile differently")
        val push = findProfilePush(factory)
        requirePushIsPublic(push)
        val check = findTabRootCheck()
        val hosts = findTabHosts(check.method)
        val manager = hosts.map { it.manager }.distinctBy { it.toString() }.singleOrNull() ?: refuse("the tab hosts get their fragment manager differently")
        requirePublic(listOf(manager, check.fragments))
        check.store?.let { requirePublic(it) }
        routes.forEach { keepProfileOnTab(it) }
        writeProfilePush(push)
        hosts.forEach { keepProfileAsTabRoot(it) }
        writeFragmentList(manager, check)
        enableStatus(REEL_PROFILE_STATUS)
    }
}
