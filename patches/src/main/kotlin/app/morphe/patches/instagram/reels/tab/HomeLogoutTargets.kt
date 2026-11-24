/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.feed.back.enumConstantName
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

/** The extension's side of Log out in Home's menu (#850). */
internal const val HOME_LOGOUT = "$EXTENSION_PACKAGE/settings/HomeLogout;"
internal const val LOGOUT_WIRED = "$HOME_LOGOUT->wired()Z"
internal const val LOGOUT_START = "$HOME_LOGOUT->start(Ljava/lang/Object;)Z"

/** What Instagram's Log out controller logs as its question about the saved login loads, on every 456 build. */
internal const val LOGOUT_QUESTION = "logout_d2_loaded"

/** What the controller's call that signs out right away writes first. The start this patch calls never does. */
internal const val SIGNS_OUT_NOW = "is_from_log_out"

/** The logout Instagram's own settings row asks the controller for. */
internal const val LOG_OUT_ALL = "ALL"

internal const val FRAGMENT ="Landroidx/fragment/app/Fragment;"
internal const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val ANALYTICS_MODULE = "getAnalyticsModule"
private const val ENUM = "Ljava/lang/Enum;"
private const val LOGOUT_PATCH = "Hide the Reels tab: Log out in Home's menu"

/** The controller found and the body [LOGOUT_START] gets. */
internal class HomeLogoutHook(val controller: String, val body: String)

/**
 * Finds what Instagram's own settings row runs to log out, or null, after the patch log says why, so
 * the rest of Hide the Reels tab goes in without it and the menu leaves Log out off.
 *
 * The row makes Instagram's Log out controller, the one class logging [LOGOUT_QUESTION], from the
 * screen it's on: a fragment, the activity, its fragment manager, an analytics module and the
 * session. Then it calls the controller's one public start taking just the kind of logout, with
 * [LOG_OUT_ALL]. That start hands the work to the controller's own flow, which asks its questions,
 * and never signs out itself, so a start that writes [SIGNS_OUT_NOW] is refused. The body makes the
 * controller the same way from Instagram's main screen, which keeps each of the five as its own
 * getter answers it.
 */
internal fun BytecodePatchContext.findHomeLogout(): HomeLogoutHook? {
    fun skip(why: String): HomeLogoutHook? {
        patchLog.warning("$LOGOUT_PATCH: $why. The patch goes in without it.")
        return null
    }
    stub(LOGOUT_WIRED)
    stub(LOGOUT_START)

    val controllers = classesHolding(LOGOUT_QUESTION).filter { it.constructor() != null }
    val controller = controllers.singleOrNull()
        ?: return skip("${controllers.size} Log out controllers log $LOGOUT_QUESTION")
    val constructor = controller.constructor()!!
    val (_, _, manager, module, _) = constructor.parameters()

    val starts = controller.methods.filter { method ->
        !method.isStatic() && method.isPublic() && method.returnType == "V" &&
            method.parameters().singleOrNull()?.let { classDefByOrNull(it)?.superclass == ENUM } == true
    }
    val start = starts.singleOrNull() ?: return skip("${starts.size} starts on ${controller.type} take a kind of logout alone")
    if (loadsString(start, SIGNS_OUT_NOW)) return skip("${controller.type}->${start.name} signs out right away")
    val kind = classDefBy(start.parameters().single())
    val all = kind.fields.filter { it.type == kind.type && AccessFlags.STATIC.isSet(it.accessFlags) }
        .singleOrNull { enumConstantName(kind, it) == LOG_OUT_ALL }
        ?: return skip("${kind.type} has no $LOG_OUT_ALL")

    val main = classDefByOrNull(MAIN_ACTIVITY) ?: return skip("$MAIN_ACTIVITY is missing")
    if (!extendsType(main, FRAGMENT_ACTIVITY)) return skip("$MAIN_ACTIVITY isn't a $FRAGMENT_ACTIVITY")
    val fragment = main.only {
        it.isStatic() && it.parameters() == listOf(MAIN_ACTIVITY) && it.returnType == FRAGMENT
    } ?: return skip("$MAIN_ACTIVITY has no one static getter of its fragment")
    val session = main.only {
        it.isStatic() && it.parameters() == listOf(MAIN_ACTIVITY) && it.returnType == SESSION
    } ?: return skip("$MAIN_ACTIVITY has no one static getter of its session")
    val analytics = main.only {
        !it.isStatic() && it.name == ANALYTICS_MODULE && it.parameters().isEmpty() && it.returnType == module
    } ?: return skip("$MAIN_ACTIVITY has no $ANALYTICS_MODULE answering a $module")
    val managers = classDefBy(FRAGMENT_ACTIVITY).methods.filter {
        !it.isStatic() && it.isPublic() && it.parameters().isEmpty() && it.returnType == manager
    }
    val fragments = managers.singleOrNull() ?: return skip("$FRAGMENT_ACTIVITY has ${managers.size} getters of a $manager")

    val reached = listOf(controller, kind, main)
    reached.firstOrNull { !AccessFlags.PUBLIC.isSet(it.accessFlags) }?.let { return skip("${it.type} isn't public") }
    if (!AccessFlags.PUBLIC.isSet(all.accessFlags)) return skip("${all.name} of ${kind.type} isn't public")

    // Seven registers: the controller, then the five the constructor takes in its order, and the screen.
    val body = """
        check-cast p0, $MAIN_ACTIVITY
        invoke-static { p0 }, ${fragment.reference()}
        move-result-object v1
        invoke-virtual { p0 }, ${fragments.reference()}
        move-result-object v3
        invoke-virtual { p0 }, ${analytics.reference()}
        move-result-object v4
        invoke-static { p0 }, ${session.reference()}
        move-result-object v5
        new-instance v0, ${controller.type}
        move-object v2, p0
        invoke-direct/range { v0 .. v5 }, ${constructor.reference()}
        sget-object v1, ${kind.type}->${all.name}:${kind.type}
        invoke-virtual { v0, v1 }, ${start.reference()}
        const/4 v0, 0x1
        return v0
    """
    return HomeLogoutHook(controller.type, body)
}

