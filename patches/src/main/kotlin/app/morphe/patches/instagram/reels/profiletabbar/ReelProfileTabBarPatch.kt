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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Keep the tab bar on a reel's profile"
internal const val REEL_PROFILE_STATUS = "reelProfileTabBar"
private const val ANY = "Ljava/lang/Object;"
internal const val REEL_PROFILE_HOOK = "$EXTENSION_PACKAGE/reels/ReelProfileTabBar;->open($ANY$ANY$ANY$ANY)Z"
internal const val REEL_PROFILE_TARGETS = "$EXTENSION_PACKAGE/reels/ReelProfileTabBarTargets;"

/** The markers of the two methods that open a reel author's profile in its own screen. */
internal const val AUTHOR_PROFILE = "android_purge_26_q3_AuthorInfoNavigator_navigateToNormalProfile"
internal const val VIEWER_PROFILE = "android_purge_26_q3_ClipsViewerUtils_launchProfile"

/** The Reels viewer's follow prompt, which puts a profile on the current tab, loads this. */
internal const val FOLLOW_PROMPT = "clips_follow_prompt"

internal const val MODAL_ACTIVITY = "Lcom/instagram/modal/ModalActivity;"
internal const val SESSION = "Lcom/instagram/common/session/UserSession;"
internal const val LAUNCH_CONFIG = "Lcom/instagram/profile/intf/UserDetailLaunchConfig;"
internal const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
internal const val ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val CONTEXT = "Landroid/content/Context;"

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
internal fun BytecodePatchContext.requirePushIsPublic(push: ProfilePush) {
    for (call in listOf(push.build, push.navigator) + push.steps) {
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
 * own with no tab bar, so the extension gets to put it on the current tab first. See
 * ReelProfileTabBar in the extension. In the default selection with its switch off.
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
        routes.forEach { keepProfileOnTab(it) }
        writeProfilePush(push)
        enableStatus(REEL_PROFILE_STATUS)
    }
}