/** Writes both bodies: [LOGOUT_WIRED] answers true and [LOGOUT_START] starts the found Log out. */
internal fun BytecodePatchContext.writeHomeLogout(hook: HomeLogoutHook) {
    rewrite(stub(LOGOUT_WIRED), 1, """
        const/4 v0, 0x1
        return v0
    """)
    rewrite(stub(LOGOUT_START), 7, hook.body)
}

private fun BytecodePatchContext.stub(signature: String): MutableMethod {
    val extension = mutableClassDefByOrNull(HOME_LOGOUT) ?: throw PatchException("$LOGOUT_PATCH: the extension has no $HOME_LOGOUT")
    return extension.methods.singleOrNull { it.reference() == signature && it.isPublic() && it.isStatic() }
        ?: throw PatchException("$LOGOUT_PATCH: the extension has no public static $signature")
}

private fun BytecodePatchContext.rewrite(method: MutableMethod, registers: Int, body: String) {
    val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(registers, emptyList(), null, null)).toMutable().apply {
        addInstructions(0, body.trimIndent())
    }
    val owner = mutableClassDefBy(method.definingClass)
    owner.methods.remove(method)
    owner.methods.add(replacement)
}

/** [type] is [ancestor] or extends it. */
private fun BytecodePatchContext.extendsType(type: ClassDef, ancestor: String): Boolean {
    var at: String? = type.type
    while (at != null) {
        if (at == ancestor) return true
        at = classDefByOrNull(at)?.superclass
    }
    return false
}

/** The controller's constructor from a fragment, the activity, two of Instagram's own and the session, or null. */
private fun ClassDef.constructor(): Method? = methods.singleOrNull {
    val parameters = it.parameters()
    it.name == "<init>" && it.isPublic() && parameters.size == 5 && parameters[0] == FRAGMENT &&
        parameters[1] == FRAGMENT_ACTIVITY && parameters[4] == SESSION
}

/** The one method [wanted] picks, when it's public, or null. */
private fun ClassDef.only(wanted: (Method) -> Boolean): Method? =
    methods.filter(wanted).singleOrNull()?.takeIf { it.isPublic() }

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Method.reference(): String = "$definingClass->$name(${parameters().joinToString("")})$returnType"
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.isPublic() = AccessFlags.PUBLIC.isSet(accessFlags)
